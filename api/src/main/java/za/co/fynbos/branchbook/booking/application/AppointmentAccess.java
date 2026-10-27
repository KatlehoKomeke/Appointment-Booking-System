package za.co.fynbos.branchbook.booking.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
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
 * the digits have been compared: {@link #begin} counts it as in flight, {@link #load} compares, and
 * {@link #end} turns it into a failure if the digits were wrong or releases it if they were right.
 * The counts are taken before and after the caller's transaction, never inside it: each is a short
 * transaction of the limiter's own, so a wrong cancel's failure is kept when the cancel rolls back,
 * and a read-only lookup never has to write. A read that fails is the database's fault, not the
 * caller's guess, so it is released too. The digits are compared in constant time, so the response
 * time does not leak how many of them matched.
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
   * Counts a try of a reference as in flight, in a transaction of the limiter's own, before the
   * caller's begins.
   *
   * @param reference the reference, already parsed
   * @param now the current instant, which the limiter's window is measured on
   * @return the counted try, to pass to {@link #end} once the caller's transaction has ended
   * @throws LookupRateLimitedException if the reference has had too many wrong tries
   */
  LookupAttemptLimiter.Attempt begin(AppointmentReference reference, Instant now) {
    return limiter.begin(reference, now);
  }

  /**
   * Loads the appointment the caller has proved, inside the caller's transaction. Counts nothing:
   * the caller settles the try it began once its transaction has ended.
   *
   * @param reference the reference, already parsed
   * @param mobileLast4 the last four digits of the mobile number, already checked to be digits
   * @return the appointment, loaded through JPA without a lock
   * @throws AppointmentNotFoundException if the reference and the digits match no appointment
   */
  Appointment load(AppointmentReference reference, String mobileLast4) {
    return appointments
        .findByReference(reference)
        .filter(appointment -> sameDigits(appointment.mobile().last4(), mobileLast4))
        .orElseThrow(AppointmentNotFoundException::new);
  }

  /**
   * Settles a counted try once the caller's transaction has ended, in a transaction of the
   * limiter's own. It never throws, so the caller's answer, already decided, stands.
   *
   * @param attempt what {@link #begin} returned
   * @param wrongDigits whether the try was a wrong guess: the reference and the digits matched no
   *     appointment, or the appointment was gone by the time the caller could act on it, which must
   *     be counted exactly as an unknown reference is (BR-13, BR-14); {@code false} for a try that
   *     failed or was refused for any other reason, such as the database or a cancel too late
   * @param now the current instant, which decides whether the try's lease has run out
   */
  void end(LookupAttemptLimiter.Attempt attempt, boolean wrongDigits, Instant now) {
    limiter.end(attempt, wrongDigits, now);
  }

  /** Compares in constant time, so the response time does not leak how many digits matched. */
  private static boolean sameDigits(String expected, String given) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII), given.getBytes(StandardCharsets.US_ASCII));
  }
}
