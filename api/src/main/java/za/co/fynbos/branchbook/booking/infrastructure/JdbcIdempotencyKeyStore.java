package za.co.fynbos.branchbook.booking.infrastructure;

import static za.co.fynbos.branchbook.booking.infrastructure.ConstraintViolationTranslator.translated;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * <p>The claim is {@code INSERT ... ON CONFLICT DO NOTHING}. When another transaction holds the
 * same key uncommitted, the insert waits for it, bounded by the session's {@code lock_timeout};
 * once it has committed, the key's row says what it booked, and once it has rolled back, the key is
 * free. Keys are honoured for {@link #KEY_TTL} and until the hourly purge removes them.
 */
@Repository
class JdbcIdempotencyKeyStore implements IdempotencyKeyStore {

  /** How long a key is honoured after its booking; the hourly purge removes it within the hour. */
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
                        ON CONFLICT (key) DO NOTHING
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
                        "SELECT request_hash, appointment_id FROM idempotency_keys WHERE key = :key")
                    .param("key", key.value())
                    .query(
                        (rs, row) ->
                            new Used(
                                rs.getString("request_hash"),
                                rs.getObject("appointment_id", UUID.class)))
                    .optional());
    // A row vanishes only if the purge removed it between the two statements, and an uncompleted
    // row is never committed; both are a race the re-run resolves.
    if (used.isEmpty() || used.get().appointmentId() == null) {
      throw new TransactionConflictException("40001");
    }
    UUID appointmentId = used.get().appointmentId();
    return used.get().requestHash().equals(requestHash) ? new Replay(appointmentId) : new Reused();
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
    return jdbc.sql("DELETE FROM idempotency_keys WHERE expires_at < :now")
        .param("now", utc(now))
        .update();
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  /** What a key was used for. */
  private record Used(String requestHash, @Nullable UUID appointmentId) {}
}
