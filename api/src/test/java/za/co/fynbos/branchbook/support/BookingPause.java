package za.co.fynbos.branchbook.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Holds a booking at step 10, after it has written its appointment and its SMS but before it
 * commits, so a test can look at the database from outside the transaction, or send a second
 * request into it, at a moment it chooses. No test sleeps for this.
 *
 * <p>A temporary trigger makes the step 10 update of {@code idempotency_keys} wait for an advisory
 * lock that this class holds on a connection of its own. The trigger raises that transaction's
 * {@code lock_timeout} to 10 seconds first, so only the requests the test sends while it holds the
 * pause are bounded by the usual 2 seconds. The held update is still bounded by the session's
 * 5-second {@code statement_timeout}, which a trigger cannot extend for the statement it runs in,
 * so a test must release the pause well within that; {@link #awaitWaiting} gives up after 3
 * seconds. Closing it releases the lock and drops the trigger.
 */
public final class BookingPause implements AutoCloseable {

  /** The advisory lock the paused booking waits for; any number no application code uses. */
  private static final long LOCK = 4_242_424L;

  private final JdbcTemplate jdbc;
  private final Connection holder;
  private boolean held;

  private BookingPause(JdbcTemplate jdbc, Connection holder) {
    this.jdbc = jdbc;
    this.holder = holder;
  }

  /**
   * Installs the pause and holds it, so the next booking to reach step 10 waits there.
   *
   * @param dataSource where to open the connection that holds the lock
   * @param jdbc where to create and drop the trigger
   * @return the pause, held
   * @throws SQLException if the lock cannot be taken
   */
  public static BookingPause hold(DataSource dataSource, JdbcTemplate jdbc) throws SQLException {
    jdbc.execute(
        """
        CREATE OR REPLACE FUNCTION test_pause_key_completion() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          PERFORM set_config('lock_timeout', '10s', true);
          PERFORM pg_advisory_xact_lock(%d);
          RETURN NEW;
        END $$"""
            .formatted(LOCK));
    jdbc.execute("DROP TRIGGER IF EXISTS test_pause_key_completion ON idempotency_keys");
    jdbc.execute(
        """
        CREATE TRIGGER test_pause_key_completion BEFORE UPDATE ON idempotency_keys
        FOR EACH ROW EXECUTE FUNCTION test_pause_key_completion()""");
    Connection holder = dataSource.getConnection();
    try (Statement statement = holder.createStatement()) {
      statement.execute("SELECT pg_advisory_lock(" + LOCK + ")");
    }
    BookingPause pause = new BookingPause(jdbc, holder);
    pause.held = true;
    return pause;
  }

  /**
   * Waits until other sessions are waiting: on this pause, and on row or transaction locks.
   *
   * @param onThePause how many bookings must be held at step 10
   * @param onLocks how many sessions must be waiting for another transaction's lock
   * @throws IllegalStateException if they are not, within three seconds
   */
  public void awaitWaiting(int onThePause, int onLocks) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(3));
    while (Instant.now().isBefore(deadline)) {
      Integer paused = waiting("wait_event = 'advisory'");
      Integer locked = waiting("wait_event IN ('transactionid', 'tuple')");
      if (paused != null && paused >= onThePause && locked != null && locked >= onLocks) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new IllegalStateException("Sessions did not reach the expected waits in time");
  }

  private Integer waiting(String condition) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
            + " AND pid <> pg_backend_pid() AND wait_event_type = 'Lock' AND "
            + condition,
        Integer.class);
  }

  /**
   * Lets the held booking continue to its commit.
   *
   * @throws SQLException if the lock cannot be released
   */
  public void release() throws SQLException {
    if (held) {
      try (Statement statement = holder.createStatement()) {
        statement.execute("SELECT pg_advisory_unlock(" + LOCK + ")");
      }
      held = false;
    }
  }

  @Override
  public void close() throws SQLException {
    try {
      release();
    } finally {
      holder.close();
      jdbc.execute("DROP TRIGGER IF EXISTS test_pause_key_completion ON idempotency_keys");
      jdbc.execute("DROP FUNCTION IF EXISTS test_pause_key_completion()");
    }
  }
}
