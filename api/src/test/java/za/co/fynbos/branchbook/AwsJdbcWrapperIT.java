package za.co.fynbos.branchbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * In AWS the application connects through the AWS Advanced JDBC Wrapper, {@code
 * jdbc:aws-wrapper:postgresql://...} with {@code software.amazon.jdbc.Driver}, whose {@code iam}
 * plugin signs in with IAM database authentication. This proves the wrapper itself on a real
 * PostgreSQL: Spring Boot's Hikari pool connects through it, Flyway migrates through it, and the
 * session settings that keep personal data out of logs and bound every lock and statement ({@code
 * logServerErrorDetail=false}, {@code lock_timeout}, {@code statement_timeout}) still reach
 * PostgreSQL's driver and the server.
 *
 * <p>The {@code iam} plugin is left out ({@code wrapperPlugins=} empty): it asks RDS for a token,
 * which only an AWS account and an RDS instance can give, so it is exercised in staging, not here.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.driver-class-name=software.amazon.jdbc.Driver",
      "branchbook.dispatcher.enabled=false",
      "branchbook.inbox.enabled=false",
      "branchbook.demo-data.enabled=false",
      "branchbook.sms.gateway=simulated",
      "branchbook.sms.recipient-daily-cap=0"
    })
@Testcontainers
class AwsJdbcWrapperIT {

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"));

  @Autowired private DataSource dataSource;

  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void connectThroughTheWrapper(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () ->
            "jdbc:aws-wrapper:"
                + POSTGRES.getJdbcUrl().substring("jdbc:".length())
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "wrapperPlugins=");
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Test
  void hikariConnectsThroughTheWrappersDriver() throws SQLException {
    assertThat(dataSource).isInstanceOf(HikariDataSource.class);
    HikariDataSource hikari = (HikariDataSource) dataSource;
    assertThat(hikari.getDriverClassName()).isEqualTo("software.amazon.jdbc.Driver");
    assertThat(hikari.getJdbcUrl()).startsWith("jdbc:aws-wrapper:postgresql://");

    try (Connection connection = dataSource.getConnection()) {
      assertThat(connection.isWrapperFor(PGConnection.class)).isTrue();
      assertThat(connection.unwrap(Connection.class).getClass().getName())
          .startsWith("software.amazon.jdbc.");
    }
  }

  @Test
  void flywayMigratesThroughTheWrapper() {
    assertThat(
            jdbc.queryForObject(
                "SELECT max(installed_rank) FROM flyway_schema_history WHERE success",
                Integer.class))
        .isGreaterThanOrEqualTo(9);
  }

  /** The same bounds as with PostgreSQL's own driver (ApplicationStartsIT). */
  @Test
  void theLockAndStatementTimeoutsReachTheServer() {
    assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("2s");
    assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("5s");
  }

  /** BR-19: a constraint violation's message still leaves out the values the server quotes. */
  @Test
  void aConstraintViolationStillQuotesNoValue() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE TEMPORARY TABLE wrapper_detail (mobile text UNIQUE)");
      statement.execute("INSERT INTO wrapper_detail VALUES ('+27825550142')");

      assertThatExceptionOfType(SQLException.class)
          .isThrownBy(() -> statement.execute("INSERT INTO wrapper_detail VALUES ('+27825550142')"))
          .satisfies(
              violation -> {
                assertThat(violation.getSQLState()).isEqualTo("23505");
                assertThat(violation.getMessage()).doesNotContain("+27825550142");
              });
    }
  }
}
