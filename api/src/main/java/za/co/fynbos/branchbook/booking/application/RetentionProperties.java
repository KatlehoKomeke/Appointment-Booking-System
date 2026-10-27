package za.co.fynbos.branchbook.booking.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How long a booking is kept, under {@code branchbook.retention}: the hosted demo keeps each
 * booking, with the customer's name and number, until its appointment is over, and deletes it
 * within the hour after it ends (SECURITY.md, ADR-0006).
 *
 * <p>A booking is never deleted before its appointment ends, so a customer who books days ahead
 * keeps it until the day. {@code after-end} can keep it for longer; it is zero by default, and the
 * application refuses to start with a negative one, even while the purge is switched off, so a
 * mistyped value shows at start-up and never as bookings deleted early.
 *
 * <p>Deleting an appointment also deletes its {@code Idempotency-Key}, which may still have hours
 * to run when the appointment is short and booked the same day. That cannot turn a retry into a
 * second booking: by then the slot has started, and every booking of a slot that has started is
 * refused (BR-12, BR-21).
 *
 * @param enabled whether the hourly purge runs; {@code false} by default and locally, {@code true}
 *     only in the hosted demo's task definition
 * @param afterEnd how long after its appointment ends a booking is deleted, with its messages and
 *     its idempotency keys; {@code PT0S} by default, and never negative
 */
@Validated
@ConfigurationProperties("branchbook.retention")
public record RetentionProperties(boolean enabled, @DefaultValue("PT0S") Duration afterEnd) {

  /** Refuses a missing or negative {@code after-end}. */
  public RetentionProperties {
    if (afterEnd == null || afterEnd.isNegative()) {
      throw new IllegalArgumentException(
          "branchbook.retention.after-end must be zero or more, an ISO-8601 duration such as PT0S"
              + " or PT24H, so the purge never deletes a booking before its appointment ends");
    }
  }
}
