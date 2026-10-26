package za.co.fynbos.branchbook.notification.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One message as the simulated inbox shows it: with its appointment's reference and the recipient
 * redacted to {@code 082 *** ****}, because the inbox is an operator view of every booking's
 * messages and no caller of it has proved any number.
 *
 * @param id the message's id
 * @param reference the appointment's reference
 * @param kind {@code CONFIRMATION} or {@code CANCELLATION}
 * @param channel {@code SMS}, or {@code EMAIL} if the e-mail channel is added
 * @param recipientRedacted where it goes, with every digit after the prefix hidden
 * @param subject the e-mail subject; {@code null} for an SMS
 * @param body the text, which names the reference
 * @param status {@code PENDING}, {@code SENT} or {@code FAILED}
 * @param failureReason why it failed, or {@code null}
 * @param retryable whether a retry could send it: FAILED with DELIVERY_FAILED or DISPATCH_ERROR
 * @param attempts how many times delivery has been tried
 * @param nextAttemptAt when the dispatcher may next try it, or {@code null} unless PENDING
 * @param lastError a short code for why the last attempt failed, or {@code null}
 * @param createdAt when it was written
 * @param sentAt when the gateway accepted it, or {@code null}
 * @param zone the branch's zone, whose offset its times are shown in
 */
public record InboxMessage(
    UUID id,
    String reference,
    String kind,
    String channel,
    String recipientRedacted,
    @Nullable String subject,
    String body,
    String status,
    @Nullable String failureReason,
    boolean retryable,
    int attempts,
    @Nullable Instant nextAttemptAt,
    @Nullable String lastError,
    Instant createdAt,
    @Nullable Instant sentAt,
    ZoneId zone) {

  /** Rejects a missing value. */
  public InboxMessage {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(recipientRedacted, "recipientRedacted");
    Objects.requireNonNull(body, "body");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(zone, "zone");
  }

  /**
   * Hides the reference, the recipient and the body (BR-19).
   *
   * @return the kind, the status and a fixed text
   */
  @Override
  public String toString() {
    return "InboxMessage[" + kind + ", " + status + ", redacted]";
  }
}
