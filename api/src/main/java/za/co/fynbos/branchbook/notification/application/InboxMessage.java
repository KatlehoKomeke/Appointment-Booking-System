package za.co.fynbos.branchbook.notification.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import za.co.fynbos.branchbook.booking.application.AppointmentLabel;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.notification.domain.NotificationChannel;
import za.co.fynbos.branchbook.notification.domain.NotificationMessage;
import za.co.fynbos.branchbook.notification.domain.NotificationStatus;

/**
 * One message as the simulated inbox shows it: with its appointment's reference and the recipient
 * redacted to {@code 082 *** ****}, because the inbox is an operator view of every booking's
 * messages and no caller of it has proved any number.
 *
 * <p>A cancellation whose appointment the cancel has deleted shows {@code APT-****-****} as its
 * reference, as there is no appointment left to name, and its times in South Africa's one time
 * zone, which every branch keeps; it leaves the inbox once it is final and deleted (BR-14).
 *
 * @param id the message's id
 * @param reference the appointment's reference, or {@code APT-****-****} for a cancellation whose
 *     appointment is gone
 * @param kind {@code CONFIRMATION} or {@code CANCELLATION}
 * @param channel {@code SMS}, or {@code EMAIL} if the e-mail channel is added
 * @param recipientRedacted where it goes, with every digit after the prefix hidden
 * @param subject the e-mail subject; {@code null} for an SMS
 * @param body the text, which names the reference
 * @param status {@code PENDING}, {@code SENT} or {@code FAILED}
 * @param failureReason why it failed, or {@code null}
 * @param retryable whether a retry could send it: FAILED with DELIVERY_FAILED or DISPATCH_ERROR,
 *     and for a confirmation, an appointment neither cancelled nor started ({@link RetryPolicy})
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

  /** What an e-mail recipient would show as, if the e-mail channel is added. */
  private static final String HIDDEN_ADDRESS = "***";

  /**
   * The zone of a message with no appointment left: every branch is in it, and the cancellation SMS
   * names its time in it as SAST.
   */
  static final ZoneId DETACHED_ZONE = ZoneId.of("Africa/Johannesburg");

  /**
   * Shows a stored message in the inbox: with its appointment's reference, the recipient redacted,
   * and whether a retry could send it, by the same rule the retry itself applies.
   *
   * @param message the message as stored
   * @param appointment what booking says about the message's appointment, or {@code null} for a
   *     cancellation whose appointment the cancel has deleted
   * @param now the current instant, which decides whether a confirmation's appointment has started
   * @return the inbox entry
   */
  static InboxMessage of(
      NotificationMessage message, @Nullable AppointmentLabel appointment, Instant now) {
    boolean pending = message.status() == NotificationStatus.PENDING;
    return new InboxMessage(
        message.id(),
        appointment == null ? AppointmentReference.REDACTED : appointment.reference(),
        message.kind().name(),
        message.channel().name(),
        message.channel() == NotificationChannel.SMS
            ? MobileNumber.ofE164(message.recipient()).redacted()
            : HIDDEN_ADDRESS,
        message.subject(),
        message.body(),
        message.status().name(),
        message.failureReason(),
        RetryPolicy.allows(message, appointment, now),
        message.attempts(),
        pending ? message.nextAttemptAt() : null,
        message.lastError(),
        message.createdAt(),
        message.sentAt(),
        appointment == null ? DETACHED_ZONE : appointment.zone());
  }

  /**
   * Shows every appointment reference as {@code APT-****-****}: in the reference itself, and
   * wherever the subject, the body or the last error names one, such as an SMS's {@code Ref
   * APT-7K3M-9Q2X}. The id is kept, so the message can still be retried.
   *
   * @return the same message with no reference in it
   */
  public InboxMessage withReferencesHidden() {
    return new InboxMessage(
        id,
        AppointmentReference.REDACTED,
        kind,
        channel,
        recipientRedacted,
        subject == null ? null : AppointmentReference.hideIn(subject),
        AppointmentReference.hideIn(body),
        status,
        failureReason,
        retryable,
        attempts,
        nextAttemptAt,
        lastError == null ? null : AppointmentReference.hideIn(lastError),
        createdAt,
        sentAt,
        zone);
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
