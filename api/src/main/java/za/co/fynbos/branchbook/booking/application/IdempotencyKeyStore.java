package za.co.fynbos.branchbook.booking.application;

import java.time.Instant;
import java.util.UUID;
import za.co.fynbos.branchbook.booking.domain.IdempotencyKey;

/**
 * Remembers the {@code Idempotency-Key} of every successful booking, so a retry returns the same
 * appointment instead of booking twice (BR-12). Table {@code idempotency_keys}.
 *
 * <p>The key is the first row the booking transaction writes. A second request with the same key
 * waits on that row until the first commits or rolls back, then replays its result or starts
 * afresh. A failed booking rolls its key row back with it, so only successes are remembered.
 */
public interface IdempotencyKeyStore {

  /**
   * Claims a key for a new booking, or reports what it was already used for.
   *
   * @param key the client's key
   * @param requestHash the hash of the booking's details, after normalisation
   * @param now the current instant, from which the key's expiry is counted
   * @return {@link New} if the key is now this request's, {@link Replay} if it already booked an
   *     appointment with the same details, or {@link Reused} if it booked one with other details
   */
  Reservation reserve(IdempotencyKey key, String requestHash, Instant now);

  /**
   * Records the appointment a key booked, in the same transaction as the booking.
   *
   * @param key the key claimed by {@link #reserve}
   * @param appointmentId the appointment it booked
   */
  void complete(IdempotencyKey key, UUID appointmentId);

  /**
   * Deletes the keys that have expired.
   *
   * @param now the current instant
   * @return how many keys were deleted
   */
  int purgeExpired(Instant now);

  /** What {@link #reserve} found. */
  sealed interface Reservation permits New, Replay, Reused {}

  /** The key was free and now belongs to this request. */
  record New() implements Reservation {}

  /**
   * The key already booked an appointment with the same details.
   *
   * @param appointmentId the appointment it booked
   */
  record Replay(UUID appointmentId) implements Reservation {}

  /** The key already booked an appointment with different details. */
  record Reused() implements Reservation {}
}
