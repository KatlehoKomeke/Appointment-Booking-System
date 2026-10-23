package za.co.fynbos.branchbook;

import org.springframework.boot.SpringApplication;

/**
 * Runs the application on a developer's machine against a throwaway PostgreSQL in Docker, with no
 * database to install or configure: {@code ./mvnw spring-boot:test-run} (or {@code make dev-api}).
 */
public class TestBranchBookApplication {

  /**
   * Starts the application with {@link TestcontainersConfiguration} supplying the database.
   *
   * @param args command-line arguments, passed through to Spring Boot
   */
  public static void main(String[] args) {
    SpringApplication.from(BranchBookApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
