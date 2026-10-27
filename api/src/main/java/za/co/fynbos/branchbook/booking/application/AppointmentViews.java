package za.co.fynbos.branchbook.booking.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.booking.domain.Appointment;
import za.co.fynbos.branchbook.catalogue.application.CatalogueLookup;

/**
 * Renders an appointment as its owner sees it: with its branch, its service and its messages, read
 * after the booking committed, so a replay shows the appointment as it is now (BR-12). A cancel
 * renders the appointment it is about to delete from what it holds in memory instead, with only the
 * cancellation SMS it has just written (BR-14).
 */
@Service
public class AppointmentViews {

  private final AppointmentRepository appointments;
  private final CatalogueLookup catalogue;
  private final AppointmentNotifications notifications;

  AppointmentViews(
      AppointmentRepository appointments,
      CatalogueLookup catalogue,
      AppointmentNotifications notifications) {
    this.appointments = appointments;
    this.catalogue = catalogue;
    this.notifications = notifications;
  }

  /**
   * Renders an appointment by id.
   *
   * @param appointmentId the appointment's id
   * @param now the current instant, which decides whether it can still be cancelled
   * @return the view
   * @throws IllegalStateException if no appointment has that id, which a committed booking rules
   *     out until it is cancelled
   */
  @Transactional(readOnly = true)
  public AppointmentView byId(UUID appointmentId, Instant now) {
    return findById(appointmentId, now)
        .orElseThrow(() -> new IllegalStateException("A booked appointment has disappeared"));
  }

  /**
   * Renders an appointment by id, if it is still there: a cancel deletes the appointment it
   * cancels, so a replay of its booking may find it gone.
   *
   * @param appointmentId the appointment's id
   * @param now the current instant, which decides whether it can still be cancelled
   * @return the view, or empty if no appointment has that id
   */
  @Transactional(readOnly = true)
  public Optional<AppointmentView> findById(UUID appointmentId, Instant now) {
    return appointments.findById(appointmentId).map(appointment -> of(appointment, now));
  }

  /**
   * Renders an appointment already loaded.
   *
   * @param appointment the appointment
   * @param now the current instant, which decides whether it can still be cancelled
   * @return the view
   */
  @Transactional(readOnly = true)
  public AppointmentView of(Appointment appointment, Instant now) {
    return of(appointment, now, notifications.forAppointment(appointment.id()));
  }

  /**
   * Renders an appointment already loaded, with the messages the caller gives rather than those
   * stored: the cancel's response, built before the appointment is deleted (BR-14).
   *
   * @param appointment the appointment
   * @param now the current instant, which decides whether it can still be cancelled
   * @param messages the messages to show, oldest first
   * @return the view
   */
  public AppointmentView of(
      Appointment appointment, Instant now, List<NotificationSummary> messages) {
    return new AppointmentView(
        appointment.reference(),
        appointment.status(),
        catalogue.branch(appointment.branchId()),
        catalogue.service(appointment.serviceId()),
        appointment.startsAt(),
        appointment.endsAt(),
        appointment.customer().firstName(),
        appointment.customer().lastName(),
        appointment.mobile(),
        appointment.canCancel(now),
        appointment.cancelledAt(),
        messages);
  }
}
