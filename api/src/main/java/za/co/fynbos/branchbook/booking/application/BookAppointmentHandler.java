package za.co.fynbos.branchbook.booking.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fynbos.branchbook.booking.application.IdempotencyKeyStore.Cancelled;
import za.co.fynbos.branchbook.booking.application.IdempotencyKeyStore.New;
import za.co.fynbos.branchbook.booking.application.IdempotencyKeyStore.Replay;
import za.co.fynbos.branchbook.booking.application.IdempotencyKeyStore.Reused;
import za.co.fynbos.branchbook.booking.domain.Appointment;
import za.co.fynbos.branchbook.booking.domain.CustomerDetails;
import za.co.fynbos.branchbook.catalogue.application.BranchCalendarProvider;
import za.co.fynbos.branchbook.catalogue.domain.BranchCalendar;
import za.co.fynbos.branchbook.catalogue.domain.ServiceOffer;
import za.co.fynbos.branchbook.scheduling.application.AlternativeSlots;
import za.co.fynbos.branchbook.scheduling.domain.SlotCalendar;
import za.co.fynbos.branchbook.shared.config.BookingProperties;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * Books an appointment: the one transaction that decides who gets a seat (docs/architecture.md,
 * section 4; ADR-0002).
 *
 * <p>{@link #book} runs the ten steps in one READ COMMITTED transaction, one line each:
 *
 * <ol>
 *   <li>Claim the {@code Idempotency-Key}, or replay what it already booked, or refuse it if that
 *       booking has been cancelled (BR-12, BR-14).
 *   <li>Load the branch and how it offers the service (BR-6).
 *   <li>Check the requested start against the slot rules, by their fixed precedence (BR-1 to BR-5,
 *       BR-21).
 *   <li>Lock the customer's mobile number, so their bookings queue (BR-22).
 *   <li>Refuse a second upcoming appointment for the service, then one that overlaps another
 *       appointment of the customer (BR-10, BR-9).
 *   <li>Lock how the branch offers the service, and read its capacity (BR-7).
 *   <li>Take the lowest free seat, or answer that the slot is full.
 *   <li>Insert the appointment and flush it, so a database rule it breaks is reported here.
 *   <li>Write the confirmation SMS in the same transaction (ADR-0003).
 *   <li>Record the appointment against the key, then commit.
 * </ol>
 *
 * <p>The locks are always taken in this order, so concurrent bookings queue and never deadlock;
 * each check after a lock sees every booking that committed before it. The database's exclusion
 * constraints stay the backstop for any path that skips them (BR-8). A reference collision, a
 * deadlock or a serialization failure runs the whole transaction once more; a lock that is not
 * granted in time is {@code 503 TRY_AGAIN}. Every instant comes from the injected clock, read once
 * per attempt. Log lines name the appointment by id, never by reference, name or number.
 */
@Service
public class BookAppointmentHandler {

  private static final Logger log = LoggerFactory.getLogger(BookAppointmentHandler.class);

  private final TransactionTemplate transaction;
  private final IdempotencyKeyStore keys;
  private final BranchCalendarProvider calendars;
  private final BookingLocks locks;
  private final AppointmentRepository appointments;
  private final ConfirmationOutbox outbox;
  private final ReferenceGenerator references;
  private final AlternativeSlots alternatives;
  private final AppointmentViews views;
  private final Clock clock;
  private final BookingProperties booking;

  BookAppointmentHandler(
      PlatformTransactionManager transactionManager,
      IdempotencyKeyStore keys,
      BranchCalendarProvider calendars,
      BookingLocks locks,
      AppointmentRepository appointments,
      ConfirmationOutbox outbox,
      ReferenceGenerator references,
      AlternativeSlots alternatives,
      AppointmentViews views,
      Clock clock,
      BookingProperties booking) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setName("BookAppointmentHandler.book");
    this.transaction = template;
    this.keys = keys;
    this.calendars = calendars;
    this.locks = locks;
    this.appointments = appointments;
    this.outbox = outbox;
    this.references = references;
    this.alternatives = alternatives;
    this.views = views;
    this.clock = clock;
    this.booking = booking;
  }

  /**
   * Books an appointment, or returns the one this request's {@code Idempotency-Key} already booked.
   *
   * @param command the parsed request
   * @return the new appointment, or the replayed one, as it is after the commit
   * @throws ProblemException for every refusal, with its code: 404, 409, 422 or 503
   */
  public BookingResult handle(BookAppointmentCommand command) {
    try {
      try {
        return attempt(command);
      } catch (ReferenceCollisionException | TransactionConflictException first) {
        log.info("booking.rerun cause={}", first.getClass().getSimpleName());
        return rerun(command);
      }
    } catch (ProblemException refused) {
      log.info("booking.refused code={}", refused.code());
      throw refused;
    }
  }

  /** The one re-run. A second conflict is a 503; a second collision escapes as a 500. */
  private BookingResult rerun(BookAppointmentCommand command) {
    try {
      return attempt(command);
    } catch (TransactionConflictException again) {
      throw new ProblemException(
          ProblemCode.TRY_AGAIN,
          "We could not complete your request. Please try again.",
          Map.of(),
          booking.conflictRetryAfter());
    }
  }

  private BookingResult attempt(BookAppointmentCommand command) {
    Instant now = clock.instant();
    Booked booked;
    try {
      booked = Objects.requireNonNull(transaction.execute(status -> book(command, now)));
    } catch (SlotFullException full) {
      // Found after the rollback, so the search holds no lock and sees every committed booking.
      throw new SlotFullException(
          alternatives.after(command.branchCode(), command.serviceCode(), command.startsAt()));
    }
    if (booked.replayed()) {
      // A cancel may delete the appointment between this commit and this read: its booking was
      // cancelled, which the key now answers, as it would have a moment later.
      AppointmentView view =
          views
              .findById(booked.appointmentId(), now)
              .orElseThrow(IdempotencyKeyReusedException::bookingCancelled);
      log.info("booking.replayed appointmentId={}", booked.appointmentId());
      return new BookingResult.Replayed(view);
    }
    AppointmentView view = views.byId(booked.appointmentId(), now);
    log.info(
        "booking.confirmed appointmentId={} branch={} service={} seat={}",
        booked.appointmentId(),
        command.branchCode(),
        command.serviceCode(),
        booked.seat());
    return new BookingResult.Created(view);
  }

  private Booked book(BookAppointmentCommand c, Instant now) {
    if (reserveIdempotencyKey(c, now) instanceof Replay replay) { // 1
      return Booked.replay(replay.appointmentId());
    }
    BranchCalendar calendar = calendars.calendarFor(c.branchCode()); // 2
    ServiceOffer service = calendars.offerFor(calendar.branch(), c.serviceCode()); // 2
    Instant endsAt = rejectUnbookableStart(calendar, service, c.startsAt(), now); // 3
    locks.lockCustomer(c.mobile()); // 4
    rejectDuplicateThenOverlap(c, service, endsAt, now); // 5
    int capacity = locks.lockSeats(calendar.branch().id(), service.serviceId()); // 6
    int seat = lowestFreeSeat(calendar, service, c.startsAt(), endsAt, capacity); // 7
    Appointment appointment = insertAppointment(c, calendar, service, seat, now); // 8
    outbox.enqueueConfirmation(Confirmation.of(appointment, calendar.branch(), service)); // 9
    keys.complete(c.idempotencyKey(), appointment.id()); // 10
    return Booked.created(appointment.id(), seat);
  }

  private IdempotencyKeyStore.Reservation reserveIdempotencyKey(
      BookAppointmentCommand c, Instant now) {
    IdempotencyKeyStore.Reservation reservation =
        keys.reserve(c.idempotencyKey(), c.requestHash(), now);
    return switch (reservation) {
      case Reused _ -> throw new IdempotencyKeyReusedException();
      case Cancelled _ -> throw IdempotencyKeyReusedException.bookingCancelled();
      case New fresh -> fresh;
      case Replay replay -> replay;
    };
  }

  /** Returns the appointment's end, or refuses a start that is not one of the slots. */
  private Instant rejectUnbookableStart(
      BranchCalendar calendar, ServiceOffer service, Instant startsAt, Instant now) {
    new SlotCalendar(calendar, service, now, booking.leadTime(), booking.horizon())
        .validate(startsAt)
        .ifPresent(
            problem -> {
              throw new SlotNotBookableException(problem);
            });
    return startsAt.plus(service.duration());
  }

  private void rejectDuplicateThenOverlap(
      BookAppointmentCommand c, ServiceOffer service, Instant endsAt, Instant now) {
    if (appointments.hasUpcomingActiveForService(c.mobile(), service.serviceId(), now)) {
      throw new DuplicateAppointmentException();
    }
    if (appointments.hasOverlappingActive(c.mobile(), c.startsAt(), endsAt)) {
      throw new CustomerOverlapException();
    }
  }

  private int lowestFreeSeat(
      BranchCalendar calendar,
      ServiceOffer service,
      Instant startsAt,
      Instant endsAt,
      int capacity) {
    return appointments
        .findLowestFreeSeat(calendar.branch().id(), service.serviceId(), startsAt, endsAt, capacity)
        .orElseThrow(SlotFullException::new);
  }

  private Appointment insertAppointment(
      BookAppointmentCommand c,
      BranchCalendar calendar,
      ServiceOffer service,
      int seat,
      Instant now) {
    Appointment appointment =
        Appointment.confirm(
            references.next(),
            calendar.branch().id(),
            service.serviceId(),
            service.duration(),
            seat,
            c.startsAt(),
            CustomerDetails.of(c.firstName(), c.lastName(), c.mobile()),
            now);
    appointments.insert(appointment);
    return appointment;
  }

  /**
   * What one transaction did: booked an appointment on a seat, or found the one to replay.
   *
   * @param appointmentId the appointment
   * @param replayed whether it was booked by an earlier request
   * @param seat the seat it took, or 0 for a replay
   */
  private record Booked(UUID appointmentId, boolean replayed, int seat) {

    static Booked created(UUID appointmentId, int seat) {
      return new Booked(appointmentId, false, seat);
    }

    static Booked replay(UUID appointmentId) {
      return new Booked(appointmentId, true, 0);
    }
  }
}
