package za.co.fynbos.branchbook.notification.domain;

/** How a message reaches the customer: the values of {@code notification_messages.channel}. */
public enum NotificationChannel {
  /** A text message to the customer's mobile number. */
  SMS,
  /** An e-mail, only if the optional e-mail channel is added. */
  EMAIL
}
