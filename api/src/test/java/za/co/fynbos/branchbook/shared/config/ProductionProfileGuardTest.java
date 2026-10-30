package za.co.fynbos.branchbook.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.env.MockEnvironment;

/**
 * The prod profile fails closed: staging and production refuse to start with a setting that would
 * be unsafe in public, even when an environment variable overrides application-prod.yml's safe
 * value. No database is needed: the guard runs on the environment before anything is created.
 */
class ProductionProfileGuardTest {

  private static final String KEY = "k".repeat(48);

  private final ProductionProfileGuard guard = new ProductionProfileGuard();

  /** The settings staging and production run with. */
  private static final Map<String, String> PRODUCTION =
      Map.of(
          "branchbook.inbox.enabled", "false",
          "branchbook.inbox.redact-references", "true",
          "branchbook.demo-data.enabled", "false",
          "branchbook.notification.public-base-url", "https://example.test",
          "branchbook.sms.recipient-daily-cap", "5",
          "branchbook.sms.recipient-cap-key", KEY,
          "server.port", "8080",
          "management.server.port", "8081");

  private static MockEnvironment safeProd() {
    return safeProdWithout("");
  }

  private static MockEnvironment safeProdWithout(String left) {
    MockEnvironment environment = new MockEnvironment();
    PRODUCTION.forEach(
        (name, value) -> {
          if (!name.equals(left)) {
            environment.setProperty(name, value);
          }
        });
    environment.setActiveProfiles("prod");
    return environment;
  }

  private void start(MockEnvironment environment) {
    guard.postProcessEnvironment(environment, new SpringApplication());
  }

  @Test
  void theProductionSettingsStart() {
    assertThatNoException().isThrownBy(() -> start(safeProd()));
  }

  @Test
  void theInboxWithoutRedactedReferencesIsRefused() {
    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                start(
                    safeProd()
                        .withProperty("branchbook.inbox.enabled", "true")
                        .withProperty("branchbook.inbox.redact-references", "false")))
        .withMessageContaining("BRANCHBOOK_INBOX_REDACT_REFERENCES");
  }

  @Test
  void theInboxWithRedactedReferencesStarts() {
    assertThatNoException()
        .isThrownBy(() -> start(safeProd().withProperty("branchbook.inbox.enabled", "true")));
  }

  @Test
  void demoDataIsRefused() {
    assertThatIllegalStateException()
        .isThrownBy(() -> start(safeProd().withProperty("branchbook.demo-data.enabled", "true")))
        .withMessageContaining("BRANCHBOOK_DEMO_DATA_ENABLED");
  }

  @Test
  void aPlainHttpPublicBaseUrlIsRefused() {
    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                start(
                    safeProd()
                        .withProperty(
                            "branchbook.notification.public-base-url", "http://example.test")))
        .withMessageContaining("PUBLIC_BASE_URL");
  }

  /** BR-17: without its key the cap's counts would reset at every restart. */
  @Test
  void theSmsCapWithoutItsKeyIsRefused() {
    assertThatIllegalStateException()
        .isThrownBy(() -> start(safeProd().withProperty("branchbook.sms.recipient-cap-key", "")))
        .withMessageContaining("BRANCHBOOK_SMS_RECIPIENT_CAP_KEY");
  }

  @Test
  void theActuatorOnThePublicPortIsRefused() {
    assertThatIllegalStateException()
        .isThrownBy(() -> start(safeProd().withProperty("management.server.port", "8080")))
        .withMessageContaining("MANAGEMENT_SERVER_PORT");
    assertThat(ProductionProfileGuard.problems(safeProdWithout("management.server.port")))
        .singleElement()
        .asString()
        .contains("MANAGEMENT_SERVER_PORT");
  }

  /** Every problem is named at once, and no setting's value, the cap's key included, is quoted. */
  @Test
  void everyProblemIsNamedAndNoValueIsQuoted() {
    MockEnvironment unsafe =
        safeProd()
            .withProperty("branchbook.demo-data.enabled", "true")
            .withProperty("branchbook.notification.public-base-url", "http://example.test")
            .withProperty("management.server.port", "8080");

    assertThatIllegalStateException()
        .isThrownBy(() -> start(unsafe))
        .withMessageContaining("BRANCHBOOK_DEMO_DATA_ENABLED")
        .withMessageContaining("PUBLIC_BASE_URL")
        .withMessageContaining("MANAGEMENT_SERVER_PORT")
        .withMessageNotContaining("example.test")
        .withMessageNotContaining(KEY);
  }

  @Test
  void withoutTheProdProfileNothingIsChecked() {
    MockEnvironment local =
        new MockEnvironment()
            .withProperty("branchbook.demo-data.enabled", "true")
            .withProperty("branchbook.notification.public-base-url", "http://localhost:8080");

    assertThatNoException().isThrownBy(() -> start(local));
  }

  /**
   * The migration task serves nothing and sends nothing, so it needs no public address, cap key or
   * management port; staging and production's task sets only the database and the switch.
   */
  @Test
  void theMigrationTaskStartsWithoutTheServingSettings() {
    MockEnvironment migration = new MockEnvironment();
    migration.setActiveProfiles("prod");
    migration
        .withProperty(MigrateOnlyMode.PROPERTY, "true")
        .withProperty("branchbook.notification.public-base-url", "http://localhost:8080")
        .withProperty("branchbook.sms.recipient-daily-cap", "5");

    assertThatNoException().isThrownBy(() -> start(migration));
  }

  /**
   * Even the migration task refuses demo data and an unredacted inbox: they mean a wrong config.
   */
  @Test
  void theMigrationTaskStillRefusesDemoDataAndAnUnredactedInbox() {
    MockEnvironment migration = new MockEnvironment();
    migration.setActiveProfiles("prod");
    migration
        .withProperty(MigrateOnlyMode.PROPERTY, "true")
        .withProperty("branchbook.demo-data.enabled", "true")
        .withProperty("branchbook.inbox.enabled", "true");

    assertThatIllegalStateException()
        .isThrownBy(() -> start(migration))
        .withMessageContaining("BRANCHBOOK_DEMO_DATA_ENABLED")
        .withMessageContaining("BRANCHBOOK_INBOX_REDACT_REFERENCES")
        .withMessageNotContaining("PUBLIC_BASE_URL");
  }

  /** The guard judges the settings as given, before the migrate-only mode forces demo data off. */
  @Test
  void theGuardRunsBeforeTheMigrateOnlyModesOverrides() {
    assertThat(new ProductionProfileGuard().getOrder())
        .isLessThan(new MigrateOnlyMode().getOrder());
  }

  /**
   * Spring Boot finds the guard and the migrate-only mode in the application's spring.factories.
   */
  @Test
  void springBootRunsTheGuardAndTheMigrateOnlyMode() throws IOException {
    Properties factories =
        PropertiesLoaderUtils.loadProperties(new ClassPathResource("META-INF/spring.factories"));

    assertThat(factories.getProperty(EnvironmentPostProcessor.class.getName()))
        .contains(ProductionProfileGuard.class.getName())
        .contains(MigrateOnlyMode.class.getName());
  }
}
