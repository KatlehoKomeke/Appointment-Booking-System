package za.co.fynbos.branchbook.booking.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import za.co.fynbos.branchbook.booking.domain.Appointment;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.catalogue.domain.Branch;
import za.co.fynbos.branchbook.catalogue.domain.ServiceOffer;

/**
 * Everything the confirmation of a new appointment needs, gathered by the booking transaction so
 * the outbox does not query the catalogue while the seat lock is held.
 *
 * @param appointmentId the appointment the message belongs to
 * @param reference the appointment's reference
 * @param recipient the customer's mobile number
 * @param startsAt when the appointment starts
 * @param zone the branch's time zone, which the message's time is written in
 * @param branchSmsName the branch's short name, for example {@code Sandton Summit}
 * @param serviceSmsName the service's short name, for example {@code FICA update}
 * @param now when the booking was made, which is when the message is first due
 */
public record Confirmation(
    UUID appointmentId,
    String reference,
    MobileNumber recipient,
    Instant startsAt,
    ZoneId zone,
    String branchSmsName,
    String serviceSmsName,
    Instant now) {

  /** Rejects a missing value. */
  public Confirmation {
    Objects.requireNonNull(appointmentId, "appointmentId");
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(recipient, "recipient");
    Objects.requireNonNull(startsAt, "startsAt");
    Objects.requireNonNull(zone, "zone");
    Objects.requireNonNull(branchSmsName, "branchSmsName");
    Objects.requireNonNull(serviceSmsName, "serviceSmsName");
    Objects.requireNonNull(now, "now");
  }

  /**
   * Gathers the confirmation of a new appointment.
   *
   * @param appointment the appointment just inserted
   * @param branch the branch it is at
   * @param service how the branch offers the service it is for
   * @return the confirmation, due at the appointment's creation time
   */
  public static Confirmation of(Appointment appointment, Branch branch, ServiceOffer service) {
    return new Confirmation(
        appointment.id(),
        appointment.reference(),
        appointment.mobile(),
        appointment.startsAt(),
        branch.timezone(),
        branch.smsName(),
        service.smsName(),
        appointment.createdAt());
  }

  /**
   * Hides the recipient, so the confirmation cannot reach a log with the customer's number.
   *
   * @return the appointment's id only
   */
  @Override
  public String toString() {
    return "Confirmation[appointmentId=" + appointmentId + ", recipient redacted]";
  }
}
