package za.co.fynbos.branchbook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The retention settings refuse a max-age that is missing, not positive, or shorter than the 24
 * hours an {@code Idempotency-Key} is honoured for, and that refusal stops start-up. No database is
 * needed: the settings are bound in a context of their own.
 */
class RetentionPropertiesTest {

  private final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(RetentionSettings.class);

  @Test
  void acceptsFortyEightHoursAndExactlyTheFloor() {
    assertThat(new RetentionProperties(true, Duration.ofHours(48)).maxAge())
        .isEqualTo(Duration.ofHours(48));
    assertThat(new RetentionProperties(true, Duration.ofHours(24)).maxAge())
        .isEqualTo(RetentionProperties.MINIMUM_MAX_AGE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"PT23H59M59S", "PT1H", "PT0S", "-PT48H"})
  void refusesAMaxAgeShorterThanTheFloorZeroOrNegative(String maxAge) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new RetentionProperties(false, Duration.parse(maxAge)))
        .withMessageContaining("at least PT24H");
  }

  @Test
  void refusesAMissingMaxAge() {
    assertThatIllegalArgumentException().isThrownBy(() -> new RetentionProperties(false, null));
  }

  @Test
  void bindsFromTheSettingsAndIsOffUnlessSwitchedOn() {
    context
        .withPropertyValues("branchbook.retention.max-age=PT48H")
        .run(
            started -> {
              RetentionProperties bound = started.getBean(RetentionProperties.class);
              assertThat(bound.enabled()).isFalse();
              assertThat(bound.maxAge()).isEqualTo(Duration.ofHours(48));
            });
  }

  /** A value below the floor stops start-up, even with the purge switched off. */
  @ParameterizedTest
  @ValueSource(strings = {"PT1H", "48", "PT0S"})
  void aMaxAgeBelowTheFloorStopsStartUp(String maxAge) {
    context
        .withPropertyValues(
            "branchbook.retention.enabled=false", "branchbook.retention.max-age=" + maxAge)
        .run(
            started ->
                assertThat(started)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least PT24H"));
  }

  /** Binds the retention settings alone. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(RetentionProperties.class)
  static class RetentionSettings {}
}
