package za.co.fynbos.branchbook.shared.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;

/**
 * FR-30: meters reach Amazon CloudWatch only where {@code BRANCHBOOK_METRICS_CLOUDWATCH_ENABLED} is
 * true. Off, the default, no AWS client is built, so nothing looks for credentials. On, the
 * included meters are sent to the configured namespace with each tag as a dimension, and every
 * other meter stays out. The client here is a stub that records what would be sent, so nothing
 * reaches AWS.
 */
class CloudWatchMetricsConfigurationTest {

  /**
   * The configuration alone, with the developer's environment replaced by the one given, so a
   * variable exported in their shell cannot change what the tests prove.
   */
  private static ApplicationContextRunner withEnvironment(Map<String, Object> environment) {
    return new ApplicationContextRunner()
        .withUserConfiguration(CloudWatchMetricsConfiguration.class)
        .withInitializer(
            started ->
                started
                    .getEnvironment()
                    .getPropertySources()
                    .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            environment)));
  }

  /** Off by default: neither the registry nor any AWS client exists, so no credentials are read. */
  @Test
  void offByDefaultNothingNeedsAwsCredentials() {
    withEnvironment(Map.of())
        .run(
            started -> {
              assertThat(started).hasNotFailed();
              assertThat(started).doesNotHaveBean(CloudWatchMeterRegistry.class);
              assertThat(started).doesNotHaveBean(CloudWatchAsyncClient.class);
            });
  }

  @Test
  void falseIsOffToo() {
    withEnvironment(Map.of("BRANCHBOOK_METRICS_CLOUDWATCH_ENABLED", "false"))
        .run(started -> assertThat(started).doesNotHaveBean(CloudWatchAsyncClient.class));
  }

  /**
   * On, the namespace comes from its variable; the outbox gauges and the circuit breaker's state
   * are sent with their tags as dimensions, under names Micrometer gives them; a JVM meter is not
   * sent at all.
   */
  @Test
  void onItSendsTheIncludedMetersToTheNamespaceAndNothingElse() {
    ConcurrentLinkedQueue<PutMetricDataRequest> sent = new ConcurrentLinkedQueue<>();
    CloudWatchAsyncClient client = mock(CloudWatchAsyncClient.class);
    when(client.putMetricData(any(PutMetricDataRequest.class)))
        .thenAnswer(
            call -> {
              sent.add(call.getArgument(0));
              return CompletableFuture.completedFuture(PutMetricDataResponse.builder().build());
            });

    withEnvironment(
            Map.of(
                "BRANCHBOOK_METRICS_CLOUDWATCH_ENABLED", "true",
                "BRANCHBOOK_METRICS_CLOUDWATCH_NAMESPACE", "BranchBook/staging"))
        .withBean(CloudWatchAsyncClient.class, () -> client)
        .run(
            started -> {
              CloudWatchMetricsProperties settings =
                  started.getBean(CloudWatchMetricsProperties.class);
              assertThat(settings.step()).isEqualTo(Duration.ofMinutes(1));
              MeterRegistry registry = started.getBean(CloudWatchMeterRegistry.class);
              Gauge.builder("outbox.pending", () -> 3).register(registry);
              Gauge.builder("outbox.oldest.pending.age", () -> 42)
                  .baseUnit("seconds")
                  .register(registry);
              Gauge.builder("resilience4j.circuitbreaker.state", () -> 1)
                  .tags(Tags.of("name", "sms", "state", "open"))
                  .register(registry);
              Gauge.builder("jvm.memory.used", () -> 1).register(registry);
              // Closing the context sends once more, so what would reach CloudWatch is recorded.
            });

    assertThat(sent)
        .isNotEmpty()
        .allMatch(request -> request.namespace().equals("BranchBook/staging"));
    Map<String, MetricDatum> data =
        sent.stream()
            .flatMap(request -> request.metricData().stream())
            .collect(Collectors.toMap(MetricDatum::metricName, datum -> datum, (a, b) -> b));
    assertThat(data)
        .containsOnlyKeys(
            "outbox.pending.value",
            "outbox.oldest.pending.age.value",
            "resilience4j.circuitbreaker.state.value");
    assertThat(data.get("outbox.pending.value").value()).isEqualTo(3.0);
    assertThat(data.get("outbox.pending.value").dimensions()).isEmpty();
    assertThat(data.get("resilience4j.circuitbreaker.state.value").dimensions())
        .extracting(Dimension::name, Dimension::value)
        .containsExactlyInAnyOrder(tuple("name", "sms"), tuple("state", "open"));
  }

  @Test
  void theIncludedPrefixesComeFromTheirVariable() {
    withEnvironment(
            Map.of(
                "BRANCHBOOK_METRICS_CLOUDWATCH_ENABLED", "true",
                "BRANCHBOOK_METRICS_CLOUDWATCH_INCLUDE", "outbox.,jvm."))
        .withBean(CloudWatchAsyncClient.class, () -> mock(CloudWatchAsyncClient.class))
        .run(
            started ->
                assertThat(started.getBean(CloudWatchMetricsProperties.class).include())
                    .isEqualTo(List.of("outbox.", "jvm.")));
  }
}
