package za.co.fynbos.branchbook.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * FR-28: the variable compose.yaml sets, {@code BRANCHBOOK_DEMO_DATA_ENABLED}, is the one that
 * switches the demo data on, and compose.yaml does set it. No test starts docker compose, so the
 * binding is proven here: a bare context binds {@link DemoDataProperties} by its own annotation
 * from an environment shaped like a container's, the way Spring Boot reads it at start-up.
 */
class DemoDataPropertiesTest {

  /** The repository's compose file, read from the api folder the build runs in. */
  private static final Path COMPOSE = Path.of("..", "compose.yaml");

  private final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(DemoDataSetting.class);

  @Test
  void composeVariableSwitchesTheDemoDataOn() {
    context
        .withInitializer(
            started ->
                started
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(
                        new SystemEnvironmentPropertySource(
                            "test-systemEnvironment",
                            Map.of("BRANCHBOOK_DEMO_DATA_ENABLED", "true"))))
        .run(started -> assertThat(started.getBean(DemoDataProperties.class).enabled()).isTrue());
  }

  /**
   * compose.yaml, which {@code make up} and CI's stack job start, gives the app service the
   * variable that switches the demo data on: its environment, read from the file, binds as {@code
   * true}.
   */
  @Test
  void composeSwitchesTheDemoDataOnForTheApp() {
    Map<String, Object> environment = composeEnvironment("app");

    context
        .withInitializer(
            started ->
                started
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(new SystemEnvironmentPropertySource("compose", environment)))
        .run(started -> assertThat(started.getBean(DemoDataProperties.class).enabled()).isTrue());
  }

  @Test
  void demoDataIsOffWithoutTheVariable() {
    context.run(
        started -> assertThat(started.getBean(DemoDataProperties.class).enabled()).isFalse());
  }

  /** The environment compose.yaml gives one service, as the variables its container sees. */
  private static Map<String, Object> composeEnvironment(String service) {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new FileSystemResource(COMPOSE));
    Properties compose = Objects.requireNonNull(yaml.getObject());
    String prefix = "services." + service + ".environment.";
    Map<String, Object> environment = new HashMap<>();
    compose.stringPropertyNames().stream()
        .filter(name -> name.startsWith(prefix))
        .forEach(name -> environment.put(name.substring(prefix.length()), compose.get(name)));
    assertThat(environment).as("the %s service's environment in %s", service, COMPOSE).isNotEmpty();
    return environment;
  }

  /** Registers the setting alone, as the application's properties scan does. */
  @EnableConfigurationProperties(DemoDataProperties.class)
  static class DemoDataSetting {}
}
