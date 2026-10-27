package za.co.fynbos.branchbook.booking.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import za.co.fynbos.branchbook.booking.domain.Appointment;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;

/**
 * Where appointments are stored: the {@code appointments} table. Every query that depends on time
 * takes the instant from the caller, never from the database's clock (BR-20), and counts only
 * active appointments, so cancelled and past ones never block a booking (BR-25).
 */
public interface AppointmentRepository {

  /**
   * Inserts a new appointment and flushes it at once, so a database rule it breaks is reported by
   * this call, not at commit, and the confirmation written next can refer to it.
   *
   * @param appointment the appointment
   * @throws SlotFullException if its seat was taken meanwhile (the seat exclusion constraint)
   * @throws CustomerOverlapException if its customer is somewhere else then (the customer exclusion
   *     constraint)
   * @throws ReferenceCollisionException if its reference is already taken
   */
  void insert(Appointment appointment);

  /**
   * Locks the row of an appointment loaded in this transaction, so the cancel can delete it: {@code
   * SELECT ... WHERE id = ? AND version = ? FOR UPDATE}, waiting at most the session's {@code
   * lock_timeout} for a transaction that holds it. From then on the appointment is no longer
   * managed, so a change made to it in memory, such as its cancellation, is never written: the
   * cancel deletes the row instead (BR-14).
   *
   * @param appointment the appointment, loaded in this transaction
   * @throws org.springframework.dao.OptimisticLockingFailureException if the row is gone, or was
   *     changed, since the appointment was loaded: another cancel or the retention purge deleted it
   */
  void lockForDeletion(Appointment appointment);

  /**
   * Deletes an appointment locked with {@link #lockForDeletion}, with its version checked: {@code
   * DELETE ... WHERE id = ? AND version = ?}. Its confirmations must have gone first; the foreign
   * keys then keep its cancellation SMS and its idempotency keys, with no appointment (V6).
   *
   * @param appointment the appointment
   * @throws org.springframework.dao.OptimisticLockingFailureException if no row was deleted
   */
  void delete(Appointment appointment);

  /**
   * Finds an appointment by id.
   *
   * @param id the appointment's id
   * @return the appointment, or empty
   */
  Optional<Appointment> findById(UUID id);

  /**
   * Finds an appointment by reference.
   *
   * @param reference the reference in its stored form
   * @return the appointment, or empty
   */
  Optional<Appointment> findByReference(AppointmentReference reference);

  /**
   * Finds the appointments with the given ids, in no particular order.
   *
   * @param ids the appointments' ids
   * @return those that exist
   */
  List<Appointment> findAllById(Collection<UUID> ids);

  /**
   * Finds the lowest seat that no active appointment of the service holds during a period.
   *
   * @param branchId the branch
   * @param serviceId the service
   * @param startsAt the period's start
   * @param endsAt the period's end
   * @param capacity the number of seats
   * @return the lowest free seat, from 1 to the capacity, or empty when every seat is taken
   */
  OptionalInt findLowestFreeSeat(
      UUID branchId, UUID serviceId, Instant startsAt, Instant endsAt, int capacity);

  /**
   * Tells whether a mobile number already has an upcoming active appointment for a service (BR-10).
   *
   * @param mobile the customer's number
   * @param serviceId the service
   * @param now the current instant; an appointment that has ended does not count
   * @return {@code true} if one exists
   */
  boolean hasUpcomingActiveForService(MobileNumber mobile, UUID serviceId, Instant now);

  /**
   * Tells whether a mobile number has an active appointment overlapping a period, at any branch or
   * service (BR-9).
   *
   * @param mobile the customer's number
   * @param startsAt the period's start
   * @param endsAt the period's end
   * @return {@code true} if one exists
   */
  boolean hasOverlappingActive(MobileNumber mobile, Instant startsAt, Instant endsAt);

  /**
   * Locks, for the caller's transaction, the appointments that ended no later than an instant,
   * whatever their status, for the retention purge. An appointment another transaction has locked
   * is skipped, not waited for, and found again on a later run.
   *
   * @param cutoff the latest end to include; an appointment ending at exactly this instant is
   *     included
   * @param limit the most appointments to lock
   * @return their ids, the earliest end first
   */
  List<UUID> lockEndedNoLaterThan(Instant cutoff, int limit);

  /**
   * Deletes appointments, in the caller's transaction, once their messages and idempotency keys
   * have gone, so a confirmation never outlives its appointment.
   *
   * @param ids the appointments' ids; may be empty
   * @return how many were deleted
   */
  int deleteAllById(Collection<UUID> ids);
}
