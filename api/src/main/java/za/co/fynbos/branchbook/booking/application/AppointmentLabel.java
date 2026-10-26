package za.co.fynbos.branchbook.booking.application;

import java.time.ZoneId;
import java.util.Objects;

/**
 * What another module needs to name an appointment beside one of its messages: the reference the
 * customer quotes, and the branch's zone for its times.
 *
 * @param reference the reference in its stored form, for example {@code APT-7K3M-9Q2X}
 * @param zone the branch's time zone
 */
public record AppointmentLabel(String reference, ZoneId zone) {

  /** Rejects a missing value. */
  public AppointmentLabel {
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(zone, "zone");
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
