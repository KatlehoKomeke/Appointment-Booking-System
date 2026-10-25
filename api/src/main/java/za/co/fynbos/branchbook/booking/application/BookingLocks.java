package za.co.fynbos.branchbook.booking.application;

import java.util.UUID;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;

/**
 * The two locks the booking transaction takes, always in this order, so concurrent bookings queue
 * instead of deadlocking (BR-7, BR-22, ADR-0002):
 *
 * <ol>
 *   <li>{@link #lockCustomer}: one booking per mobile number at a time, so the customer rules are
 *       checked against every booking that number has committed.
 *   <li>{@link #lockSeats}: one booking per service at a branch at a time, so the lowest free seat
 *       is chosen against every booking that has committed.
 * </ol>
 *
 * <p>Both are released when the transaction ends. A lock not granted within the database's {@code
 * lock_timeout} becomes {@code 503 TRY_AGAIN}.
 */
public interface BookingLocks {

  /**
   * Takes the transaction's lock on a mobile number.
   *
   * @param mobile the customer's number
   */
  void lockCustomer(MobileNumber mobile);

  /**
   * Takes the transaction's lock on how a branch offers a service, and reads its capacity.
   *
   * @param branchId the branch
   * @param serviceId the service
   * @return the number of seats
   * @throws za.co.fynbos.branchbook.catalogue.application.ServiceNotOfferedAtBranchException if the
   *     branch no longer offers the service
   */
  int lockSeats(UUID branchId, UUID serviceId);
}
