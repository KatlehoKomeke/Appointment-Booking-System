package za.co.fynbos.branchbook.notification.domain;

/**
 * Where a message is in its delivery: the values of {@code notification_messages.status}. A message
 * is written PENDING in the booking or cancel transaction; the dispatcher moves it to SENT or,
 * after its last attempt, FAILED. A cancellation moves its appointment's PENDING confirmation to
 * FAILED too, and a retry moves a FAILED message back to PENDING.
 */
public enum NotificationStatus {
  /** Written with the booking and waiting to be sent. */
  PENDING,
  /** Accepted by the gateway. */
  SENT,
  /** Not delivered; the failure reason says why. */
  FAILED
}
