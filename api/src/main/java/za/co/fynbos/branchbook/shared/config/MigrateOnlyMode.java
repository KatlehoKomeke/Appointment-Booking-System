package za.co.fynbos.branchbook.shared.config;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

/**
 * The migrate-only mode ({@code branchbook.migrate-only}, {@code BRANCHBOOK_MIGRATE_ONLY=true}):
 * the process runs the Flyway migrations, lets Hibernate validate the schema against the entities,
 * and exits, as a deployment step that runs before the new version's tasks start.
 *
 * <p>Switched on, it overrides, above every other source, the settings that would make the process
 * do anything else: no web server, migrations on whatever {@code BRANCHBOOK_MIGRATE_ON_START} says,
 * and no dispatcher, demo data or retention purge. The scheduler is not started either ({@code
 * SchedulingConfiguration}). {@code BranchBookApplication.main} then closes the context and exits
 * 0; a failed migration or validation fails start-up, and the process exits non-zero.
 */
public final class MigrateOnlyMode implements EnvironmentPostProcessor, Ordered {

  /** The property that switches the mode on. */
  public static final String PROPERTY = "branchbook.migrate-only";

  /** When the mode runs among the post-processors: after Spring Boot reads the configuration. */
  static final int ORDER = Ordered.LOWEST_PRECEDENCE - 10;

  /** The name of the property source that holds the overrides. */
  static final String SOURCE = "branchbookMigrateOnly";

  /** What the mode forces, whatever the environment says. */
  static final Map<String, Object> OVERRIDES =
      Map.of(
          "spring.main.web-application-type", "none",
          "spring.flyway.enabled", "true",
          "branchbook.dispatcher.enabled", "false",
          "branchbook.demo-data.enabled", "false",
          "branchbook.retention.enabled", "false");

  /** Created by Spring Boot from {@code META-INF/spring.factories}. */
  public MigrateOnlyMode() {}

  /**
   * Tells whether an environment asks for the migrate-only mode.
   *
   * @param environment the application's environment
   * @return true if {@code branchbook.migrate-only} is true
   */
  public static boolean isOn(Environment environment) {
    return environment.getProperty(PROPERTY, Boolean.class, false);
  }

  /**
   * Adds the overrides, ahead of every other property source, when the mode is on.
   *
   * @param environment the environment, with the configuration files and variables read
   * @param application the application being started
   */
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (isOn(environment)) {
      environment.getPropertySources().addFirst(new MapPropertySource(SOURCE, OVERRIDES));
    }
  }

  /**
   * Runs after the configuration files have been read, so a value set in any of them is seen, and
   * after {@code ProductionProfileGuard}, which judges the settings as they were given.
   *
   * @return an order after Spring Boot's own configuration processing
   */
  @Override
  public int getOrder() {
    return ORDER;
  }
}
