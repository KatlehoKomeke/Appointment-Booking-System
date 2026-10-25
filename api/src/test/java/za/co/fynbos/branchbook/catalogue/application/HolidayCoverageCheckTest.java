package za.co.fynbos.branchbook.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import za.co.fynbos.branchbook.catalogue.domain.Address;
import za.co.fynbos.branchbook.catalogue.domain.Branch;
import za.co.fynbos.branchbook.catalogue.domain.Province;
import za.co.fynbos.branchbook.catalogue.domain.PublicHoliday;
import za.co.fynbos.branchbook.catalogue.domain.PublicHoliday.Source;
import za.co.fynbos.branchbook.shared.config.BookingProperties;

/**
 * BR-3: the warning that the booking window is about to reach, or has reached, dates the
 * public-holiday data does not cover. The holidays here end with 2027, so coverage ends on 31
 * December 2027.
 */
class HolidayCoverageCheckTest {

  private static final BookingProperties BOOKING =
      new BookingProperties(Duration.ofHours(2), Period.ofDays(30));

  private final CatalogueReadPort catalogue = mock(CatalogueReadPort.class);

  private final Logger logger = (Logger) LoggerFactory.getLogger(HolidayCoverageCheck.class);

  private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

  @BeforeEach
  void seedTheCatalogue() {
    when(catalogue.activeBranches()).thenReturn(List.of(branch()));
    when(catalogue.publicHolidays())
        .thenReturn(
            List.of(
                new PublicHoliday(LocalDate.of(2027, 1, 1), "New Year's Day", Source.SCHEDULE_1),
                new PublicHoliday(
                    LocalDate.of(2027, 12, 26), "Day of Goodwill", Source.SCHEDULE_1)));
    logged.start();
    logger.addAppender(logged);
  }

  @AfterEach
  void stopListening() {
    logger.detachAppender(logged);
  }

  /** On 3 December the thirtieth bookable date is 1 January 2028, the first uncovered date. */
  @Test
  void warnsOnceTheBookingWindowPassesTheLastSeededYear() {
    check(Instant.parse("2027-12-03T06:00:00Z")).warnIfBookingsOutrunTheHolidays();

    assertThat(logged.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .startsWith(
                      "Public holidays are known up to 2027-12-31 but bookings open up to"
                          + " 2028-01-01");
            });
  }

  /**
   * On 4 October the window ends on 2 November, 59 days before the data does, so in 60 days it will
   * reach 1 January.
   */
  @Test
  void warnsSixtyDaysBeforeTheWindowPassesTheEndOfTheData() {
    check(Instant.parse("2027-10-04T06:00:00Z")).warnIfBookingsOutrunTheHolidays();

    assertThat(logged.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .isEqualTo(
                      "Public holidays are known up to 2027-12-31, and the booking window passes"
                          + " that date in 60 days. Add the next year's holidays in a migration"
                          + " before then.");
            });
  }

  /** On 3 October the window ends on 1 November, sixty days before the data does. */
  @Test
  void staysQuietUntilSixtyDaysBefore() {
    check(Instant.parse("2027-10-03T06:00:00Z")).warnIfBookingsOutrunTheHolidays();

    assertThat(logged.list).isEmpty();
  }

  /** At 22:30 UTC on 2 December it is already 3 December in Johannesburg. */
  @Test
  void countsTheWindowFromTheBranchsDate() {
    HolidayCoverageCheck check = check(Instant.parse("2027-12-02T22:30:00Z"));

    assertThat(check.lastBookableDate()).contains(LocalDate.of(2028, 1, 1));
  }

  @Test
  void staysQuietWithoutActiveBranches() {
    when(catalogue.activeBranches()).thenReturn(List.of());

    check(Instant.parse("2027-12-20T06:00:00Z")).warnIfBookingsOutrunTheHolidays();

    assertThat(logged.list).isEmpty();
  }

  private HolidayCoverageCheck check(Instant now) {
    return new HolidayCoverageCheck(catalogue, Clock.fixed(now, ZoneOffset.UTC), BOOKING);
  }

  private static Branch branch() {
    return new Branch(
        UUID.randomUUID(),
        "TST",
        "Test Branch",
        "Test Branch",
        new Address("1 Test Road", "Testville", "Test City", Province.GAUTENG, "2000"),
        ZoneId.of("Africa/Johannesburg"),
        false);
  }
}
