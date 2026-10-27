package za.co.fynbos.branchbook.booking.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import za.co.fynbos.branchbook.booking.application.LookupAttemptStore;
import za.co.fynbos.branchbook.booking.application.Window;

/**
 * Keeps the lookup limiter's counts in {@code lookup_attempts} (BR-13, V8).
 *
 * <p>{@link #lockWindow} is a {@code SELECT ... FOR UPDATE}: a second count of the same reference,
 * from this instance or another, waits for the first to commit, bounded by the session's {@code
 * lock_timeout}, and then reads what it wrote. {@link #insert} is {@code INSERT ... ON CONFLICT DO
 * NOTHING}: when two first lookups of one reference find no row at once, the second's insert waits
 * for the first's commit and then inserts nothing, which it reports. Every instant is bound from
 * the caller, never read from the database's clock (BR-20).
 *
 * <p>Nothing here goes through {@link ConstraintViolationTranslator}: no constraint of this table
 * maps to a problem code, and the translator would turn a deadlock or serialization failure (40P01,
 * 40001) into a {@code TransactionConflictException}, which only the booking path re-runs and the
 * lookup path would answer as {@code 500}. Left as Spring reports it, the global handler answers
 * {@code 503 TRY_AGAIN}, as for any other endpoint.
 */
@Repository
class JdbcLookupAttemptStore implements LookupAttemptStore {

  private final JdbcClient jdbc;

  JdbcLookupAttemptStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Window> lockWindow(String reference) {
    return jdbc.sql(
            """
            SELECT reference, opens_at, closes_at, held_until, failures, in_flight
            FROM lookup_attempts WHERE reference = :reference FOR UPDATE""")
        .param("reference", reference)
        .query(JdbcLookupAttemptStore::window)
        .optional();
  }

  @Override
  public boolean insert(Window window) {
    int inserted =
        jdbc.sql(
                """
                INSERT INTO lookup_attempts
                    (reference, opens_at, closes_at, held_until, failures, in_flight)
                VALUES (:reference, :opensAt, :closesAt, :heldUntil, :failures, :inFlight)
                ON CONFLICT (reference) DO NOTHING""")
            .param("reference", window.reference())
            .param("opensAt", utc(window.opensAt()))
            .param("closesAt", utc(window.closesAt()))
            .param("heldUntil", utc(window.heldUntil()))
            .param("failures", window.failures())
            .param("inFlight", window.inFlight())
            .update();
    return inserted == 1;
  }

  @Override
  public void update(Window window) {
    int updated =
        jdbc.sql(
                """
                UPDATE lookup_attempts
                   SET opens_at = :opensAt, closes_at = :closesAt, held_until = :heldUntil,
                       failures = :failures, in_flight = :inFlight
                 WHERE reference = :reference""")
            .param("opensAt", utc(window.opensAt()))
            .param("closesAt", utc(window.closesAt()))
            .param("heldUntil", utc(window.heldUntil()))
            .param("failures", window.failures())
            .param("inFlight", window.inFlight())
            .param("reference", window.reference())
            .update();
    if (updated != 1) {
      throw new IllegalStateException("The reference's window was not locked");
    }
  }

  @Override
  public void delete(String reference) {
    jdbc.sql("DELETE FROM lookup_attempts WHERE reference = :reference")
        .param("reference", reference)
        .update();
  }

  @Override
  public int purgeClosed(Instant now) {
    return jdbc.sql("DELETE FROM lookup_attempts WHERE closes_at <= :now")
        .param("now", utc(now))
        .update();
  }

  private static Window window(ResultSet row, int index) throws SQLException {
    return new Window(
        row.getString("reference"),
        instant(row, "opens_at"),
        instant(row, "closes_at"),
        instant(row, "held_until"),
        row.getInt("failures"),
        row.getInt("in_flight"));
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
