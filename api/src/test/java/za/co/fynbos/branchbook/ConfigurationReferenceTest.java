package za.co.fynbos.branchbook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;
import za.co.fynbos.branchbook.shared.config.BookingProperties;
import za.co.fynbos.branchbook.support.BookingRules;

/**
 * Every setting is documented: each property in {@code application*.yml} appears in
 * docs/configuration.md together with the environment variable that sets it, so no value can be
 * added that an operator cannot find. The variable is the one its {@code ${NAME:default}}
 * placeholder names, or, for a value that is a design decision rather than a setting, the name
 * Spring Boot's relaxed binding gives the property.
 *
 * <p>It also keeps {@link BookingRules}, the settings unit tests build handlers with, equal to what
 * application.yml defaults to, so a unit test never proves a rule under other values. No Spring
 * context and no database: the files are read as they are.
 */
class ConfigurationReferenceTest {

  /** The reference, relative to the api module, where Maven runs the tests. */
  private static final Path REFERENCE = Path.of("..", "docs", "configuration.md");

  private static final Path RESOURCES = Path.of("src", "main", "resources");

  /** A placeholder's variable: {@code ${NAME:default}} or {@code ${NAME}}. */
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)[:}]");

  /**
   * One property of one configuration file.
   *
   * @param file the file's name
   * @param name the property, such as {@code branchbook.booking.lead-time}
   * @param value its value as written, placeholders unresolved
   */
  record Property(String file, String name, String value) {

    /** The variables that set it: its placeholders', or else its relaxed-binding name. */
    List<String> variables() {
      List<String> named = new ArrayList<>();
      Matcher matcher = PLACEHOLDER.matcher(value);
      while (matcher.find()) {
        named.add(matcher.group(1));
      }
      if (named.isEmpty()) {
        named.add(name.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
      }
      return named;
    }

    @Override
    public String toString() {
      return file + ": " + name;
    }
  }

  static Stream<Property> properties() throws IOException {
    List<Property> all = new ArrayList<>();
    try (Stream<Path> files = Files.list(RESOURCES)) {
      for (Path file :
          files
              .filter(path -> path.getFileName().toString().matches("application.*\\.ya?ml"))
              .sorted()
              .toList()) {
        for (PropertySource<?> source : load(file)) {
          EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) source;
          for (String name : enumerable.getPropertyNames()) {
            all.add(
                new Property(
                    file.getFileName().toString(),
                    name,
                    String.valueOf(enumerable.getProperty(name))));
          }
        }
      }
    }
    assertThat(all).as("properties found in %s", RESOURCES).hasSizeGreaterThan(50);
    return all.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("properties")
  void everyPropertyIsDocumentedWithItsEnvironmentVariable(Property property) {
    String reference = reference();

    assertThat(reference)
        .as("docs/configuration.md documents %s", property.name())
        .contains("`" + property.name() + "`");
    for (String variable : property.variables()) {
      assertThat(reference)
          .as("docs/configuration.md names %s, which sets %s", variable, property.name())
          .contains("`" + variable + "`");
    }
  }

  @Test
  void theUnitTestsBookingRulesAreTheApplicationsDefaults() throws IOException {
    BookingProperties defaults =
        binder(RESOURCES.resolve("application.yml"))
            .bind("branchbook.booking", BookingProperties.class)
            .get();

    assertThat(BookingRules.defaults()).isEqualTo(defaults);
  }

  /** Staging and production move the actuator off the public port unless told otherwise. */
  @Test
  void theProdProfileMovesTheActuatorToPort8081() throws IOException {
    assertThat(
            binder(RESOURCES.resolve("application-prod.yml"))
                .bind("management.server.port", Integer.class)
                .get())
        .isEqualTo(8081);
  }

  /**
   * Staging and production's task definitions set the pool size as {@code
   * SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE}; relaxed binding gives that the same property as the
   * name this reference uses, and the environment outranks application.yml's default.
   */
  @Test
  void theTaskDefinitionsPoolSizeOverridesTheDefault() throws IOException {
    MutablePropertySources sources = new MutablePropertySources();
    sources.addFirst(
        new SystemEnvironmentPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            Map.of("SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE", "5")));
    load(RESOURCES.resolve("application.yml")).forEach(sources::addLast);
    Binder binder =
        new Binder(
            ConfigurationPropertySources.from(sources),
            new PropertySourcesPlaceholdersResolver(sources));

    assertThat(binder.bind("spring.datasource.hikari.maximum-pool-size", Integer.class).get())
        .isEqualTo(5);
  }

  /** A binder over one file alone, its placeholders resolved to their defaults. */
  private static Binder binder(Path file) throws IOException {
    MutablePropertySources sources = new MutablePropertySources();
    load(file).forEach(sources::addLast);
    return new Binder(
        ConfigurationPropertySources.from(sources),
        new PropertySourcesPlaceholdersResolver(sources));
  }

  private static List<PropertySource<?>> load(Path file) throws IOException {
    return new YamlPropertySourceLoader()
        .load(file.getFileName().toString(), new FileSystemResource(file));
  }

  private static String reference() {
    try {
      return Files.readString(REFERENCE, StandardCharsets.UTF_8);
    } catch (IOException missing) {
      throw new UncheckedIOException(missing);
    }
  }
}
