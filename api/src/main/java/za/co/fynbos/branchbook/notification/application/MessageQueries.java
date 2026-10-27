package za.co.fynbos.branchbook.notification.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.booking.application.AppointmentNotifications;
import za.co.fynbos.branchbook.booking.application.NotificationSummary;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.notification.domain.NotificationChannel;
import za.co.fynbos.branchbook.notification.domain.NotificationMessage;

/**
 * Reads an appointment's messages for its response, with the recipient masked: {@code 082 *** 0101}
 * for an SMS. The response goes only to the customer who booked or who has just proved the number.
 */
@Service
public class MessageQueries implements AppointmentNotifications {

  /** What an e-mail recipient would show as, if the e-mail channel is added. */
  private static final String HIDDEN_ADDRESS = "***";

  private final NotificationMessageRepository messages;

  MessageQueries(NotificationMessageRepository messages) {
    this.messages = messages;
  }

  @Override
  @Transactional(readOnly = true)
  public List<NotificationSummary> forAppointment(UUID appointmentId) {
    return messages.forAppointment(appointmentId).stream().map(MessageQueries::summary).toList();
  }

  /**
   * Shows one message as an appointment's response does, with the recipient masked.
   *
   * @param message the message
   * @return its summary
   */
  static NotificationSummary summary(NotificationMessage message) {
    String recipient =
        message.channel() == NotificationChannel.SMS
            ? MobileNumber.ofE164(message.recipient()).masked()
            : HIDDEN_ADDRESS;
    return new NotificationSummary(
        message.id(),
        message.channel().name(),
        message.kind().name(),
        message.status().name(),
        message.attempts(),
        message.sentAt(),
        message.failureReason(),
        recipient,
        message.body());
  }
}
