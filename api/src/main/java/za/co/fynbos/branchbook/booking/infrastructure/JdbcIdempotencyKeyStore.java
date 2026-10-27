package za.co.fynbos.branchbook.booking.infrastructure;

import static za.co.fynbos.branchbook.booking.infrastructure.ConstraintViolationTranslator.translated;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import za.co.fynbos.branchbook.booking.application.IdempotencyKeyStore;
import za.co.fynbos.branchbook.booking.application.TransactionConflictException;
import za.co.fynbos.branchbook.booking.domain.IdempotencyKey;

/**
 * Stores the keys of successful bookings in {@code idempotency_keys} (BR-12).
 *
 * <p>The claim is one {@code INSERT ... ON CONFLICT} statement: it takes a new key, and takes over
 * a key whose {@link #KEY_TTL} has run out, so a key is honoured for exactly that long whether or
 * not the hourly purge has removed it yet. When another transaction holds the same key uncommitted,
 * the insert waits for it, bounded by the session's {@code lock_timeout}; once it has committed,
 * the key's row says what it booked, and once it has rolled back, the key is free. A completed key
 * whose appointment a cancel has deleted keeps {@code response_status} 201 with no appointment
 * (V6), which says the booking it made was cancelled.
 */
@Repository
class JdbcIdempotencyKeyStore implements IdempotencyKeyStore {

  /** How long a key is honoured after its booking; from then on it books anew. */
  static final Duration KEY_TTL = Duration.ofHours(24);

  private final JdbcClient jdbc;

  JdbcIdempotencyKeyStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Reservation reserve(IdempotencyKey key, String requestHash, Instant now) {
    Optional<UUID> claimed =
        translated(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO idempotency_keys (key, request_hash, created_at, expires_at)
                        VALUES (:key, :requestHash, :createdAt, :expiresAt)
                        ON CONFLICT (key) DO UPDATE
                           SET request_hash = EXCLUDED.request_hash, appointment_id = NULL,
                               response_status = NULL, created_at = EXCLUDED.created_at,
                               expires_at = EXCLUDED.expires_at
                         WHERE idempotency_keys.expires_at <= EXCLUDED.created_at
                        RETURNING key""")
                    .param("key", key.value())
                    .param("requestHash", requestHash)
                    .param("createdAt", utc(now))
                    .param("expiresAt", utc(now.plus(KEY_TTL)))
                    .query(UUID.class)
                    .optional());
    if (claimed.isPresent()) {
      return new New();
    }
    Optional<Used> used =
        translated(
            () ->
                jdbc.sql(
                        """
                        SELECT request_hash, appointment_id, response_status FROM idempotency_keys
                        WHERE key = :key""")
                    .param("key", key.value())
                    .query(
                        (rs, row) ->
                            new Used(
                                rs.getString("request_hash"),
                                rs.getObject("appointment_id", UUID.class),
                                rs.getObject("response_status") != null))
                    .optional());
    // The key is in force here: an expired one was taken over above. ON CONFLICT DO UPDATE locks
    // the conflicting row even when its WHERE is false, so the purge cannot remove it before this
    // read, and only a completed row is ever committed. The check below is a guard for what should
    // never happen; the re-run would resolve it.
    if (used.isEmpty() || !used.get().completed()) {
      throw new TransactionConflictException("40001");
    }
    if (!used.get().requestHash().equals(requestHash)) {
      return new Reused();
    }
    UUID appointmentId = used.get().appointmentId();
    return appointmentId == null ? new Cancelled() : new Replay(appointmentId);
  }

  @Override
  public void complete(IdempotencyKey key, UUID appointmentId) {
    int updated =
        translated(
            () ->
                jdbc.sql(
                        """
                        UPDATE idempotency_keys SET appointment_id = :appointmentId, response_status = 201
                        WHERE key = :key""")
                    .param("appointmentId", appointmentId)
                    .param("key", key.value())
                    .update());
    if (updated != 1) {
      throw new IllegalStateException("The booking's idempotency key was not claimed");
    }
  }

  @Override
  public int purgeExpired(Instant now) {
    return jdbc.sql("DELETE FROM idempotency_keys WHERE expires_at <= :now")
        .param("now", utc(now))
        .update();
  }

  @Override
  public int deleteForAppointments(Collection<UUID> appointmentIds) {
    if (appointmentIds.isEmpty()) {
      return 0;
    }
    return jdbc.sql("DELETE FROM idempotency_keys WHERE appointment_id IN (:appointmentIds)")
        .param("appointmentIds", appointmentIds)
        .update();
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  /**
   * What a key was used for: a completed key with no appointment booked one that a cancel deleted.
   */
  private record Used(String requestHash, @Nullable UUID appointmentId, boolean completed) {}
}
