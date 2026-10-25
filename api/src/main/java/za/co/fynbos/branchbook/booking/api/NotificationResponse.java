package za.co.fynbos.branchbook.booking.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZoneId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import za.co.fynbos.branchbook.booking.application.NotificationSummary;
import za.co.fynbos.branchbook.shared.time.TimeFormat;

/**
 * One message about an appointment, as its response shows it: whether the confirmation went out.
 *
 * @param id the message's id
 * @param channel how it reaches the customer
 * @param kind what it tells them
 * @param status where it is in its delivery
 * @param attempts how many times delivery has been tried
 * @param sentAt when the gateway accepted it, or {@code null}
 * @param failureReason why it failed, or {@code null}
 * @param recipientMasked where it goes, masked
 * @param body the text of the message
 */
@Schema(name = "Notification")
public record NotificationResponse(
    UUID id,
    @Schema(allowableValues = {"SMS", "EMAIL"}) String channel,
    @Schema(allowableValues = {"CONFIRMATION", "CANCELLATION"}) String kind,
    @Schema(allowableValues = {"PENDING", "SENT", "FAILED"}) String status,
    @Schema(minimum = "0", maximum = "5") int attempts,
    @Schema(
            types = {"string", "null"},
            format = "date-time")
        @Nullable String sentAt,
    @Schema(
            types = {"string", "null"},
            allowableValues = {
              "DELIVERY_FAILED",
              "SUPERSEDED_BY_CANCELLATION",
              "RECIPIENT_DAILY_CAP",
              "DISPATCH_ERROR"
            })
        @Nullable String failureReason,
    @Schema(example = "082 *** 0101") String recipientMasked,
    String body) {

  static NotificationResponse from(NotificationSummary summary, ZoneId zone) {
    return new NotificationResponse(
        summary.id(),
        summary.channel(),
        summary.kind(),
        summary.status(),
        summary.attempts(),
        summary.sentAt() == null ? null : TimeFormat.isoOffset(summary.sentAt(), zone),
        summary.failureReason(),
        summary.recipientMasked(),
        summary.body());
  }
}
