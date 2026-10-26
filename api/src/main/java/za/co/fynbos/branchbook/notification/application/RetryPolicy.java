package za.co.fynbos.branchbook.notification.application;

import java.time.Instant;
import java.util.Set;
import za.co.fynbos.branchbook.booking.application.AppointmentLabel;
import za.co.fynbos.branchbook.notification.domain.NotificationKind;
import za.co.fynbos.branchbook.notification.domain.NotificationMessage;
import za.co.fynbos.branchbook.notification.domain.NotificationStatus;

/**
 * Decides whether a retry may send a message again (FR-18, BR-24). It is the one rule behind both
 * the inbox's {@code retryable} flag and {@link RetryMessageHandler}, so the button the inbox shows
 * and what pressing it does can never disagree.
 *
 * <p>Only a FAILED message whose failure a retry could cure qualifies: {@code DELIVERY_FAILED} or
 * {@code DISPATCH_ERROR}. A superseded or capped message stays as it is. A confirmation also needs
 * an appointment that is neither cancelled nor started, because telling a customer about an
 * appointment they cancelled, or one already under way, would mislead them.
 */
final class RetryPolicy {

  /** The failure reasons a retry could cure. */
  private static final Set<String> CURABLE = Set.of("DELIVERY_FAILED", "DISPATCH_ERROR");

  private RetryPolicy() {}

  /**
   * Tells whether a retry may send a message again.
   *
   * @param message the message as stored
   * @param appointment what booking says about the message's appointment
   * @param now the current instant, from the application's clock
   * @return {@code true} if a retry would reset the message to PENDING
   */
  static boolean allows(NotificationMessage message, AppointmentLabel appointment, Instant now) {
    boolean curable =
        message.status() == NotificationStatus.FAILED && CURABLE.contains(message.failureReason());
    if (!curable || message.kind() != NotificationKind.CONFIRMATION) {
      return curable;
    }
    return !appointment.cancelled() && now.isBefore(appointment.startsAt());
  }
}
