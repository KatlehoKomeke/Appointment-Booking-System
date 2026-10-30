package za.co.fynbos.branchbook.shared.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Refuses to start under the {@code prod} profile, which staging and production run with, with a
 * combination of settings that would be unsafe in public. application-prod.yml sets the safe value
 * of each, but an environment variable outranks it, so a task definition could still undo one; this
 * guard turns that mistake into a failed deployment instead of an exposed one. It refuses:
 *
 * <ul>
 *   <li>the simulated inbox without redacted references, which would list other visitors'
 *       references beside guessable demo numbers (BR-19);
 *   <li>demo data, which would book fixtures into a real database;
 *   <li>a public base URL that is not {@code https}, which every SMS link would carry;
 *   <li>a per-recipient SMS cap without its key, whose counts would then reset at every restart and
 *       differ between instances (BR-17);
 *   <li>the actuator on the main port, which the load balancer routes to the internet.
 * </ul>
 *
 * <p>It lists every problem at once, naming the settings and never their values, since one of them
 * is a secret. It runs before anything is created, so nothing has connected to the database or
 * opened a port when it refuses. A migrate-only run serves nothing and sends nothing, so only the
 * first two checks apply to it: the migration task needs no public address, cap key or management
 * port. It runs before {@link MigrateOnlyMode}'s overrides, so it sees demo data switched on as the
 * task was configured, not as the mode forces it.
 */
public final class ProductionProfileGuard implements EnvironmentPostProcessor, Ordered {

  /** The profile staging and production run with. */
  public static final String PROFILE = "prod";

  /** Created by Spring Boot from {@code META-INF/spring.factories}. */
  public ProductionProfileGuard() {}

  /**
   * Fails start-up if the {@code prod} profile is active with an unsafe combination.
   *
   * @param environment the environment, with the configuration files and variables read
   * @param application the application being started
   * @throws IllegalStateException naming every unsafe setting
   */
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!environment.acceptsProfiles(Profiles.of(PROFILE))) {
      return;
    }
    List<String> problems = problems(environment);
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "The prod profile refuses to start: " + String.join("; ", problems));
    }
  }

  /**
   * Lists what is unsafe about an environment's settings for a public deployment, or, for a
   * migrate-only run, about the settings that apply to one.
   *
   * @param environment the settings to check
   * @return one sentence per problem, naming the setting and never its value; empty if none
   */
  public static List<String> problems(Environment environment) {
    List<String> problems = new ArrayList<>();
    if (flag(environment, "branchbook.inbox.enabled")
        && !flag(environment, "branchbook.inbox.redact-references")) {
      problems.add(
          "the inbox (BRANCHBOOK_INBOX_ENABLED) is on without BRANCHBOOK_INBOX_REDACT_REFERENCES");
    }
    if (flag(environment, "branchbook.demo-data.enabled")) {
      problems.add("demo data (BRANCHBOOK_DEMO_DATA_ENABLED) is on");
    }
    if (MigrateOnlyMode.isOn(environment)) {
      return problems;
    }
    String baseUrl = environment.getProperty("branchbook.notification.public-base-url", "");
    if (!baseUrl.startsWith("https://")) {
      problems.add("the public base URL (PUBLIC_BASE_URL) is not an https address");
    }
    int cap = environment.getProperty("branchbook.sms.recipient-daily-cap", Integer.class, 0);
    String capKey = environment.getProperty("branchbook.sms.recipient-cap-key", "");
    if (cap > 0 && capKey.isBlank()) {
      problems.add(
          "the SMS cap (BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP) is on without its key"
              + " (BRANCHBOOK_SMS_RECIPIENT_CAP_KEY)");
    }
    if (actuatorSharesTheMainPort(environment)) {
      problems.add(
          "the actuator would share the public port (MANAGEMENT_SERVER_PORT is unset or equals"
              + " the server port)");
    }
    return problems;
  }

  /**
   * Decides as Spring Boot does whether the actuator is served on the main port: no management
   * port, or the same one as the server's. Zero means a random port of its own, and a negative one
   * switches the actuator's HTTP endpoints off, both of which are safe.
   */
  private static boolean actuatorSharesTheMainPort(Environment environment) {
    Integer management = environment.getProperty("management.server.port", Integer.class);
    Integer server = environment.getProperty("server.port", Integer.class, 8080);
    return management == null || (management != 0 && Objects.equals(management, server));
  }

  private static boolean flag(Environment environment, String name) {
    return environment.getProperty(name, Boolean.class, false);
  }

  /**
   * Runs after the configuration files are read and before the migrate-only mode's overrides.
   *
   * @return an order before {@link MigrateOnlyMode}'s
   */
  @Override
  public int getOrder() {
    return MigrateOnlyMode.ORDER - 5;
  }
}
