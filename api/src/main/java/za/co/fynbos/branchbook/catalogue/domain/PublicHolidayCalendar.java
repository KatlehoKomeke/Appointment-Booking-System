package za.co.fynbos.branchbook.catalogue.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.Collection;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Answers whether a date is a public holiday in South Africa, and what it is called.
 *
 * <p>The stored holidays are the dates in law (Schedule 1 and section 2A proclamations). The
 * calendar adds the rule of section 2(1) of the Public Holidays Act 36 of 1994: <em>whenever a
 * public holiday falls on a Sunday, the following Monday is a public holiday</em>. So 9 August
 * 2026, a Sunday, makes Monday 10 August a holiday. A holiday on a Saturday gets no substitute.
 * When the Monday is already a holiday in its own right, it keeps its own name.
 *
 * <p>The calendar knows only the years it was given. {@link #coveredThrough()} says where its
 * knowledge ends, so the application can warn before the booking window passes it (BR-3).
 */
public final class PublicHolidayCalendar {

  private final NavigableMap<LocalDate, String> namesByDate = new TreeMap<>();

  /**
   * Creates a calendar from the holidays in law.
   *
   * @param holidays the stored holidays; observed Mondays must not be among them
   * @throws IllegalArgumentException if no holiday is given, because coverage would be undefined
   */
  public PublicHolidayCalendar(Collection<PublicHoliday> holidays) {
    if (holidays.isEmpty()) {
      throw new IllegalArgumentException("A holiday calendar needs at least one holiday");
    }
    holidays.forEach(holiday -> namesByDate.put(holiday.date(), holiday.name()));
  }

  /**
   * Returns the name of the public holiday on a date, including a Monday observed in place of a
   * Sunday holiday.
   *
   * @param date the date to check
   * @return the holiday's name, such as {@code National Women's Day (observed)}, or empty
   */
  public Optional<String> holidayOn(LocalDate date) {
    String stored = namesByDate.get(date);
    if (stored != null) {
      return Optional.of(stored);
    }
    if (date.getDayOfWeek() == DayOfWeek.MONDAY) {
      String sundayHoliday = namesByDate.get(date.minusDays(1));
      if (sundayHoliday != null) {
        return Optional.of(sundayHoliday + " (observed)");
      }
    }
    return Optional.empty();
  }

  /**
   * Tells whether a date is a public holiday.
   *
   * @param date the date to check
   * @return {@code true} for a holiday in law and for a Monday observed in place of a Sunday one
   */
  public boolean isHoliday(LocalDate date) {
    return holidayOn(date).isPresent();
  }

  /**
   * Returns the last date this calendar can answer for: 31 December of the latest year it holds a
   * holiday for. Dates after it are reported as ordinary days, which may be wrong.
   *
   * @return the last date covered
   */
  public LocalDate coveredThrough() {
    int lastYear = namesByDate.lastKey().getYear();
    return LocalDate.of(lastYear, Month.DECEMBER, 31);
  }
}
