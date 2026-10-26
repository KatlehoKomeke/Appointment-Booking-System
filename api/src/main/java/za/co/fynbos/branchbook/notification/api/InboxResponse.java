package za.co.fynbos.branchbook.notification.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import za.co.fynbos.branchbook.notification.application.InboxMessage;
import za.co.fynbos.branchbook.shared.time.TimeFormat;

/**
 * The simulated inbox: the newest messages of every appointment. A retry answers with one of its
 * {@link InboxMessageResponse} entries.
 *
 * @param messages the messages, newest first
 */
@Schema(name = "Inbox")
public record InboxResponse(List<InboxMessageResponse> messages) {

  static InboxResponse from(List<InboxMessage> messages) {
    return new InboxResponse(messages.stream().map(InboxMessageResponse::from).toList());
  }

  /**
   * One message in the inbox. Its times carry the branch's offset.
   *
   * @param id the message's id
   * @param reference the appointment's reference
   * @param kind what it tells the customer
   * @param channel how it reaches them
   * @param recipientMasked where it goes, redacted: no digit after the prefix
   * @param subject the e-mail subject; {@code null} for an SMS
   * @param body the text of the message
   * @param status where it is in its delivery
   * @param failureReason why it failed, or {@code null}
   * @param retryable whether a retry could send it, by the rule the retry itself applies
   * @param attempts how many times delivery has been tried
   * @param nextAttemptAt when delivery is next tried, or {@code null} unless PENDING
   * @param lastError a short code for why the last attempt failed, or {@code null}
   * @param createdAt when it was written
   * @param sentAt when the gateway accepted it, or {@code null}
   */
  @Schema(name = "InboxMessage")
  public record InboxMessageResponse(
      UUID id,
      @Schema(example = "APT-7K3M-9Q2X") String reference,
      @Schema(allowableValues = {"CONFIRMATION", "CANCELLATION"}) String kind,
      @Schema(allowableValues = {"SMS", "EMAIL"}) String channel,
      @Schema(example = "082 *** ****") String recipientMasked,
      @Schema(types = {"string", "null"}) @Nullable String subject,
      String body,
      @Schema(allowableValues = {"PENDING", "SENT", "FAILED"}) String status,
      @Schema(
              types = {"string", "null"},
              allowableValues = {
                "DELIVERY_FAILED",
                "SUPERSEDED_BY_CANCELLATION",
                "RECIPIENT_DAILY_CAP",
                "DISPATCH_ERROR"
              })
          @Nullable String failureReason,
      @Schema(
              description =
                  "True only for FAILED with DELIVERY_FAILED or DISPATCH_ERROR, and never for the"
                      + " confirmation of an appointment that is cancelled or has started")
          boolean retryable,
      @Schema(minimum = "0", maximum = "5") int attempts,
      @Schema(
              types = {"string", "null"},
              format = "date-time")
          @Nullable String nextAttemptAt,
      @Schema(types = {"string", "null"}) @Nullable String lastError,
      @Schema(format = "date-time", example = "2026-10-26T08:00:00+02:00") String createdAt,
      @Schema(
              types = {"string", "null"},
              format = "date-time")
          @Nullable String sentAt) {

    static InboxMessageResponse from(InboxMessage message) {
      return new InboxMessageResponse(
          message.id(),
          message.reference(),
          message.kind(),
          message.channel(),
          message.recipientRedacted(),
          message.subject(),
          message.body(),
          message.status(),
          message.failureReason(),
          message.retryable(),
          message.attempts(),
          message.nextAttemptAt() == null
              ? null
              : TimeFormat.isoOffset(message.nextAttemptAt(), message.zone()),
          message.lastError(),
          TimeFormat.isoOffset(message.createdAt(), message.zone()),
          message.sentAt() == null ? null : TimeFormat.isoOffset(message.sentAt(), message.zone()));
    }

    /**
     * Hides the reference, the recipient and the body (BR-19).
     *
     * @return the kind, the status and a fixed text
     */
    @Override
    public String toString() {
      return "InboxMessageResponse[" + kind + ", " + status + ", redacted]";
    }
  }
}
