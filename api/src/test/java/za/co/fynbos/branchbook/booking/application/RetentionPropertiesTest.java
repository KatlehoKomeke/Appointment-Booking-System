package za.co.fynbos.branchbook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The retention settings keep a booking until its appointment is over: {@code after-end} is zero by
 * default, may add a delay, and is refused when missing or negative, which stops start-up. No
 * database is needed: the settings are bound in a context of their own.
 */
class RetentionPropertiesTest {

  private final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(RetentionSettings.class);

  @Test
  void acceptsZeroAndADelay() {
    assertThat(new RetentionProperties(true, Duration.ZERO).afterEnd()).isEqualTo(Duration.ZERO);
    assertThat(new RetentionProperties(true, Duration.ofHours(24)).afterEnd())
        .isEqualTo(Duration.ofHours(24));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-PT1S", "-PT48H"})
  void refusesANegativeAfterEnd(String afterEnd) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new RetentionProperties(false, Duration.parse(afterEnd)))
        .withMessageContaining("zero or more");
  }

  @Test
  void refusesAMissingAfterEnd() {
    assertThatIllegalArgumentException().isThrownBy(() -> new RetentionProperties(false, null));
  }

  /** Unset, the purge is off and deletes a booking as soon as its appointment has ended. */
  @Test
  void isOffAndDeletesAtTheEndUnlessSetOtherwise() {
    withEnvironment(Map.of())
        .run(
            started -> {
              RetentionProperties bound = started.getBean(RetentionProperties.class);
              assertThat(bound.enabled()).isFalse();
              assertThat(bound.afterEnd()).isEqualTo(Duration.ZERO);
            });
  }

  /** The names the hosted demo's task definition uses bind to the settings. */
  @Test
  void bindsFromTheEnvironmentNames() {
    withEnvironment(
            Map.of(
                "BRANCHBOOK_RETENTION_ENABLED", "true", "BRANCHBOOK_RETENTION_AFTER_END", "PT2H"))
        .run(
            started -> {
              RetentionProperties bound = started.getBean(RetentionProperties.class);
              assertThat(bound.enabled()).isTrue();
              assertThat(bound.afterEnd()).isEqualTo(Duration.ofHours(2));
            });
  }

  /** A negative value stops start-up, even with the purge switched off. */
  @Test
  void aNegativeAfterEndStopsStartUp() {
    context
        .withPropertyValues(
            "branchbook.retention.enabled=false", "branchbook.retention.after-end=-PT1H")
        .run(
            started ->
                assertThat(started)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("zero or more"));
  }

  /** The runner with the developer's environment replaced by the given variables. */
  private ApplicationContextRunner withEnvironment(Map<String, Object> variables) {
    return context.withInitializer(
        started ->
            started
                .getEnvironment()
                .getPropertySources()
                .replace(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)));
  }

  /** Binds the retention settings alone. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(RetentionProperties.class)
  static class RetentionSettings {}
}
