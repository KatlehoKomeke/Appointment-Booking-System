package za.co.fynbos.branchbook.shared.metrics;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Whether and how chosen meters are exported to Amazon CloudWatch, under {@code
 * branchbook.metrics.cloudwatch}. application.yml names each setting's environment variable, {@code
 * BRANCHBOOK_METRICS_CLOUDWATCH_ENABLED}, {@code _NAMESPACE}, {@code _STEP}, {@code _REGION} and
 * {@code _INCLUDE}, and docs/configuration.md lists them; the defaults here repeat its own, so a
 * context built without application.yml, as in the unit tests, behaves the same.
 *
 * <p>Off by default, as locally and in the tests, so nothing builds an AWS client or looks for
 * credentials unless a deployment asks for it. Spring Boot 4.1 has no auto-configuration for this
 * registry, so {@link CloudWatchMetricsConfiguration} builds it.
 *
 * @param enabled whether the meters are exported
 * @param namespace the CloudWatch namespace the metrics are put in, such as {@code
 *     BranchBook/production}, so each environment's alarms read their own
 * @param step how often the meters are sent; CloudWatch's standard resolution is a minute
 * @param region the AWS region to send to; blank leaves it to the SDK's default region chain, which
 *     on Amazon ECS is the task's own region
 * @param include the meter-name prefixes that are exported; every other meter stays out, so the
 *     JVM's and the web server's many series cost nothing in CloudWatch
 */
@Validated
@ConfigurationProperties("branchbook.metrics.cloudwatch")
public record CloudWatchMetricsProperties(
    @DefaultValue("false") boolean enabled,
    @NotBlank @DefaultValue("BranchBook") String namespace,
    @NotNull @DefaultValue("PT1M") Duration step,
    @Nullable String region,
    @NotEmpty @DefaultValue({"outbox.", "resilience4j.circuitbreaker.", "appointments.booked"})
        List<String> include) {}
