package za.co.fynbos.branchbook.booking.application;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;

/**
 * Opens an appointment with its reference and the last four digits of the mobile number that booked
 * it: the customer's only credential, as there are no accounts (BR-13, ADR-0004).
 *
 * <p>Every mismatch, whether the reference is malformed, unknown or right with the wrong digits, is
 * the same {@code 404}, never a {@code 403}, so the answer never confirms that a reference exists.
 * Wrong lookups of a well-formed reference are counted through {@link AppointmentAccess}, and the
 * {@link LookupAttemptLimiter} blocks a reference that has had too many.
 */
@Service
public class LookupAppointmentHandler {

  private final AppointmentAccess access;
  private final AppointmentViews views;
  private final Clock clock;

  LookupAppointmentHandler(AppointmentAccess access, AppointmentViews views, Clock clock) {
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
  @Transactional(readOnly = true)
  public AppointmentView lookup(String reference, String mobileLast4) {
    AppointmentReference parsed =
        AppointmentReference.parse(reference).orElseThrow(AppointmentNotFoundException::new);
    Instant now = clock.instant();
    return views.of(access.open(parsed, mobileLast4, now), now);
  }
}
