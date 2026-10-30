package za.co.fynbos.branchbook.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

/**
 * When the application's own housekeeping jobs run, under {@code branchbook.jobs}. Each job's
 * {@code @Scheduled} annotation reads its timing from these properties through a placeholder; this
 * record checks the same values at start-up, so a zero or negative interval, a malformed cron
 * expression or an unknown zone stops the application with a message that names the setting.
 *
 * @param idempotencyKeyPurge the hourly deletion of expired idempotency keys (BR-12)
 * @param lookupAttemptPurge the hourly deletion of closed lookup windows (BR-13)
 * @param capLedgerPurge the hourly deletion of the SMS cap's day-old ledger rows (BR-17)
 * @param retentionPurge the hourly deletion of appointments that are over, which runs only where
 *     {@code branchbook.retention.enabled} is on
 * @param holidayCheck the daily warning that the public-holiday data is running out (BR-3)
 */
@Validated
@ConfigurationProperties("branchbook.jobs")
public record JobsProperties(
    @NotNull @Valid Every idempotencyKeyPurge,
    @NotNull @Valid Every lookupAttemptPurge,
    @NotNull @Valid Every capLedgerPurge,
    @NotNull @Valid Every retentionPurge,
    @NotNull @Valid Daily holidayCheck) {

  /**
   * A job that runs again a fixed time after each run ends.
   *
   * @param interval how long after one run ends the next starts
   * @param initialDelay how long after start-up the first run starts
   */
  public record Every(@NotNull Duration interval, @NotNull Duration initialDelay) {

    /** Refuses an interval that is not positive, or a negative first delay. */
    public Every {
      if (interval != null && (interval.isZero() || interval.isNegative())) {
        throw new IllegalArgumentException("A job's interval must be more than zero");
      }
      if (initialDelay != null && initialDelay.isNegative()) {
        throw new IllegalArgumentException("A job's initial delay must be zero or more");
      }
    }
  }

  /**
   * A job that runs on a cron schedule in a given time zone.
   *
   * @param cron the six-field Spring cron expression, such as {@code 0 0 6 * * *}
   * @param zone the time zone the expression is read in, such as {@code Africa/Johannesburg}
   */
  public record Daily(@NotBlank String cron, @NotNull ZoneId zone) {

    /** Refuses a cron expression Spring cannot read. */
    public Daily {
      if (cron != null && !CronExpression.isValidExpression(cron)) {
        throw new IllegalArgumentException(
            "branchbook.jobs.holiday-check.cron must be a six-field cron expression");
      }
    }
  }
}
