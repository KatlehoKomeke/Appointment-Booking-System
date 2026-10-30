package za.co.fynbos.branchbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import za.co.fynbos.branchbook.support.IntegrationTest;

/**
 * V9: the runtime role that staging and production's tasks connect as, {@code branchbook_app}, may
 * read and write every application table and nothing more. It cannot change the schema, and it
 * cannot read or rewrite Flyway's record of which migrations ran, so a compromised task can do no
 * more than the application itself does.
 *
 * <p>In AWS the role signs in with IAM database authentication and has no password. Here, in a
 * throwaway container, the test gives it one so it can connect as the role.
 */
@IntegrationTest
class RuntimeRoleIT {

  private static final String ROLE = "branchbook_app";

  /** A throwaway password for the container's copy of the role; the real role has none. */
  private static final String PASSWORD = "runtime-role-test-only";

  /** PostgreSQL's code for a privilege the role lacks, or an object it does not own. */
  private static final String INSUFFICIENT_PRIVILEGE = "42501";

  @Autowired private JdbcTemplate jdbc;

  @Autowired private PostgreSQLContainer postgres;

  @BeforeEach
  void letTheTestSignInAsTheRole() {
    jdbc.execute("ALTER ROLE " + ROLE + " PASSWORD '" + PASSWORD + "'");
  }

  @Test
  void theRoleExistsAndMayLogIn() {
    assertThat(
            jdbc.queryForObject(
                "SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole"
                    + " FROM pg_roles WHERE rolname = ?",
                Boolean.class,
                ROLE))
        .isTrue();
  }

  /** Every table but Flyway's history: read, insert, update and delete. */
  @Test
  void theRoleMayReadAndWriteEveryApplicationTable() {
    List<String> tables =
        jdbc.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public'"
                + " AND tablename <> 'flyway_schema_history' ORDER BY tablename",
            String.class);

    assertThat(tables).contains("appointments", "notification_messages", "lookup_attempts");
    for (String table : tables) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT has_table_privilege(?, ?, 'SELECT, INSERT, UPDATE, DELETE')",
                  Boolean.class,
                  ROLE,
                  "public." + table))
          .as("%s may read and write %s", ROLE, table)
          .isTrue();
    }
  }

  /** Signed in as the role, a row is written, changed, read and deleted. */
  @Test
  void signedInAsTheRoleTheApplicationsWritesWork() throws SQLException {
    try (Connection connection = connectAsTheRole();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO lookup_attempts (reference, opens_at, closes_at, held_until, failures,"
              + " in_flight) VALUES ('APT-RQ2X-TEST', now(), now() + interval '1 hour',"
              + " now() + interval '30 seconds', 0, 1)");
      assertThat(
              statement.executeUpdate(
                  "UPDATE lookup_attempts SET failures = 1 WHERE reference = 'APT-RQ2X-TEST'"))
          .isOne();
      try (var rows = statement.executeQuery("SELECT count(*) FROM branches")) {
        rows.next();
        assertThat(rows.getInt(1)).isPositive();
      }
      assertThat(
              statement.executeUpdate(
                  "DELETE FROM lookup_attempts WHERE reference = 'APT-RQ2X-TEST'"))
          .isOne();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CREATE TABLE intruder (id int)",
        "ALTER TABLE appointments ADD COLUMN intruder int",
        "DROP TABLE lookup_attempts",
        "TRUNCATE lookup_attempts",
        "CREATE INDEX ix_intruder ON appointments (created_at)",
        "ALTER TABLE appointments DROP CONSTRAINT appointments_reference_format"
      })
  void theRoleCannotChangeTheSchema(String ddl) {
    assertThatExceptionOfType(SQLException.class)
        .isThrownBy(() -> runAsTheRole(ddl))
        .extracting(SQLException::getSQLState)
        .isEqualTo(INSUFFICIENT_PRIVILEGE);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT * FROM flyway_schema_history",
        "DELETE FROM flyway_schema_history",
        "UPDATE flyway_schema_history SET checksum = 0",
        "INSERT INTO flyway_schema_history (installed_rank, description, type, script,"
            + " installed_by, execution_time, success) VALUES (999, 'x', 'SQL', 'x', 'x', 0, true)"
      })
  void theRoleCannotTouchFlywaysHistory(String sql) {
    assertThatExceptionOfType(SQLException.class)
        .isThrownBy(() -> runAsTheRole(sql))
        .extracting(SQLException::getSQLState)
        .isEqualTo(INSUFFICIENT_PRIVILEGE);
  }

  private void runAsTheRole(String sql) throws SQLException {
    try (Connection connection = connectAsTheRole();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private Connection connectAsTheRole() throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), ROLE, PASSWORD);
  }
}
