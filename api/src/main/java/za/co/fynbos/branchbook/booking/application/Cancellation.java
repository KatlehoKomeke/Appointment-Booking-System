package za.co.fynbos.branchbook.booking.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import za.co.fynbos.branchbook.booking.domain.Appointment;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.catalogue.domain.Branch;
import za.co.fynbos.branchbook.catalogue.domain.ServiceType;

/**
 * Everything the cancellation message of an appointment needs, gathered by the cancel transaction
 * so the outbox needs no other module's data. It names the same service, branch and time as the
 * confirmation did, so the customer can tell which appointment it means.
 *
 * @param appointmentId the appointment the message belongs to until the cancel deletes it, whose
 *     confirmations it replaces
 * @param reference the appointment's reference
 * @param recipient the customer's mobile number
 * @param startsAt when the appointment would have started
 * @param zone the branch's time zone, which the message's time is written in
 * @param branchSmsName the branch's short name, for example {@code Sandton Summit}
 * @param serviceSmsName the service's short name, for example {@code FICA update}
 * @param now when the appointment was cancelled, which is when the message is first due
 */
public record Cancellation(
    UUID appointmentId,
    String reference,
    MobileNumber recipient,
    Instant startsAt,
    ZoneId zone,
    String branchSmsName,
    String serviceSmsName,
    Instant now) {

  /** Rejects a missing value. */
  public Cancellation {
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
   * Gathers the cancellation of an appointment.
   *
   * @param appointment the appointment just cancelled
   * @param branch the branch it was at
   * @param service the service it was for
   * @param now when it was cancelled
   * @return the cancellation, due at once
   */
  public static Cancellation of(
      Appointment appointment, Branch branch, ServiceType service, Instant now) {
    return new Cancellation(
        appointment.id(),
        appointment.reference(),
        appointment.mobile(),
        appointment.startsAt(),
        branch.timezone(),
        branch.smsName(),
        service.smsName(),
        now);
  }

  /**
   * Hides the recipient and the reference, so the cancellation cannot reach a log with either
   * (BR-19).
   *
   * @return the appointment's id only
   */
  @Override
  public String toString() {
    return "Cancellation[appointmentId=" + appointmentId + ", recipient redacted]";
  }
}
