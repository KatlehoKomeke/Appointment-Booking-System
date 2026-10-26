package za.co.fynbos.branchbook.booking.application;

import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.booking.domain.Appointment;
import za.co.fynbos.branchbook.catalogue.application.CatalogueLookup;

/**
 * Names appointments for other modules, which may not read booking's tables: the simulated inbox
 * shows each message with its appointment's reference and its branch's times. One query finds the
 * appointments, and each distinct branch is looked up once.
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
   * Labels appointments by id.
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
      labels.put(appointment.id(), new AppointmentLabel(appointment.reference(), zone));
    }
    return labels;
  }
}
