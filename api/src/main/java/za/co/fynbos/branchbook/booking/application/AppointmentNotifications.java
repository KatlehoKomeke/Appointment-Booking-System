package za.co.fynbos.branchbook.booking.application;

import java.util.List;
import java.util.UUID;

/**
 * Reads the messages sent, or still to be sent, about an appointment, so its response can show
 * whether the confirmation went out. Booking declares this port and the notification module
 * implements it.
 */
public interface AppointmentNotifications {

  /**
   * Lists an appointment's messages, oldest first.
   *
   * @param appointmentId the appointment's id
   * @return its messages, with the recipient masked; empty if there are none
   */
  List<NotificationSummary> forAppointment(UUID appointmentId);
}
