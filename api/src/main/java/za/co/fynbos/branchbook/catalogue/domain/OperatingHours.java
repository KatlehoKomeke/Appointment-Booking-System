package za.co.fynbos.branchbook.catalogue.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;

/**
 * The hours a branch trades on one day of the week, as wall-clock times in the branch's time zone.
 * A day without operating hours is a day the branch is closed.
 *
 * @param weekday the day of the week
 * @param opensAt the opening time
 * @param closesAt the closing time, after the opening time
 */
public record OperatingHours(DayOfWeek weekday, LocalTime opensAt, LocalTime closesAt) {

  /** Rejects hours that close before they open, as the database does. */
  public OperatingHours {
    Objects.requireNonNull(weekday, "weekday");
    Objects.requireNonNull(opensAt, "opensAt");
    Objects.requireNonNull(closesAt, "closesAt");
    if (!opensAt.isBefore(closesAt)) {
      throw new IllegalArgumentException("A branch must open before it closes");
    }
  }
}
