package za.co.fynbos.branchbook.notification.application;

import java.util.List;
import java.util.UUID;
import za.co.fynbos.branchbook.notification.domain.NotificationMessage;

/** Where messages are stored: the {@code notification_messages} table. */
public interface NotificationMessageRepository {

  /**
   * Stores a new message in the caller's transaction.
   *
   * @param message the message
   */
  void insert(NotificationMessage message);

  /**
   * Lists an appointment's messages, oldest first.
   *
   * @param appointmentId the appointment's id
   * @return its messages; empty if there are none
   */
  List<NotificationMessage> forAppointment(UUID appointmentId);

  /**
   * Lists the newest messages of every appointment, for the simulated inbox.
   *
   * @param limit the most messages to return
   * @return the messages, newest first
   */
  List<NotificationMessage> newest(int limit);
}
