package za.co.fynbos.branchbook.booking.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.booking.domain.Appointment;
import za.co.fynbos.branchbook.catalogue.application.CatalogueLookup;

/**
 * Renders an appointment as its owner sees it: with its branch, its service and its messages, read
 * after the booking committed, so a replay shows the appointment as it is now (BR-12).
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
   *     out
   */
  @Transactional(readOnly = true)
  public AppointmentView byId(UUID appointmentId, Instant now) {
    return of(
        appointments
            .findById(appointmentId)
            .orElseThrow(() -> new IllegalStateException("A booked appointment has disappeared")),
        now);
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
        notifications.forAppointment(appointment.id()));
  }
}
