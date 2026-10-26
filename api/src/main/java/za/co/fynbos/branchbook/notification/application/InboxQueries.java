package za.co.fynbos.branchbook.notification.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.booking.application.AppointmentDirectory;
import za.co.fynbos.branchbook.booking.application.AppointmentLabel;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.notification.domain.NotificationChannel;
import za.co.fynbos.branchbook.notification.domain.NotificationMessage;
import za.co.fynbos.branchbook.notification.domain.NotificationStatus;

/**
 * Reads the simulated inbox: the newest messages of every appointment, each with its reference and
 * its recipient redacted (FR-17). The appointments are named through booking's {@link
 * AppointmentDirectory}, so this module still reads no table of another.
 */
@Service
public class InboxQueries {

  /** The failure reasons a retry could cure; a superseded or capped message stays as it is. */
  private static final Set<String> RETRYABLE_REASONS = Set.of("DELIVERY_FAILED", "DISPATCH_ERROR");

  /** What an e-mail recipient would show as, if the e-mail channel is added. */
  private static final String HIDDEN_ADDRESS = "***";

  private final NotificationMessageRepository messages;
  private final AppointmentDirectory appointments;

  InboxQueries(NotificationMessageRepository messages, AppointmentDirectory appointments) {
    this.messages = messages;
    this.appointments = appointments;
  }

  /**
   * Lists the newest messages.
   *
   * @param limit the most messages to return, already checked to be from 1 to 200
   * @return the messages, newest first
   */
  @Transactional(readOnly = true)
  public List<InboxMessage> newest(int limit) {
    List<NotificationMessage> newest = messages.newest(limit);
    Map<UUID, AppointmentLabel> labels =
        appointments.labels(
            newest.stream().map(NotificationMessage::appointmentId).collect(Collectors.toSet()));
    return newest.stream()
        .map(message -> entry(message, labels.get(message.appointmentId())))
        .toList();
  }

  private static InboxMessage entry(NotificationMessage message, AppointmentLabel label) {
    boolean pending = message.status() == NotificationStatus.PENDING;
    return new InboxMessage(
        message.id(),
        label.reference(),
        message.kind().name(),
        message.channel().name(),
        message.channel() == NotificationChannel.SMS
            ? MobileNumber.ofE164(message.recipient()).redacted()
            : HIDDEN_ADDRESS,
        message.subject(),
        message.body(),
        message.status().name(),
        message.failureReason(),
        message.status() == NotificationStatus.FAILED
            && RETRYABLE_REASONS.contains(message.failureReason()),
        message.attempts(),
        pending ? message.nextAttemptAt() : null,
        message.lastError(),
        message.createdAt(),
        message.sentAt(),
        label.zone());
  }
}
