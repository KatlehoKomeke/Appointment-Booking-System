package za.co.fynbos.branchbook.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application's clock with a {@link MutableClock}. Being {@code @Primary}, it is the
 * clock every bean receives; tests inject it to set the time.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

  /**
   * The instant every integration test starts from unless it sets another: Monday 26 October 2026,
   * 08:00 in Johannesburg, an ordinary trading day.
   */
  public static final Instant DEFAULT_INSTANT = Instant.parse("2026-10-26T06:00:00Z");

  @Bean
  @Primary
  MutableClock mutableClock() {
    return new MutableClock(DEFAULT_INSTANT);
  }
}
