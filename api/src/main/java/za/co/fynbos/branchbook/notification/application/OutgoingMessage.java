package za.co.fynbos.branchbook.notification.application;

import java.util.Objects;
import java.util.UUID;
import za.co.fynbos.branchbook.notification.domain.NotificationChannel;
import za.co.fynbos.branchbook.notification.domain.NotificationMessage;

/**
 * A message as the gateway receives it: only what delivery needs.
 *
 * @param id the message's id, which a real provider would echo back
 * @param channel how it reaches the customer
 * @param recipient the mobile number in E.164 form
 * @param body the text
 */
public record OutgoingMessage(UUID id, NotificationChannel channel, String recipient, String body) {

  /** Rejects a missing value. */
  public OutgoingMessage {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(recipient, "recipient");
    Objects.requireNonNull(body, "body");
  }

  static OutgoingMessage of(NotificationMessage message) {
    return new OutgoingMessage(
        message.id(), message.channel(), message.recipient(), message.body());
  }

  /**
   * Shows the message's id and channel, never its recipient or text (BR-19).
   *
   * @return the id and channel
   */
  @Override
  public String toString() {
    return "OutgoingMessage[id=" + id + ", " + channel + "]";
  }
}
