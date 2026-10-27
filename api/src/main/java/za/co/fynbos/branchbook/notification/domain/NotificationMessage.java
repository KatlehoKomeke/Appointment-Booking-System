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
 * @param appointmentId the appointment it is about, or {@code null} for a cancellation whose
 *     appointment the cancel has deleted; only a cancellation outlives its appointment (BR-14)
 * @param kind what it tells the customer
 * @param channel how it reaches them
 * @param recipient the mobile number in E.164 form (or the e-mail address)
 * @param subject the e-mail subject; {@code null} for an SMS
 * @param body the text
 * @param status where it is in its delivery
 * @param attempts how many times delivery has been tried
 * @param nextAttemptAt when the dispatcher may next try it
 * @param lastError a short code for why the last attempt failed, or {@code null}; never recipient
 *     data
 * @param failureReason why it failed, or {@code null}
 * @param sentAt when the gateway accepted it, or {@code null}
 * @param correlationId the id of the request that wrote it, so its lines can be found in the logs
 * @param createdAt when it was written
 */
public record NotificationMessage(
    UUID id,
    @Nullable UUID appointmentId,
    NotificationKind kind,
    NotificationChannel channel,
    String recipient,
    @Nullable String subject,
    String body,
    NotificationStatus status,
    int attempts,
    Instant nextAttemptAt,
    @Nullable String lastError,
    @Nullable String failureReason,
    @Nullable Instant sentAt,
    String correlationId,
    Instant createdAt) {

  /** The failure reason of a message the per-recipient daily cap stopped (BR-17). */
  public static final String RECIPIENT_DAILY_CAP = "RECIPIENT_DAILY_CAP";

  /** Rejects a missing value, and a confirmation without its appointment. */
  public NotificationMessage {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    if (appointmentId == null && kind != NotificationKind.CANCELLATION) {
      throw new IllegalArgumentException("Only a cancellation outlives its appointment");
    }
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
    return sms(
        appointmentId, kind, recipient, body, correlationId, now, NotificationStatus.PENDING, null);
  }

  /**
   * Creates an SMS that the per-recipient daily cap stops: FAILED from the start with {@code
   * RECIPIENT_DAILY_CAP}, so the dispatcher never claims it and the inbox's retry never resets it
   * (BR-17).
   *
   * @param appointmentId the appointment it is about
   * @param kind what it tells the customer
   * @param recipient the mobile number in E.164 form
   * @param body the text it would have carried
   * @param correlationId the id of the request that writes it
   * @param now the current instant
   * @return a FAILED message with no attempts
   */
  public static NotificationMessage cappedSms(
      UUID appointmentId,
      NotificationKind kind,
      String recipient,
      String body,
      String correlationId,
      Instant now) {
    return sms(
        appointmentId,
        kind,
        recipient,
        body,
        correlationId,
        now,
        NotificationStatus.FAILED,
        RECIPIENT_DAILY_CAP);
  }

  /** Builds an SMS with no attempts, due at once, in the given state. */
  private static NotificationMessage sms(
      UUID appointmentId,
      NotificationKind kind,
      String recipient,
      String body,
      String correlationId,
      Instant now,
      NotificationStatus status,
      @Nullable String failureReason) {
    return new NotificationMessage(
        UUID.randomUUID(),
        appointmentId,
        kind,
        NotificationChannel.SMS,
        recipient,
        null,
        body,
        status,
        0,
        now,
        null,
        failureReason,
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
