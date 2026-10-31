package za.co.fynbos.branchbook.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * BR-17: the dispatcher's tuning is read from application.yml, where each setting names the
 * environment variable that overrides it, and its defaults are the dispatcher as it was: twenty
 * messages a tick, five attempts, waits of up to 2, 4, 8 and 16 seconds, and a closed circuit
 * breaker. A value that would break delivery stops start-up.
 */
class DispatcherPropertiesTest {

  @Test
  void theDefaultsAreTheDispatcherAsItWas() {
    withEnvironment(Map.of())
        .run(
            started -> {
              DispatcherProperties settings = started.getBean(DispatcherProperties.class);
              assertThat(settings.batchSize()).isEqualTo(20);
              assertThat(settings.maxAttempts()).isEqualTo(5);
              assertThat(settings.backoff())
                  .isEqualTo(
                      new DispatcherProperties.Backoff(
                          Duration.ofSeconds(2), 2, Duration.ofSeconds(16), 0.2));
              assertThat(settings.circuitBreaker())
                  .isEqualTo(
                      new DispatcherProperties.CircuitBreaker(
                          true, 50, 100, Duration.ofSeconds(8), 10, 5, Duration.ofSeconds(30), 2));
            });
  }

  /** The same defaults hold where application.yml is not read, as in a slice test. */
  @Test
  void theRecordDeclaresTheSameDefaultsAsApplicationYml() {
    new ApplicationContextRunner()
        .withUserConfiguration(Settings.class)
        .withPropertyValues(
            "branchbook.dispatcher.interval=PT2S", "branchbook.dispatcher.simulated-latency=PT0S")
        .run(
            bare ->
                withEnvironment(Map.of())
                    .run(
                        fromYaml ->
                            assertThat(bare.getBean(DispatcherProperties.class))
                                .usingRecursiveComparison()
                                .ignoringFields("enabled", "simulatedLatency")
                                .isEqualTo(fromYaml.getBean(DispatcherProperties.class))));
  }

  @Test
  void eachSettingIsOverriddenByItsNamedEnvironmentVariable() {
    withEnvironment(
            Map.ofEntries(
                Map.entry("BRANCHBOOK_DISPATCHER_BATCH_SIZE", "7"),
                Map.entry("BRANCHBOOK_DISPATCHER_MAX_ATTEMPTS", "3"),
                Map.entry("BRANCHBOOK_DISPATCHER_BACKOFF_BASE", "PT1S"),
                Map.entry("BRANCHBOOK_DISPATCHER_BACKOFF_MULTIPLIER", "3"),
                Map.entry("BRANCHBOOK_DISPATCHER_BACKOFF_CAP", "PT20S"),
                Map.entry("BRANCHBOOK_DISPATCHER_BACKOFF_JITTER", "0"),
                Map.entry("BRANCHBOOK_DISPATCHER_CIRCUIT_BREAKER_ENABLED", "false"),
                Map.entry("BRANCHBOOK_DISPATCHER_CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD", "75"),
                Map.entry("BRANCHBOOK_DISPATCHER_CIRCUIT_BREAKER_SLOW_CALL_RATE_THRESHOLD", "90"),
                Map.entry("BRANCHBOOK_DISPATCHER_CIRCUIT_BREAKER_SLOW_CALL_DURATION", "PT3S"),
                Map.entry("BRANCHBOOK_DISPATCHER_CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE", "20"),
                Map.entry("BRANCHBOOK_DISPATCHER_CIRCUIT_BREAKER_MINIMUM_NUMBER_OF_CALLS", "8"),
                Map.entry("BRANCHBOOK_DISPATCHER_CIRCUIT_BREAKER_WAIT_IN_OPEN_STATE", "PT1M"),
                Map.entry(
                    "BRANCHBOOK_DISPATCHER_CIRCUIT_BREAKER_PERMITTED_CALLS_IN_HALF_OPEN_STATE",
                    "4")))
        .run(
            started -> {
              DispatcherProperties settings = started.getBean(DispatcherProperties.class);
              assertThat(settings.batchSize()).isEqualTo(7);
              assertThat(settings.maxAttempts()).isEqualTo(3);
              assertThat(settings.backoff())
                  .isEqualTo(
                      new DispatcherProperties.Backoff(
                          Duration.ofSeconds(1), 3, Duration.ofSeconds(20), 0));
              assertThat(settings.circuitBreaker())
                  .isEqualTo(
                      new DispatcherProperties.CircuitBreaker(
                          false, 75, 90, Duration.ofSeconds(3), 20, 8, Duration.ofMinutes(1), 4));
            });
  }

  /** More than five attempts would break notification_messages' check, so start-up stops. */
  @Test
  void moreThanFiveAttemptsStopsStartUp() {
    withEnvironment(Map.of("BRANCHBOOK_DISPATCHER_MAX_ATTEMPTS", "6"))
        .run(started -> assertThat(started).hasFailed());
  }

  /** A jitter of 1 could make a wait zero, retrying a refused message at once. */
  @Test
  void aJitterOfOneOrMoreStopsStartUp() {
    withEnvironment(Map.of("BRANCHBOOK_DISPATCHER_BACKOFF_JITTER", "1"))
        .run(started -> assertThat(started).hasFailed());
  }

  @Test
  void aCapBelowTheBaseStopsStartUp() {
    withEnvironment(
            Map.of(
                "BRANCHBOOK_DISPATCHER_BACKOFF_BASE", "PT10S",
                "BRANCHBOOK_DISPATCHER_BACKOFF_CAP", "PT5S"))
        .run(started -> assertThat(started).hasFailed());
  }

  @Test
  void aZeroWaitInTheOpenStateStopsStartUp() {
    withEnvironment(Map.of("BRANCHBOOK_DISPATCHER_CIRCUIT_BREAKER_WAIT_IN_OPEN_STATE", "PT0S"))
        .run(started -> assertThat(started).hasFailed());
  }

  /**
   * application.yml with the developer's environment replaced by the one given, so a variable
   * exported in their shell cannot change what the tests prove.
   */
  private static ApplicationContextRunner withEnvironment(Map<String, Object> environment) {
    return new ApplicationContextRunner()
        .withInitializer(
            started ->
                started
                    .getEnvironment()
                    .getPropertySources()
                    .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            environment)))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Settings.class);
  }

  /** Binds the dispatcher's settings alone. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(DispatcherProperties.class)
  static class Settings {}
}
