package za.co.fynbos.branchbook.booking.application;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import za.co.fynbos.branchbook.booking.domain.AppointmentStatus;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.catalogue.domain.Branch;
import za.co.fynbos.branchbook.catalogue.domain.ServiceType;

/**
 * An appointment as its owner sees it: what the booking response and the lookup return. It goes
 * only to the customer who booked, or who has just proved the reference and the number, so it
 * carries their name and the masked number; {@link #toString()} shows none of it.
 *
 * @param reference the reference
 * @param status where it is in its lifecycle
 * @param branch the branch it is at
 * @param service the service it is for
 * @param startsAt when it starts
 * @param endsAt when it ends
 * @param firstName the customer's first name
 * @param lastName the customer's last name
 * @param mobile the customer's mobile number, to be shown masked
 * @param canCancel whether the customer may still cancel it
 * @param cancelledAt when it was cancelled, or {@code null}
 * @param notifications the messages about it, oldest first
 */
public record AppointmentView(
    String reference,
    AppointmentStatus status,
    Branch branch,
    ServiceType service,
    Instant startsAt,
    Instant endsAt,
    String firstName,
    String lastName,
    MobileNumber mobile,
    boolean canCancel,
    @Nullable Instant cancelledAt,
    List<NotificationSummary> notifications) {

  /** Keeps an unmodifiable copy of the messages. */
  public AppointmentView {
    notifications = List.copyOf(notifications);
  }

  /**
   * Shows nothing that identifies the appointment or its customer.
   *
   * @return a fixed text and the status
   */
  @Override
  public String toString() {
    return "AppointmentView[" + status + ", redacted]";
  }
}
