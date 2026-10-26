package za.co.fynbos.branchbook.booking.application;

import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.booking.domain.Appointment;
import za.co.fynbos.branchbook.booking.domain.AppointmentStatus;
import za.co.fynbos.branchbook.catalogue.application.CatalogueLookup;

/**
 * Answers other modules' questions about appointments, so no other module reads booking's tables.
 * The simulated inbox shows each message with its appointment's reference and its branch's times;
 * the dispatcher and the retry of a message ask whether a confirmation's appointment is cancelled
 * or has started (BR-24). Every answer is a plain read that takes no lock, so a caller holding a
 * message's row lock can never wait for a cancel that holds the appointment's.
 */
@Service
public class AppointmentDirectory {

  private final AppointmentRepository appointments;
  private final CatalogueLookup catalogue;

  AppointmentDirectory(AppointmentRepository appointments, CatalogueLookup catalogue) {
    this.appointments = appointments;
    this.catalogue = catalogue;
  }

  /**
   * Labels appointments by id. One query finds the appointments, and each distinct branch is looked
   * up once.
   *
   * @param appointmentIds the appointments to label
   * @return a label for each appointment that exists, by its id
   */
  @Transactional(readOnly = true)
  public Map<UUID, AppointmentLabel> labels(Collection<UUID> appointmentIds) {
    Map<UUID, ZoneId> zones = new HashMap<>();
    Map<UUID, AppointmentLabel> labels = new HashMap<>();
    for (Appointment appointment : appointments.findAllById(appointmentIds)) {
      ZoneId zone =
          zones.computeIfAbsent(
              appointment.branchId(), branchId -> catalogue.branch(branchId).timezone());
      labels.put(
          appointment.id(),
          new AppointmentLabel(
              appointment.reference(),
              zone,
              appointment.startsAt(),
              appointment.status() == AppointmentStatus.CANCELLED));
    }
    return labels;
  }

  /**
   * Tells whether an appointment has been cancelled, as committed when the caller's statement runs.
   * The dispatcher asks before it sends a confirmation, so one reset by hand after the cancellation
   * is never delivered.
   *
   * @param appointmentId the appointment's id
   * @return {@code true} if it exists and is cancelled
   */
  @Transactional(readOnly = true)
  public boolean isCancelled(UUID appointmentId) {
    return appointments
        .findById(appointmentId)
        .map(appointment -> appointment.status() == AppointmentStatus.CANCELLED)
        .orElse(false);
  }
}
