package za.co.fynbos.branchbook.booking.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.booking.domain.Appointment;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;

/**
 * Opens an appointment with its reference and the last four digits of the mobile number that booked
 * it: the customer's only credential, as there are no accounts (BR-13, ADR-0004).
 *
 * <p>Every mismatch, whether the reference is malformed, unknown or right with the wrong digits, is
 * the same {@code 404}, never a {@code 403}, so the answer never confirms that a reference exists.
 * Wrong lookups of a well-formed reference are counted, and the {@link LookupAttemptLimiter} blocks
 * a reference that has had too many.
 */
@Service
public class LookupAppointmentHandler {

  private final AppointmentRepository appointments;
  private final LookupAttemptLimiter limiter;
  private final AppointmentViews views;
  private final Clock clock;

  LookupAppointmentHandler(
      AppointmentRepository appointments,
      LookupAttemptLimiter limiter,
      AppointmentViews views,
      Clock clock) {
    this.appointments = appointments;
    this.limiter = limiter;
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
  @Transactional(readOnly = true)
  public AppointmentView lookup(String reference, String mobileLast4) {
    AppointmentReference parsed =
        AppointmentReference.parse(reference).orElseThrow(AppointmentNotFoundException::new);
    Instant now = clock.instant();
    limiter.checkNotBlocked(parsed, now);
    Optional<Appointment> match =
        appointments
            .findByReference(parsed)
            .filter(appointment -> sameDigits(appointment.mobile().last4(), mobileLast4));
    if (match.isEmpty()) {
      limiter.recordFailure(parsed, now);
      throw new AppointmentNotFoundException();
    }
    return views.of(match.get(), now);
  }

  /** Compares in constant time, so the response time does not leak how many digits matched. */
  private static boolean sameDigits(String expected, String given) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII), given.getBytes(StandardCharsets.US_ASCII));
  }
}
