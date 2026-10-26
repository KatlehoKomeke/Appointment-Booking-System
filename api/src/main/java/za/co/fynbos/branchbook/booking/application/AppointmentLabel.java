package za.co.fynbos.branchbook.booking.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * What another module needs to know about an appointment beside one of its messages: the reference
 * the customer quotes and the branch's zone for its times, and whether the appointment is cancelled
 * or has started, which decides whether its confirmation may still be sent (BR-24).
 *
 * @param reference the reference in its stored form, for example {@code APT-7K3M-9Q2X}
 * @param zone the branch's time zone
 * @param startsAt when the appointment starts
 * @param cancelled whether the appointment has been cancelled
 */
public record AppointmentLabel(String reference, ZoneId zone, Instant startsAt, boolean cancelled) {

  /** Rejects a missing value. */
  public AppointmentLabel {
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(zone, "zone");
    Objects.requireNonNull(startsAt, "startsAt");
  }

  /**
   * Hides the reference, which is half of what opens an appointment (BR-19).
   *
   * @return the zone and a fixed text
   */
  @Override
  public String toString() {
    return "AppointmentLabel[" + zone + ", redacted]";
  }
}
