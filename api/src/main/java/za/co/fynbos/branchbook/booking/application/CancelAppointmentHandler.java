package za.co.fynbos.branchbook.booking.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fynbos.branchbook.booking.domain.Appointment;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;
import za.co.fynbos.branchbook.catalogue.application.CatalogueLookup;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * Cancels an appointment for the customer who proves it with its reference and the last four digits
 * of the mobile number (BR-13, BR-14), and deletes it at once: a cancelled booking is personal data
 * with no further use. Only its cancellation SMS is kept, until it is sent or has failed for good,
 * and its idempotency keys, until they expire. It is the only change the API makes to an existing
 * appointment.
 *
 * <p>{@link #cancel} runs these steps in one READ COMMITTED transaction:
 *
 * <ol>
 *   <li>Count the try with the {@link LookupAttemptLimiter}, as a lookup is counted.
 *   <li>Load the appointment by reference through JPA, without a lock.
 *   <li>Compare the digits in constant time; a mismatch is {@code 404} and counts as a failure.
 *   <li>{@link Appointment#cancel}, in memory: a started appointment is {@code 409
 *       TOO_LATE_TO_CANCEL}.
 *   <li>Lock the appointment's row with its version checked, so a concurrent cancel or purge that
 *       got there first is found before anything is written.
 *   <li>Delete every confirmation, waiting for one the dispatcher is sending, and write the
 *       cancellation SMS (BR-16, BR-24).
 *   <li>Render the response from the appointment in memory and that one SMS.
 *   <li>Delete the appointment, with its version checked; the foreign keys keep the cancellation
 *       SMS and the idempotency keys with no appointment.
 *   <li>Commit.
 * </ol>
 *
 * <p>From the commit on, the reference is unknown: opening or cancelling it again is {@code 404
 * APPOINTMENT_NOT_FOUND} and counts as a wrong try, exactly as for a reference never issued. Two
 * cancels of one appointment at once, for example from two tabs, both load it before either
 * commits; the second waits for the first's lock, finds the row gone and answers that same counted
 * {@code 404}. So does a cancel whose appointment the retention purge deleted after it was loaded
 * (see {@link RetentionPurge}). A lock not granted within 2 seconds, such as on a confirmation the
 * dispatcher is still sending, is {@code 503 TRY_AGAIN}. Every instant comes from the injected
 * clock, read once and cut to the microsecond the database keeps. Nothing is read after the commit,
 * so the response cannot fail once the cancel is done. Log lines name the appointment by id, never
 * by reference, name or number.
 */
@Service
public class CancelAppointmentHandler {

  private static final Logger log = LoggerFactory.getLogger(CancelAppointmentHandler.class);

  private final TransactionTemplate transaction;
  private final AppointmentAccess access;
  private final AppointmentRepository appointments;
  private final CatalogueLookup catalogue;
  private final ConfirmationOutbox outbox;
  private final AppointmentViews views;
  private final Clock clock;

  CancelAppointmentHandler(
      PlatformTransactionManager transactionManager,
      AppointmentAccess access,
      AppointmentRepository appointments,
      CatalogueLookup catalogue,
      ConfirmationOutbox outbox,
      AppointmentViews views,
      Clock clock) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setName("CancelAppointmentHandler.cancel");
    this.transaction = template;
    this.access = access;
    this.appointments = appointments;
    this.catalogue = catalogue;
    this.outbox = outbox;
    this.views = views;
    this.clock = clock;
  }

  /**
   * Cancels an appointment and deletes it.
   *
   * @param reference the reference as the customer typed it; case and hyphens do not matter
   * @param mobileLast4 the last four digits of the mobile number, already checked to be digits
   * @return the appointment as the cancel left it, just before deleting it: cancelled, with only
   *     its cancellation SMS
   * @throws AppointmentNotFoundException if the reference and the digits match no appointment,
   *     including one this or another cancel has deleted, or the retention purge deleted before the
   *     cancel could lock it
   * @throws LookupRateLimitedException if the reference has had too many wrong tries
   * @throws TooLateToCancelException if the appointment has started
   */
  public AppointmentView cancel(String reference, String mobileLast4) {
    try {
      AppointmentReference parsed =
          AppointmentReference.parse(reference).orElseThrow(AppointmentNotFoundException::new);
      Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
      Cancelled cancelled;
      try {
        cancelled =
            Objects.requireNonNull(transaction.execute(status -> cancel(parsed, mobileLast4, now)));
      } catch (OptimisticLockingFailureException gone) {
        // Deleted, by another cancel or the purge, after its digits matched: unknown from now on.
        log.info("cancel.gone cause=VersionConflict");
        access.countAsUnknown(parsed, now);
        throw new AppointmentNotFoundException();
      }
      log.info("cancel.deleted appointmentId={}", cancelled.appointmentId());
      return cancelled.view();
    } catch (ProblemException refused) {
      log.info("cancel.refused code={}", refused.code());
      throw refused;
    }
  }

  private Cancelled cancel(AppointmentReference reference, String mobileLast4, Instant now) {
    Appointment appointment = access.open(reference, mobileLast4, now); // 1, 2, 3
    return switch (appointment.cancel(now)) { // 4
      case TOO_LATE -> throw new TooLateToCancelException();
      case ALREADY_CANCELLED -> {
        // Only an appointment cancelled before cancels deleted their bookings, or by hand: its
        // reference is answered as a deleted one is, and counted.
        access.countAsUnknown(reference, now);
        throw new AppointmentNotFoundException();
      }
      case CANCELLED -> {
        appointments.lockForDeletion(appointment); // 5
        NotificationSummary sms =
            outbox.enqueueCancellation(
                Cancellation.of(
                    appointment,
                    catalogue.branch(appointment.branchId()),
                    catalogue.service(appointment.serviceId()),
                    now)); // 6
        AppointmentView view = views.of(appointment, now, List.of(sms)); // 7
        appointments.delete(appointment); // 8
        yield new Cancelled(appointment.id(), view);
      }
    };
  }

  /**
   * What the cancel transaction did.
   *
   * @param appointmentId the appointment it deleted
   * @param view the appointment as the response shows it
   */
  private record Cancelled(UUID appointmentId, AppointmentView view) {}
}
