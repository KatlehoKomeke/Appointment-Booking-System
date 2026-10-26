package za.co.fynbos.branchbook.booking.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZoneId;
import java.util.List;
import org.jspecify.annotations.Nullable;
import za.co.fynbos.branchbook.booking.application.AppointmentView;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;
import za.co.fynbos.branchbook.catalogue.domain.Address;
import za.co.fynbos.branchbook.catalogue.domain.Branch;
import za.co.fynbos.branchbook.catalogue.domain.ServiceType;
import za.co.fynbos.branchbook.shared.time.TimeFormat;

/**
 * An appointment as the booking response and the lookup return it. It goes only to the customer who
 * booked, or who has just proved the reference and the last four digits, so the number is masked
 * and nothing else is hidden. Every instant carries the branch's offset (BR-20).
 *
 * @param reference the reference, for example {@code APT-7K3M-9Q2X}
 * @param status where it is in its lifecycle
 * @param branch the branch it is at
 * @param service the service it is for
 * @param startsAt when it starts
 * @param endsAt when it ends: the start plus the service's duration
 * @param customer who it is for
 * @param canCancel whether the customer may still cancel it
 * @param cancelledAt when it was cancelled, or {@code null}
 * @param notifications the messages about it, oldest first
 */
@Schema(name = "Appointment")
public record AppointmentResponse(
    @Schema(example = "APT-7K3M-9Q2X", pattern = AppointmentReference.PATTERN) String reference,
    @Schema(allowableValues = {"CONFIRMED", "CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW"})
        String status,
    BranchResponse branch,
    ServiceResponse service,
    @Schema(format = "date-time", example = "2026-10-27T09:30:00+02:00") String startsAt,
    @Schema(format = "date-time", example = "2026-10-27T09:45:00+02:00") String endsAt,
    CustomerResponse customer,
    @Schema(description = "True while the appointment is confirmed and has not started")
        boolean canCancel,
    @Schema(
            types = {"string", "null"},
            format = "date-time")
        @Nullable String cancelledAt,
    List<NotificationResponse> notifications) {

  /**
   * Hides the reference, the customer and the messages, so a response passed to a logger shows none
   * of them (BR-19).
   *
   * @return the status and a fixed text
   */
  @Override
  public String toString() {
    return "AppointmentResponse[" + status + ", redacted]";
  }

  static AppointmentResponse from(AppointmentView view) {
    ZoneId zone = view.branch().timezone();
    return new AppointmentResponse(
        view.reference(),
        view.status().name(),
        BranchResponse.from(view.branch()),
        ServiceResponse.from(view.service()),
        TimeFormat.isoOffset(view.startsAt(), zone),
        TimeFormat.isoOffset(view.endsAt(), zone),
        new CustomerResponse(view.firstName(), view.lastName(), view.mobile().masked()),
        view.canCancel(),
        view.cancelledAt() == null ? null : TimeFormat.isoOffset(view.cancelledAt(), zone),
        view.notifications().stream()
            .map(notification -> NotificationResponse.from(notification, zone))
            .toList());
  }

  /**
   * The branch an appointment is at.
   *
   * @param code the branch code
   * @param name the branch's name
   * @param address the street address on one line
   */
  @Schema(name = "AppointmentBranch")
  public record BranchResponse(
      @Schema(example = "SAN") String code,
      @Schema(example = "Sandton Summit Mall") String name,
      @Schema(example = "Shop 4, 1 Protea Lane, Sandton, Johannesburg, 2196") String address) {

    static BranchResponse from(Branch branch) {
      Address address = branch.address();
      return new BranchResponse(
          branch.code(),
          branch.name(),
          String.join(
              ", ", address.line1(), address.suburb(), address.city(), address.postalCode()));
    }
  }

  /**
   * The service an appointment is for.
   *
   * @param code the service code
   * @param name the service's name
   * @param durationMinutes how long it takes
   * @param documentsNeeded what to bring
   */
  @Schema(name = "AppointmentService")
  public record ServiceResponse(
      @Schema(example = "FICA") String code,
      @Schema(example = "Update personal details (FICA)") String name,
      @Schema(example = "15") int durationMinutes,
      List<String> documentsNeeded) {

    static ServiceResponse from(ServiceType service) {
      return new ServiceResponse(
          service.code(), service.name(), service.durationMinutes(), service.documentsNeeded());
    }
  }

  /**
   * Who an appointment is for.
   *
   * @param firstName the first name
   * @param lastName the last name
   * @param mobileMasked the mobile number with its middle hidden
   */
  @Schema(name = "AppointmentCustomer")
  public record CustomerResponse(
      @Schema(example = "Nomvula") String firstName,
      @Schema(example = "Dlamini") String lastName,
      @Schema(example = "082 *** 0101") String mobileMasked) {

    /**
     * Hides the customer's details, so the response cannot reach a log with them.
     *
     * @return a fixed text without personal data
     */
    @Override
    public String toString() {
      return "CustomerResponse[redacted]";
    }
  }
}
