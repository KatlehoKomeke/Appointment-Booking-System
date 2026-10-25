package za.co.fynbos.branchbook.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One message to a customer: a row of {@code notification_messages}, the transactional outbox.
 *
 * <p>The recipient is personal data and the body names the appointment, so {@link #toString()}
 * shows neither.
 *
 * @param id the message's id
 * @param appointmentId the appointment it is about
 * @param kind what it tells the customer
 * @param channel how it reaches them
 * @param recipient the mobile number in E.164 form (or the e-mail address)
 * @param body the text
 * @param status where it is in its delivery
 * @param attempts how many times delivery has been tried
 * @param nextAttemptAt when the dispatcher may next try it
 * @param failureReason why it failed, or {@code null}
 * @param sentAt when the gateway accepted it, or {@code null}
 * @param correlationId the id of the request that wrote it, so its lines can be found in the logs
 * @param createdAt when it was written
 */
public record NotificationMessage(
    UUID id,
    UUID appointmentId,
    NotificationKind kind,
    NotificationChannel channel,
    String recipient,
    String body,
    NotificationStatus status,
    int attempts,
    Instant nextAttemptAt,
    @Nullable String failureReason,
    @Nullable Instant sentAt,
    String correlationId,
    Instant createdAt) {

  /** Rejects a missing value. */
  public NotificationMessage {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(appointmentId, "appointmentId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(recipient, "recipient");
    Objects.requireNonNull(body, "body");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  /**
   * Creates an SMS waiting to be sent, due straight away.
   *
   * @param appointmentId the appointment it is about
   * @param kind what it tells the customer
   * @param recipient the mobile number in E.164 form
   * @param body the text
   * @param correlationId the id of the request that writes it
   * @param now the current instant
   * @return a PENDING message with no attempts
   */
  public static NotificationMessage pendingSms(
      UUID appointmentId,
      NotificationKind kind,
      String recipient,
      String body,
      String correlationId,
      Instant now) {
    return new NotificationMessage(
        UUID.randomUUID(),
        appointmentId,
        kind,
        NotificationChannel.SMS,
        recipient,
        body,
        NotificationStatus.PENDING,
        0,
        now,
        null,
        null,
        correlationId,
        now);
  }

  /**
   * Shows the message's identity and state, never its recipient or text.
   *
   * @return the id, kind, channel and status
   */
  @Override
  public String toString() {
    return "NotificationMessage[id=" + id + ", " + kind + ", " + channel + ", " + status + "]";
  }
}
