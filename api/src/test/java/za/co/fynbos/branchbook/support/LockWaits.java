package za.co.fynbos.branchbook.support;

import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Waits, without sleeping, until other database sessions are waiting for locks, so a concurrency
 * test acts only once the interleaving it wants is really in place. PostgreSQL's own view of who
 * waits for what, {@code pg_stat_activity}, is the evidence.
 */
public final class LockWaits {

  /** How long the expected waits may take to appear. */
  private static final Duration PATIENCE = Duration.ofSeconds(3);

  private LockWaits() {}

  /**
   * Waits until enough sessions wait on advisory locks, such as a {@link BookingPause}, and on row
   * or transaction locks, such as an UPDATE of a row another transaction has locked.
   *
   * @param jdbc a connection pool other than the sessions being watched
   * @param onAdvisoryLocks how many sessions must be waiting for an advisory lock
   * @param onRowLocks how many sessions must be waiting for another transaction's row or
   *     transaction lock
   * @throws IllegalStateException if they are not, within three seconds
   */
  public static void await(JdbcTemplate jdbc, int onAdvisoryLocks, int onRowLocks) {
    Instant deadline = Instant.now().plus(PATIENCE);
    while (Instant.now().isBefore(deadline)) {
      Integer advisory = waiting(jdbc, "wait_event = 'advisory'");
      Integer rows = waiting(jdbc, "wait_event IN ('transactionid', 'tuple')");
      if (advisory != null && advisory >= onAdvisoryLocks && rows != null && rows >= onRowLocks) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new IllegalStateException("Sessions did not reach the expected waits in time");
  }

  private static Integer waiting(JdbcTemplate jdbc, String condition) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
            + " AND pid <> pg_backend_pid() AND wait_event_type = 'Lock' AND "
            + condition,
        Integer.class);
  }
}
