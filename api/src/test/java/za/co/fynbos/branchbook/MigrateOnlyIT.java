package za.co.fynbos.branchbook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Migrations are a deployment step: {@code BRANCHBOOK_MIGRATE_ONLY=true} runs Flyway, lets
 * Hibernate validate the schema, starts no web server, scheduler, dispatcher or demo data, and
 * exits 0; a failure exits non-zero, so the deployment stops before the new version starts.
 *
 * <p>The exit codes are proven on a real process, started the way the migration task starts the
 * image's {@code java -jar}, against a database of its own in the test's PostgreSQL container. The
 * in-process run proves what the mode leaves out.
 */
class MigrateOnlyIT {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"));

  @TempDir private Path logs;

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void aMigrateOnlyRunStartsNothingButTheMigration() throws SQLException {
    String url = database("migrate_only_in_process");
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(BranchBookApplication.class)
            .run(
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--branchbook.migrate-only=true",
                "--branchbook.demo-data.enabled=true",
                "--branchbook.dispatcher.enabled=true");
    try {
      assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
      assertThat(context.containsBean("dispatcherLoop")).isFalse();
      assertThat(context.getBeanProvider(ScheduledTaskHolder.class).stream()).isEmpty();
      // Asked for here, and overruled by the mode.
      assertThat(context.getEnvironment().getProperty("branchbook.demo-data.enabled"))
          .isEqualTo("false");
      assertThat(context.getEnvironment().getProperty("branchbook.dispatcher.enabled"))
          .isEqualTo("false");
      assertThat(appliedVersions(url)).contains("9");
      assertThat(count(url, "SELECT count(*) FROM appointments")).isZero();
    } finally {
      assertThat(SpringApplication.exit(context)).isZero();
    }
  }

  @Test
  void aMigrateOnlyProcessMigratesAndExitsZero() throws Exception {
    String url = database("migrate_only_process");

    Run run = runMigrateOnly(url, POSTGRES.getPassword());

    assertThat(run.exitCode()).as(run.output()).isZero();
    assertThat(run.output()).doesNotContain("Tomcat started");
    assertThat(appliedVersions(url)).contains("1", "8", "9");
  }

  @Test
  void aMigrateOnlyProcessThatCannotMigrateExitsNonZero() throws Exception {
    String url = database("migrate_only_refused");

    Run run = runMigrateOnly(url, "not-the-password");

    assertThat(run.exitCode()).as(run.output()).isNotZero();
    assertThat(appliedVersions(url)).isEmpty();
  }

  /** What a finished process returned and printed. */
  private record Run(int exitCode, String output) {}

  /** Starts the application as the migration task does, and waits for it to exit. */
  private Run runMigrateOnly(String url, String password) throws IOException, InterruptedException {
    Path log = logs.resolve("migrate-only-" + System.nanoTime() + ".log");
    String java = ProcessHandle.current().info().command().orElse("java");
    ProcessBuilder builder =
        new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                BranchBookApplication.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    builder.environment().put("DB_URL", url);
    builder.environment().put("DB_USER", POSTGRES.getUsername());
    builder.environment().put("DB_PASSWORD", password);
    builder.environment().put("BRANCHBOOK_MIGRATE_ONLY", "true");
    Process process = builder.start();
    if (!process.waitFor(3, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new AssertionError("The migrate-only run did not exit:\n" + Files.readString(log));
    }
    return new Run(process.exitValue(), Files.readString(log));
  }

  /** Creates an empty database in the container and returns its URL. */
  private static String database(String name) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    }
    return POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + name);
  }

  private static List<String> appliedVersions(String url) throws SQLException {
    List<String> versions = new ArrayList<>();
    try (Connection connection =
            DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement();
        ResultSet tables =
            statement.executeQuery(
                "SELECT count(*) FROM pg_tables WHERE tablename = 'flyway_schema_history'")) {
      tables.next();
      if (tables.getInt(1) == 0) {
        return versions;
      }
    }
    try (Connection connection =
            DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")) {
      while (rows.next()) {
        versions.add(rows.getString(1));
      }
    }
    return versions;
  }

  private static int count(String url, String sql) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getInt(1);
    }
  }
}
