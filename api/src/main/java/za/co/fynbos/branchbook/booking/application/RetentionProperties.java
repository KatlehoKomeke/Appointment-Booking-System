package za.co.fynbos.branchbook.booking.application;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How long a booking is kept, under {@code branchbook.retention}: the hosted demo promises to
 * delete every booking, with the customer's name and number, after 48 hours (SECURITY.md,
 * ADR-0006).
 *
 * <p>The application refuses to start with a max-age shorter than {@link #MINIMUM_MAX_AGE}, even
 * while the purge is switched off, so a mistyped value shows at start-up and never as bookings
 * deleted early. The floor is the 24 hours an {@code Idempotency-Key} is honoured for (BR-12): from
 * then on every key of a purged appointment has already expired, so the purge can never turn a
 * client's retry into a second booking. It also stops a value without its unit, which Spring reads
 * as milliseconds, from deleting bookings while their customers are still on the confirmation page.
 *
 * @param enabled whether the hourly purge runs; {@code false} by default and locally, {@code true}
 *     only in the hosted demo's task definition
 * @param maxAge how long after its booking an appointment is deleted, with its messages and its
 *     idempotency keys; {@code PT48H} by default, and never less than {@link #MINIMUM_MAX_AGE}
 */
@Validated
@ConfigurationProperties("branchbook.retention")
public record RetentionProperties(boolean enabled, @NotNull Duration maxAge) {

  /** The shortest max-age allowed: the lifetime of an {@code Idempotency-Key} (BR-12). */
  public static final Duration MINIMUM_MAX_AGE = Duration.ofHours(24);

  /** Refuses a missing max-age, or one shorter than {@link #MINIMUM_MAX_AGE}. */
  public RetentionProperties {
    if (maxAge == null || maxAge.compareTo(MINIMUM_MAX_AGE) < 0) {
      throw new IllegalArgumentException(
          "branchbook.retention.max-age must be at least "
              + MINIMUM_MAX_AGE
              + ", how long an Idempotency-Key is honoured, so the purge never deletes a booking a"
              + " retry could still replay");
    }
  }
}
