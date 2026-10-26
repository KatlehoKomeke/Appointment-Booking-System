package za.co.fynbos.branchbook;

import org.springframework.boot.SpringApplication;
import za.co.fynbos.branchbook.support.TestcontainersConfiguration;

/**
 * Runs the application on a developer's machine against a throwaway PostgreSQL in Docker, with no
 * database to install or configure: {@code ./mvnw spring-boot:test-run} (or {@code make dev-api}).
 */
public class TestBranchBookApplication {

  /**
   * Starts the application with {@link TestcontainersConfiguration} supplying the database, and the
   * simulated inbox switched on, as docker compose has it.
   *
   * @param args command-line arguments, passed through to Spring Boot after the inbox switch
   */
  public static void main(String[] args) {
    String[] withInbox = new String[args.length + 1];
    withInbox[0] = "--branchbook.inbox.enabled=true";
    System.arraycopy(args, 0, withInbox, 1, args.length);
    SpringApplication.from(BranchBookApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(withInbox);
  }
}
