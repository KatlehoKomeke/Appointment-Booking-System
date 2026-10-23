package za.co.fynbos.branchbook.catalogue.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * When one branch trades: its weekly hours and the public holidays it closes on. Scheduling and
 * booking read a branch's days only through this class, so the rules for closed days live in one
 * place (BR-3).
 *
 * <p>A day is closed when it is a public holiday and the branch does not trade on public holidays,
 * or when the branch has no hours for that day of the week. A public holiday is reported as such
 * even on a day the branch never trades, because the holiday's name tells the customer more.
 */
public final class BranchCalendar {

  private final Branch branch;
  private final Map<DayOfWeek, OperatingHours> hoursByWeekday = new EnumMap<>(DayOfWeek.class);
  private final PublicHolidayCalendar holidays;

  /**
   * Creates the calendar of one branch.
   *
   * @param branch the branch
   * @param hours its operating hours, at most one entry per day of the week
   * @param holidays the national public holidays
   * @throws IllegalArgumentException if two entries are for the same day of the week
   */
  public BranchCalendar(
      Branch branch, Collection<OperatingHours> hours, PublicHolidayCalendar holidays) {
    this.branch = Objects.requireNonNull(branch, "branch");
    this.holidays = Objects.requireNonNull(holidays, "holidays");
    for (OperatingHours day : hours) {
      if (hoursByWeekday.put(day.weekday(), day) != null) {
        throw new IllegalArgumentException("Two sets of hours for " + day.weekday());
      }
    }
  }

  /**
   * Returns the branch this calendar belongs to.
   *
   * @return the branch
   */
  public Branch branch() {
    return branch;
  }

  /**
   * Returns the branch's weekly hours, Monday first.
   *
   * @return one entry per day of the week the branch trades
   */
  public List<OperatingHours> weeklyHours() {
    return List.copyOf(hoursByWeekday.values());
  }

  /**
   * Returns the hours the branch trades on a date.
   *
   * @param date the date, in the branch's time zone
   * @return the hours, or empty if the branch is closed that day
   */
  public Optional<OperatingHours> hoursOn(LocalDate date) {
    if (closure(date).isPresent()) {
      return Optional.empty();
    }
    return Optional.of(hoursByWeekday.get(date.getDayOfWeek()));
  }

  /**
   * Tells why the branch is closed on a date, if it is.
   *
   * @param date the date, in the branch's time zone
   * @return the closure, or empty if the branch trades that day
   */
  public Optional<ClosedDay> closure(LocalDate date) {
    if (!branch.openOnPublicHolidays()) {
      Optional<String> holiday = holidays.holidayOn(date);
      if (holiday.isPresent()) {
        return Optional.of(new ClosedDay(date, ClosedDay.Reason.PUBLIC_HOLIDAY, holiday.get()));
      }
    }
    if (!hoursByWeekday.containsKey(date.getDayOfWeek())) {
      return Optional.of(new ClosedDay(date, ClosedDay.Reason.CLOSED_DAY, null));
    }
    return Optional.empty();
  }

  /**
   * Lists the days in a booking window on which the branch is closed.
   *
   * @param window the dates to check
   * @return the closures, in date order
   */
  public List<ClosedDay> closuresIn(BookingWindow window) {
    return window.dates().map(this::closure).flatMap(Optional::stream).toList();
  }
}
