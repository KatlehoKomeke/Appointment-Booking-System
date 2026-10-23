package za.co.fynbos.branchbook.catalogue.domain;

import java.time.LocalDate;
import java.time.Period;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The dates a customer may book: from today up to, but not including, today plus the horizon. A
 * thirty-day horizon therefore gives exactly thirty bookable dates (BR-5).
 *
 * <p>The server computes the window in the branch's time zone and publishes it, so the web app
 * never derives "today" from the device's clock, which may be wrong or in another zone.
 *
 * @param firstDate the first bookable date: today in the branch's time zone
 * @param lastDate the last bookable date
 */
public record BookingWindow(LocalDate firstDate, LocalDate lastDate) {

  /** Rejects a window that ends before it starts. */
  public BookingWindow {
    Objects.requireNonNull(firstDate, "firstDate");
    Objects.requireNonNull(lastDate, "lastDate");
    if (lastDate.isBefore(firstDate)) {
      throw new IllegalArgumentException("A booking window cannot end before it starts");
    }
  }

  /**
   * Creates the window that starts today.
   *
   * @param today today's date in the branch's time zone
   * @param horizon the number of bookable days, at least one
   * @return the window {@code [today, today + horizon)}
   */
  public static BookingWindow startingOn(LocalDate today, Period horizon) {
    return new BookingWindow(today, today.plus(horizon).minusDays(1));
  }

  /**
   * Tells whether a date is bookable.
   *
   * @param date the date to check
   * @return {@code true} if the date lies in the window, ends included
   */
  public boolean contains(LocalDate date) {
    return !date.isBefore(firstDate) && !date.isAfter(lastDate);
  }

  /**
   * Lists every date in the window, in order.
   *
   * @return the dates from the first to the last, both included
   */
  public Stream<LocalDate> dates() {
    return firstDate.datesUntil(lastDate.plusDays(1));
  }
}
