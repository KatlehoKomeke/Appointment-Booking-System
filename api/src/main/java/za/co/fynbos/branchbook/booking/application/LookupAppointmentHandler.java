package za.co.fynbos.branchbook.booking.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;

/**
 * Opens an appointment with its reference and the last four digits of the mobile number that booked
 * it: the customer's only credential, as there are no accounts (BR-13, ADR-0004).
 *
 * <p>Every mismatch, whether the reference is malformed, unknown or right with the wrong digits, is
 * the same {@code 404}, never a {@code 403}, so the answer never confirms that a reference exists.
 * Wrong lookups of a well-formed reference are counted through {@link AppointmentAccess}, and the
 * {@link LookupAttemptLimiter} blocks a reference that has had too many. The try is counted before
 * the lookup's own read-only transaction and settled after it, each in a short transaction of the
 * limiter's: the read never writes, and a read-only transaction would refuse the count. A read that
 * fails for any reason but a mismatch releases the try rather than counting it.
 */
@Service
public class LookupAppointmentHandler {

  private final TransactionTemplate transaction;
  private final AppointmentAccess access;
  private final AppointmentViews views;
  private final Clock clock;

  LookupAppointmentHandler(
      PlatformTransactionManager transactionManager,
      AppointmentAccess access,
      AppointmentViews views,
      Clock clock) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setReadOnly(true);
    template.setName("LookupAppointmentHandler.lookup");
    this.transaction = template;
    this.access = access;
    this.views = views;
    this.clock = clock;
  }

  /**
   * Opens an appointment.
   *
   * @param reference the reference as the customer typed it; case and hyphens do not matter
   * @param mobileLast4 the last four digits of the mobile number, already checked to be digits
   * @return the appointment
   * @throws AppointmentNotFoundException if the reference and the digits do not match an
   *     appointment
   * @throws LookupRateLimitedException if the reference has had too many wrong lookups
   */
  public AppointmentView lookup(String reference, String mobileLast4) {
    AppointmentReference parsed =
        AppointmentReference.parse(reference).orElseThrow(AppointmentNotFoundException::new);
    Instant now = clock.instant();
    LookupAttemptLimiter.Attempt attempt = access.begin(parsed, now);
    boolean wrong = false;
    try {
      return Objects.requireNonNull(
          transaction.execute(status -> views.of(access.load(parsed, mobileLast4), now)));
    } catch (AppointmentNotFoundException unknown) {
      wrong = true;
      throw unknown;
    } finally {
      access.end(attempt, wrong, now);
    }
  }
}
