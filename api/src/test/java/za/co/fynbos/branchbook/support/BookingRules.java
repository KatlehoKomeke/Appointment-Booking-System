package za.co.fynbos.branchbook.support;

import java.time.Duration;
import java.time.Period;
import za.co.fynbos.branchbook.booking.domain.DemoNumberBlock;
import za.co.fynbos.branchbook.shared.config.BookingProperties;
import za.co.fynbos.branchbook.shared.config.BookingProperties.DemoNumbers;

/**
 * The booking settings application.yml defaults to, for unit tests that build a handler without
 * Spring. Kept equal to the file by {@code ConfigurationReferenceTest}, so a unit test never proves
 * a rule under values the application does not run with.
 */
public final class BookingRules {

  /** The published demo block, 082 555 0000 to 082 555 0199. */
  public static final DemoNumberBlock DEMO_NUMBERS =
      DemoNumberBlock.of("+27825550000", "+27825550199");

  private BookingRules() {}

  /**
   * The defaults: a two-hour lead time, thirty bookable days, five wrong lookups an hour, and the
   * rest as application.yml has them.
   *
   * @return the settings, with only the published demo numbers off
   */
  public static BookingProperties defaults() {
    return new BookingProperties(
        Duration.ofHours(2),
        Period.ofDays(30),
        5,
        Duration.ofHours(1),
        Duration.ofSeconds(30),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofHours(24),
        3,
        false,
        new DemoNumbers("+27825550000", "+27825550199"));
  }
}
