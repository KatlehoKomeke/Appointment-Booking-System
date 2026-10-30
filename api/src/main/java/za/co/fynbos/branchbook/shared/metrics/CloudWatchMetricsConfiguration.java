package za.co.fynbos.branchbook.shared.metrics;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.config.MeterFilter;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClientBuilder;

/**
 * Exports chosen meters to Amazon CloudWatch where {@code branchbook.metrics.cloudwatch.enabled} is
 * true ({@code BRANCHBOOK_METRICS_CLOUDWATCH_ENABLED}), so alarms can watch the outbox and the SMS
 * circuit breaker. Spring Boot 4.1 has no auto-configuration for Micrometer's CloudWatch registry;
 * this is the small one it would have been.
 *
 * <p>Switched off, the default, none of it exists: no AWS client is built and no credentials are
 * looked for. Switched on, the client's credentials come from the SDK's default chain, which on
 * Amazon ECS is the task role, so no key is ever configured, and none is read until the first
 * export. Only meters whose names start with one of {@code include} are exported; Prometheus still
 * serves every meter. Micrometer names each metric after its meter, with dots, and turns each tag
 * into a dimension: the gauge {@code outbox.pending} arrives as the metric {@code
 * outbox.pending.value} in the configured namespace.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "branchbook.metrics.cloudwatch.enabled", havingValue = "true")
@EnableConfigurationProperties(CloudWatchMetricsProperties.class)
class CloudWatchMetricsConfiguration {

  private static final Logger log = LoggerFactory.getLogger(CloudWatchMetricsConfiguration.class);

  /**
   * The CloudWatch client; the tests supply their own.
   *
   * @param settings the region, if one is set
   * @return a client that reads no credentials until its first call
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  CloudWatchAsyncClient cloudWatchAsyncClient(CloudWatchMetricsProperties settings) {
    CloudWatchAsyncClientBuilder builder = CloudWatchAsyncClient.builder();
    if (hasText(settings.region())) {
      builder.region(Region.of(settings.region().strip()));
    }
    return builder.build();
  }

  /**
   * The registry, which Spring Boot adds to the application's composite registry beside
   * Prometheus's. It sends every {@code step} and once more when the application stops.
   *
   * @param settings namespace, step and the names to export
   * @param client where to send
   * @return the registry, filtered to the included names
   */
  @Bean
  CloudWatchMeterRegistry cloudWatchMeterRegistry(
      CloudWatchMetricsProperties settings, CloudWatchAsyncClient client) {
    CloudWatchConfig config = new Settings(settings.namespace(), settings.step());
    CloudWatchMeterRegistry registry = new CloudWatchMeterRegistry(config, Clock.SYSTEM, client);
    List<String> include = List.copyOf(settings.include());
    registry
        .config()
        .meterFilter(
            MeterFilter.denyUnless(id -> include.stream().anyMatch(id.getName()::startsWith)));
    log.info(
        "Metrics: exported to CloudWatch namespace {} every {}, for meters named {}.",
        settings.namespace(),
        settings.step(),
        include);
    return registry;
  }

  private static boolean hasText(@Nullable String value) {
    return value != null && !value.isBlank();
  }

  /** Micrometer's CloudWatch settings, from ours rather than from its own property names. */
  private record Settings(String namespace, Duration step) implements CloudWatchConfig {

    @Override
    public @Nullable String get(String key) {
      return null;
    }

    @Override
    public String prefix() {
      return "branchbook.metrics.cloudwatch";
    }
  }
}
