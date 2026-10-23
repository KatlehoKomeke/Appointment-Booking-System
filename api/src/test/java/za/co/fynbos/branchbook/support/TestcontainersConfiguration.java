package za.co.fynbos.branchbook;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Gives integration tests, and {@link TestBranchBookApplication}, a real PostgreSQL in Docker.
 *
 * <p>{@code @ServiceConnection} points the application's datasource at the container, so no
 * connection settings are needed. The image is the one compose.yaml runs, so tests exercise the
 * same database version as the application.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  /** Kept in step with the {@code db} service in compose.yaml. */
  static final String POSTGRES_IMAGE = "postgres:17.11-alpine";

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE));
  }
}
