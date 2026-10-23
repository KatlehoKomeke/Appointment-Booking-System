package za.co.fynbos.branchbook.shared.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the one {@link Clock} that all application code reads the time from.
 *
 * <p>Nothing calls {@code Instant.now()}, {@code LocalDate.now()} without a clock, or the
 * database's {@code now()}: code asks this clock and passes the instant on. Tests replace it with a
 * clock they can set, so rules such as the two-hour lead time and the thirty-day window are tested
 * at fixed, meaningful moments (docs/architecture.md, section 8).
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

  /**
   * The system clock in UTC. Code that needs a local date converts explicitly, through the branch's
   * time zone.
   *
   * @return the clock the application reads the current instant from
   */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
