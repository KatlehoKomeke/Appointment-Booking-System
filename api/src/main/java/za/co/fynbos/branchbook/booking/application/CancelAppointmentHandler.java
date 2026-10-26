package za.co.fynbos.branchbook.booking.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * Cancels an appointment for the customer who proves it with its reference and the last four digits
 * of the mobile number (BR-13, BR-14). It is the only change the API makes to an existing
 * appointment.
 *
 * <p>{@link #cancel} runs these steps in one READ COMMITTED transaction:
 *
 * <ol>
 *   <li>Count the try with the {@link LookupAttemptLimiter}, as a lookup is counted.
 *   <li>Load the appointment by reference through JPA, without a lock.
 *   <li>Compare the digits in constant time; a mismatch is {@code 404} and counts as a failure.
 *   <li>{@link Appointment#cancel}: a repeat changes nothing, and a started appointment is {@code
 *       409 TOO_LATE_TO_CANCEL}.
 *   <li>Flush: {@code UPDATE ... WHERE id = ? AND version = ?}, which frees the seat.
 *   <li>Supersede the confirmation if it is still PENDING or FAILED, and write the cancellation SMS
 *       (BR-16, BR-24).
 *   <li>Render the response from this transaction's own reads: the appointment it holds and the
 *       messages it can see.
 *   <li>Commit.
 * </ol>
 *
 * <p>Two cancels of one appointment at once, for example from two tabs, both load it before either
 * commits. The second one's version check fails at the flush, so it runs once more in a new
 * transaction, finds the appointment cancelled and answers the same {@code 200} without writing
 * anything. Cancel is the only API writer of an existing appointment, so one re-run is always
 * enough; a second conflict would be {@code 503 TRY_AGAIN}. A lock not granted within 2 seconds is
 * also {@code 503}. Every instant comes from the injected clock, read once per attempt and cut to
 * the microsecond the database keeps, so the response shows {@code cancelledAt} as a lookup will.
 * Log lines name the appointment by id, never by reference, name or number.
 *
 * <p>The retention purge may delete the appointment at any moment the cancel does not hold its row
 * (see {@link RetentionPurge}). Nothing is read after the commit, so a purge then cannot fail the
 * response. A purge before the flush leaves the UPDATE nothing to change, which is a version
 * conflict; the re-run then finds no appointment and answers {@code 404 APPOINTMENT_NOT_FOUND}
 * without counting a wrong try, as the digits matched once already. A cancel that waits on the row
 * while the purge holds it ends the same way when the purge commits, or in {@code 503 TRY_AGAIN}
 * when its lock wait times out.
 */
@Service
public class CancelAppointmentHandler {

  private static final Logger log = LoggerFactory.getLogger(CancelAppointmentHandler.class);

  /** How long a caller should wait after a second version conflict. */
  private static final Duration CONFLICT_RETRY_AFTER = Duration.ofSeconds(2);

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
   * Cancels an appointment, or confirms that it already is cancelled.
   *
   * @param reference the reference as the customer typed it; case and hyphens do not matter
   * @param mobileLast4 the last four digits of the mobile number, already checked to be digits
   * @return the appointment as the cancel committed it, as a lookup would show it: cancelled, with
   *     its messages
   * @throws AppointmentNotFoundException if the reference and the digits match no appointment, or
   *     the retention purge deleted it before the cancel could write
   * @throws LookupRateLimitedException if the reference has had too many wrong tries
   * @throws TooLateToCancelException if the appointment has started
   * @throws ProblemException {@code 503 TRY_AGAIN} if the version check fails twice
   */
  public AppointmentView cancel(String reference, String mobileLast4) {
    try {
      AppointmentReference parsed =
          AppointmentReference.parse(reference).orElseThrow(AppointmentNotFoundException::new);
      Outcome outcome;
      try {
        outcome = attempt(parsed, mobileLast4, false);
      } catch (OptimisticLockingFailureException concurrent) {
        log.info("cancel.rerun cause=VersionConflict");
        outcome = rerun(parsed, mobileLast4);
      }
      log.info(
          outcome.changed()
              ? "cancel.cancelled appointmentId={}"
              : "cancel.already_cancelled appointmentId={}",
          outcome.appointmentId());
      return outcome.view();
    } catch (ProblemException refused) {
      log.info("cancel.refused code={}", refused.code());
      throw refused;
    }
  }

  /**
   * The one re-run, which finds the appointment the other cancel committed, or finds that the
   * retention purge deleted it.
   */
  private Outcome rerun(AppointmentReference reference, String mobileLast4) {
    try {
      return attempt(reference, mobileLast4, true);
    } catch (OptimisticLockingFailureException again) {
      throw new ProblemException(
          ProblemCode.TRY_AGAIN,
          "We could not complete your request. Please try again.",
          Map.of(),
          CONFLICT_RETRY_AFTER);
    }
  }

  /**
   * One transaction. {@code proven} is true on the re-run, whose caller matched the digits on the
   * first attempt, so a missing appointment there is a deleted one, not a guess, and is not
   * counted.
   */
  private Outcome attempt(AppointmentReference reference, String mobileLast4, boolean proven) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    return Objects.requireNonNull(
        transaction.execute(status -> cancel(reference, mobileLast4, proven, now)));
  }

  private Outcome cancel(
      AppointmentReference reference, String mobileLast4, boolean proven, Instant now) {
    Appointment appointment =
        proven
            ? access.reopen(reference, mobileLast4)
            : access.open(reference, mobileLast4, now); // 1, 2, 3
    return switch (appointment.cancel(now)) { // 4
      case TOO_LATE -> throw new TooLateToCancelException();
      case ALREADY_CANCELLED ->
          new Outcome(appointment.id(), false, views.of(appointment, now)); // 7
      case CANCELLED -> {
        Cancellation cancellation =
            Cancellation.of(
                appointment,
                catalogue.branch(appointment.branchId()),
                catalogue.service(appointment.serviceId()),
                now);
        appointments.update(appointment); // 5
        outbox.enqueueCancellation(cancellation); // 6
        yield new Outcome(appointment.id(), true, views.of(appointment, now)); // 7
      }
    };
  }

  /**
   * What one transaction did.
   *
   * @param appointmentId the appointment
   * @param changed whether this transaction cancelled it, rather than finding it cancelled
   * @param view the appointment as this transaction leaves it, which the response shows
   */
  private record Outcome(UUID appointmentId, boolean changed, AppointmentView view) {}
}
