package za.co.fynbos.branchbook.booking.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import za.co.fynbos.branchbook.booking.domain.Appointment;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;

/**
 * Lets in only the caller who proves an appointment with its reference and the last four digits of
 * the mobile number that booked it, the customer's only credential (BR-13, ADR-0004). Opening and
 * cancelling both come through here, so a wrong cancel is counted exactly as a wrong lookup is, and
 * five of either block the reference. A cancel deletes the appointment, so its reference is unknown
 * from then on, and opening or cancelling it again is counted as any unknown one is.
 *
 * <p>Each try is counted by the {@link LookupAttemptLimiter} from before the database is read until
 * the digits have been compared: a wrong one becomes a failure and a right one is released. A read
 * that fails is the database's fault, not the caller's guess, so it is released too. The digits are
 * compared in constant time, so the response time does not leak how many of them matched.
 */
@Component
class AppointmentAccess {

  private final AppointmentRepository appointments;
  private final LookupAttemptLimiter limiter;

  AppointmentAccess(AppointmentRepository appointments, LookupAttemptLimiter limiter) {
    this.appointments = appointments;
    this.limiter = limiter;
  }

  /**
   * Loads the appointment the caller has proved, inside the caller's transaction.
   *
   * @param reference the reference, already parsed
   * @param mobileLast4 the last four digits of the mobile number, already checked to be digits
   * @param now the current instant, which the limiter's window is measured on
   * @return the appointment, loaded through JPA without a lock
   * @throws AppointmentNotFoundException if the reference and the digits match no appointment
   * @throws LookupRateLimitedException if the reference has had too many wrong tries
   */
  Appointment open(AppointmentReference reference, String mobileLast4, Instant now) {
    LookupAttemptLimiter.Attempt attempt = limiter.begin(reference, now);
    Optional<Appointment> match = Optional.empty();
    boolean compared = false;
    try {
      match =
          appointments
              .findByReference(reference)
              .filter(appointment -> sameDigits(appointment.mobile().last4(), mobileLast4));
      compared = true;
    } finally {
      // Counted before the read, so tries that arrive together cannot all slip past the limit.
      limiter.end(attempt, compared && match.isEmpty());
    }
    return match.orElseThrow(AppointmentNotFoundException::new);
  }

  /**
   * Counts a try whose appointment was gone by the time the caller could act on it, exactly as a
   * try of an unknown reference is counted: a concurrent cancel or the retention purge deleted it
   * after {@link #open} matched its digits. A deleted reference must be indistinguishable from one
   * that never existed, in the answer and in the count alike (BR-13, BR-14).
   *
   * @param reference the reference, already parsed
   * @param now the current instant, which the limiter's window is measured on
   * @throws LookupRateLimitedException if the reference has had too many wrong tries, as an unknown
   *     one would be answered
   */
  void countAsUnknown(AppointmentReference reference, Instant now) {
    limiter.end(limiter.begin(reference, now), true);
  }

  /** Compares in constant time, so the response time does not leak how many digits matched. */
  private static boolean sameDigits(String expected, String given) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII), given.getBytes(StandardCharsets.US_ASCII));
  }
}
