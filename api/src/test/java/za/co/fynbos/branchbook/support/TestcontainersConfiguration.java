package za.co.fynbos.branchbook.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Gives integration tests, and {@code TestBranchBookApplication}, a real PostgreSQL in Docker.
 *
 * <p>{@code @ServiceConnection} points the application's datasource at the container, so no
 * connection settings are needed. The image and the logging flags are the ones compose.yaml uses,
 * so tests run against the same database, configured the same way, as the application.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  /** Kept in step with the {@code db} service in compose.yaml. */
  static final String POSTGRES_IMAGE = "postgres:17.11-alpine";

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
        // As in compose.yaml: constraint violations are logged without the values they quote.
        .withCommand(
            "postgres",
            "-c",
            "log_error_verbosity=terse",
            "-c",
            "log_min_error_statement=panic",
            // A throwaway database needs no crash safety; skipping it makes tests faster.
            "-c",
            "fsync=off");
  }
}
