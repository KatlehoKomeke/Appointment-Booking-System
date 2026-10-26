package za.co.fynbos.branchbook.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Holds a transaction of the booking module at a statement a test chooses, before it commits, so
 * the test can look at the database from outside the transaction, or send a second request into it,
 * at a moment it chooses. No test sleeps for this. {@link #hold} holds a booking at step 10, after
 * it has written its appointment and its SMS; {@link #before} holds any write, such as a cancel's
 * UPDATE of the appointment or its INSERT of the cancellation SMS.
 *
 * <p>A temporary trigger makes the chosen statement wait for an advisory lock that this class holds
 * on a connection of its own. The trigger raises that transaction's {@code lock_timeout} to 10
 * seconds first, so only the requests the test sends while it holds the pause are bounded by the
 * usual 2 seconds. The held statement is still bounded by the session's 5-second {@code
 * statement_timeout}, which a trigger cannot extend for the statement it runs in, so a test must
 * release the pause well within that; {@link #awaitWaiting} gives up after 3 seconds. A row trigger
 * fires once the row is locked, so a held UPDATE keeps its row locked while it waits. Closing the
 * pause releases the lock and drops the trigger.
 */
public final class BookingPause implements AutoCloseable {

  /** The advisory lock the paused transaction waits for; any number no application code uses. */
  private static final long LOCK = 4_242_424L;

  /** What a test may name: a statement and a table, which are spliced into the trigger's DDL. */
  private static final Pattern OPERATION = Pattern.compile("INSERT|UPDATE");

  private static final Pattern TABLE = Pattern.compile("[a-z_]+");

  private final JdbcTemplate jdbc;
  private final Connection holder;
  private final String table;
  private boolean held;

  private BookingPause(JdbcTemplate jdbc, Connection holder, String table) {
    this.jdbc = jdbc;
    this.holder = holder;
    this.table = table;
  }

  /**
   * Installs the pause at a booking's step 10, the update of its {@code Idempotency-Key}, and holds
   * it, so the next booking to reach step 10 waits there.
   *
   * @param dataSource where to open the connection that holds the lock
   * @param jdbc where to create and drop the trigger
   * @return the pause, held
   * @throws SQLException if the lock cannot be taken
   */
  public static BookingPause hold(DataSource dataSource, JdbcTemplate jdbc) throws SQLException {
    return before("UPDATE", "idempotency_keys", "true", dataSource, jdbc);
  }

  /**
   * Installs the pause before every row an INSERT or UPDATE of a table writes when a condition
   * holds, and holds it, so the next transaction to write such a row waits there.
   *
   * @param operation {@code INSERT} or {@code UPDATE}
   * @param table the table, for example {@code appointments}
   * @param condition when to pause, over {@code NEW}, for example {@code NEW.kind =
   *     'CANCELLATION'}; {@code true} for every row
   * @param dataSource where to open the connection that holds the lock
   * @param jdbc where to create and drop the trigger
   * @return the pause, held
   * @throws SQLException if the lock cannot be taken
   */
  public static BookingPause before(
      String operation, String table, String condition, DataSource dataSource, JdbcTemplate jdbc)
      throws SQLException {
    requireOperationAndTable(operation, table);
    jdbc.execute(pauseFunction("true", "NEW"));
    jdbc.execute("DROP TRIGGER IF EXISTS test_pause ON " + table);
    jdbc.execute(
        """
        CREATE TRIGGER test_pause BEFORE %s ON %s FOR EACH ROW WHEN (%s)
        EXECUTE FUNCTION test_pause()"""
            .formatted(operation, table, condition));
    return held(dataSource, jdbc, table);
  }

  /**
   * Waits until other sessions are waiting: on this pause, and on row or transaction locks.
   *
   * @param onThePause how many transactions must be held at the pause
   * @param onLocks how many sessions must be waiting for another transaction's lock
   * @throws IllegalStateException if they are not, within three seconds
   */
  public void awaitWaiting(int onThePause, int onLocks) {
    LockWaits.await(jdbc, onThePause, onLocks);
  }

  /**
   * Lets the held transaction continue to its commit.
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
      jdbc.execute("DROP TRIGGER IF EXISTS test_pause ON " + table);
      jdbc.execute("DROP FUNCTION IF EXISTS test_pause()");
    }
  }

  private static void requireOperationAndTable(String operation, String table) {
    if (!OPERATION.matcher(operation).matches() || !TABLE.matcher(table).matches()) {
      throw new IllegalArgumentException("A pause is before an INSERT or UPDATE of a table");
    }
  }

  /**
   * The trigger function: when the condition holds, it waits for the advisory lock, with the
   * transaction's lock timeout raised so the wait is not cut short.
   */
  private static String pauseFunction(String when, String result) {
    return """
        CREATE OR REPLACE FUNCTION test_pause() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          IF %s THEN
            PERFORM set_config('lock_timeout', '10s', true);
            PERFORM pg_advisory_xact_lock(%d);
          END IF;
          RETURN %s;
        END $$"""
        .formatted(when, LOCK, result);
  }

  /** Takes the advisory lock on a connection of the pause's own, so the trigger waits for it. */
  private static BookingPause held(DataSource dataSource, JdbcTemplate jdbc, String table)
      throws SQLException {
    Connection holder = dataSource.getConnection();
    try (Statement statement = holder.createStatement()) {
      statement.execute("SELECT pg_advisory_lock(" + LOCK + ")");
    } catch (SQLException failure) {
      holder.close();
      throw failure;
    }
    BookingPause pause = new BookingPause(jdbc, holder, table);
    pause.held = true;
    return pause;
  }
}
