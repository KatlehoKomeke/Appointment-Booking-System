package za.co.fynbos.branchbook.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.booking.application.AppointmentDirectory;
import za.co.fynbos.branchbook.booking.application.AppointmentLabel;
import za.co.fynbos.branchbook.notification.domain.NotificationMessage;

/**
 * Retries a FAILED message from the simulated inbox (FR-18): makes it due again, so the dispatcher
 * sends it on its next tick. It replaces the guarded SQL in docs/runbook.md wherever the inbox is
 * switched on.
 *
 * <p>One transaction locks the message with {@code FOR UPDATE}, waiting rather than skipping, so a
 * retry never races the dispatcher: a send in flight is marked first, and the retry then sees what
 * it left. {@link RetryPolicy} decides; a message it does not allow, such as one that is PENDING,
 * SENT, superseded or capped, or the confirmation of an appointment that is cancelled or has
 * started (BR-24), is returned unchanged with {@code retryable} false, and a second press is as
 * harmless as the first. The appointment is read through booking's {@link AppointmentDirectory},
 * without a lock. A cancellation whose appointment the cancel has deleted has none to read: while
 * it is PENDING its retry is the same no-op, shown with {@code APT-****-****}, and once it is final
 * it has been deleted, so its retry is {@code 404 MESSAGE_NOT_FOUND} (BR-14). Log lines name the
 * message by id only (BR-19).
 */
@Service
public class RetryMessageHandler {

  private static final Logger log = LoggerFactory.getLogger(RetryMessageHandler.class);

  private final NotificationMessageRepository messages;
  private final AppointmentDirectory appointments;
  private final Clock clock;

  RetryMessageHandler(
      NotificationMessageRepository messages, AppointmentDirectory appointments, Clock clock) {
    this.messages = messages;
    this.appointments = appointments;
    this.clock = clock;
  }

  /**
   * Retries a message, or leaves it as it is if a retry could not send it.
   *
   * @param messageId the message's id
   * @return the message as it is after the retry, in the inbox's shape
   * @throws MessageNotFoundException if no message has that id
   */
  @Transactional
  public InboxMessage retry(UUID messageId) {
    NotificationMessage message =
        messages.lockById(messageId).orElseThrow(MessageNotFoundException::new);
    Instant now = clock.instant();
    AppointmentLabel appointment = labelOf(message);
    if (!RetryPolicy.allows(message, appointment, now)) {
      log.info(
          "outbox.retry_unchanged messageId={} status={} failureReason={}",
          message.id(),
          message.status(),
          message.failureReason());
      return InboxMessage.of(message, appointment, now);
    }
    NotificationMessage reset = messages.resetForRetry(message.id(), now);
    log.info("outbox.retry_reset messageId={} kind={}", reset.id(), reset.kind());
    return InboxMessage.of(reset, appointment, now);
  }

  /**
   * The label of the message's appointment, or {@code null} for a cancellation whose appointment
   * the cancel has deleted. Only a cancellation may have none (V6), so any other message's must be
   * there.
   */
  private @Nullable AppointmentLabel labelOf(NotificationMessage message) {
    UUID appointmentId = message.appointmentId();
    if (appointmentId == null) {
      return null;
    }
    return Objects.requireNonNull(
        appointments.labels(List.of(appointmentId)).get(appointmentId),
        "A message's appointment is missing");
  }
}
