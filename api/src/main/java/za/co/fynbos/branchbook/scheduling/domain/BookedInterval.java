package za.co.fynbos.branchbook.scheduling.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A seat that an active appointment holds for a while. Scheduling learns about bookings only
 * through these intervals, never by reading the booking module's table.
 *
 * @param seatNo the seat the appointment holds
 * @param startsAt when the appointment starts
 * @param endsAt when it ends
 */
public record BookedInterval(int seatNo, Instant startsAt, Instant endsAt) {

  /** Rejects an interval that ends before it starts. */
  public BookedInterval {
    Objects.requireNonNull(startsAt, "startsAt");
    Objects.requireNonNull(endsAt, "endsAt");
    if (!endsAt.isAfter(startsAt)) {
      throw new IllegalArgumentException("A booked interval must end after it starts");
    }
  }

  /**
   * Tells whether this interval overlaps a period. Both are half-open, so an appointment ending at
   * 09:30 does not overlap one starting at 09:30, as in the database's exclusion constraint.
   *
   * @param start the period's start, included
   * @param end the period's end, excluded
   * @return {@code true} if the two share any instant
   */
  public boolean overlaps(Instant start, Instant end) {
    return startsAt.isBefore(end) && start.isBefore(endsAt);
  }
}
