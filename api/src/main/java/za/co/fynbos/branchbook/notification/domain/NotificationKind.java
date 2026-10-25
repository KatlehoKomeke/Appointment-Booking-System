package za.co.fynbos.branchbook.notification.domain;

/** What a message tells the customer: the values of {@code notification_messages.kind}. */
public enum NotificationKind {
  /** The appointment was booked. */
  CONFIRMATION,
  /** The appointment was cancelled; sent once cancellation is built. */
  CANCELLATION
}
