package za.co.fynbos.branchbook.catalogue.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.co.fynbos.branchbook.catalogue.domain.BookingWindow;
import za.co.fynbos.branchbook.catalogue.domain.PublicHolidayCalendar;
import za.co.fynbos.branchbook.shared.config.BookingProperties;

/**
 * Warns before bookings open on dates the public-holiday data does not cover (BR-3).
 *
 * <p>Holidays are seeded a year at a time. Past the last seeded year every date looks like an
 * ordinary trading day, so a branch could take appointments on a holiday. The check compares the
 * last bookable date of any branch with the end of the holiday data, at start-up and every morning:
 *
 * <ul>
 *   <li>From {@link #WARNING_MARGIN} before the window reaches the end of the data, it warns how
 *       many days are left, so the next year's holidays can be added in time.
 *   <li>Once the window has passed the end, it warns that branches can be booked on uncovered
 *       dates.
 * </ul>
 *
 * <p>The fix in both cases is a migration with the next year's holidays.
 */
@Component
class HolidayCoverageCheck {

  /** How long before the booking window reaches the end of the holiday data the warning starts. */
  static final Period WARNING_MARGIN = Period.ofDays(60);

  private static final Logger log = LoggerFactory.getLogger(HolidayCoverageCheck.class);

  private final CatalogueReadPort catalogue;
  private final Clock clock;
  private final BookingProperties booking;

  HolidayCoverageCheck(CatalogueReadPort catalogue, Clock clock, BookingProperties booking) {
    this.catalogue = catalogue;
    this.clock = clock;
    this.booking = booking;
  }

  /**
   * Runs at start-up and, by default, at 06:00 every day in Johannesburg, before the branches open
   * ({@code branchbook.jobs.holiday-check}).
   */
  @EventListener(ApplicationReadyEvent.class)
  @Scheduled(
      cron = "${branchbook.jobs.holiday-check.cron}",
      zone = "${branchbook.jobs.holiday-check.zone}")
  void warnIfBookingsOutrunTheHolidays() {
    LocalDate coveredThrough =
        new PublicHolidayCalendar(catalogue.publicHolidays()).coveredThrough();
    lastBookableDate()
        .ifPresent(
            lastBookable -> {
              if (lastBookable.isAfter(coveredThrough)) {
                log.warn(
                    "Public holidays are known up to {} but bookings open up to {}: branches can"
                        + " now be booked on holidays after {}. Add the next year's holidays in a"
                        + " migration.",
                    coveredThrough,
                    lastBookable,
                    coveredThrough);
              } else if (lastBookable.plus(WARNING_MARGIN).isAfter(coveredThrough)) {
                log.warn(
                    "Public holidays are known up to {}, and the booking window passes that date"
                        + " in {} days. Add the next year's holidays in a migration before then.",
                    coveredThrough,
                    ChronoUnit.DAYS.between(lastBookable, coveredThrough) + 1);
              }
            });
  }

  /** The latest date any active branch can be booked for, in that branch's time zone. */
  Optional<LocalDate> lastBookableDate() {
    return catalogue.activeBranches().stream()
        .map(
            branch ->
                BookingWindow.startingOn(
                        LocalDate.now(clock.withZone(branch.timezone())), booking.horizon())
                    .lastDate())
        .max(Comparator.naturalOrder());
  }
}
