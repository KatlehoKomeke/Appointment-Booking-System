package za.co.fynbos.branchbook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Starts BranchBook: the booking API, the confirmation dispatcher and the React app, in one
 * process.
 *
 * <p>{@link UserDetailsServiceAutoConfiguration} is excluded because BranchBook has no user
 * accounts (docs/adr/0004-restraint-no-login-no-id-number.md). Left in place, Spring Boot would
 * create a default user and print its generated password in the startup log, and no password may
 * ever appear in a log.
 *
 * <p>Typed settings (records annotated {@code @ConfigurationProperties}, in {@code shared.config})
 * are found by scanning, so adding one needs no registration here.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class BranchBookApplication {

  /** Called by Spring when it builds the application context; never by application code. */
  BranchBookApplication() {}

  /**
   * Starts the application and blocks until it shuts down.
   *
   * @param args command-line arguments; Spring Boot reads {@code --name=value} pairs as properties
   */
  public static void main(String[] args) {
    SpringApplication.run(BranchBookApplication.class, args);
  }
}
