package za.co.fynbos.branchbook.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The SMS settings: simulated with no daily cap unless a deployment says otherwise, bound from the
 * environment variables the hosted demo's task definition sets, and refused at start-up when a
 * value AWS would refuse anyway, such as a malformed MaxPrice, or a negative cap is given. No
 * database is needed: the settings are bound in a context of their own, whose environment holds
 * only what each test gives it.
 */
class SmsPropertiesTest {

  private final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(SmsSettings.class);

  @Test
  void bySettingNothingTheGatewayIsSimulatedWithNoCap() {
    withEnvironment(Map.of())
        .run(
            started -> {
              SmsProperties sms = started.getBean(SmsProperties.class);
              assertThat(sms.gateway()).isEqualTo(SmsProperties.Gateway.SIMULATED);
              assertThat(sms.recipientDailyCap()).isZero();
              assertThat(sms.aws().region()).isNull();
              assertThat(sms.aws().protectConfigurationId()).isNull();
              assertThat(sms.aws().configurationSet()).isNull();
              assertThat(sms.aws().originationIdentity()).isNull();
              assertThat(sms.aws().maxPrice()).isEqualTo("0.20");
              assertThat(sms.aws().dryRun()).isFalse();
            });
  }

  /** The names the hosted demo's task definition uses, as documented in api/README.md. */
  @Test
  void bindsEverySettingFromItsEnvironmentVariable() {
    withEnvironment(
            Map.of(
                "BRANCHBOOK_SMS_GATEWAY", "aws",
                "BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP", "5",
                "BRANCHBOOK_SMS_AWS_REGION", "af-south-1",
                "BRANCHBOOK_SMS_AWS_PROTECT_CONFIGURATION_ID", "protect-0a1b2c",
                "BRANCHBOOK_SMS_AWS_CONFIGURATION_SET", "branchbook-sms",
                "BRANCHBOOK_SMS_AWS_ORIGINATION_IDENTITY", "pool-0a1b2c",
                "BRANCHBOOK_SMS_AWS_MAX_PRICE", "0.05",
                "BRANCHBOOK_SMS_AWS_DRY_RUN", "true"))
        .run(
            started -> {
              SmsProperties sms = started.getBean(SmsProperties.class);
              assertThat(sms.gateway()).isEqualTo(SmsProperties.Gateway.AWS);
              assertThat(sms.recipientDailyCap()).isEqualTo(5);
              assertThat(sms.aws())
                  .isEqualTo(
                      new SmsProperties.Aws(
                          "af-south-1",
                          "protect-0a1b2c",
                          "branchbook-sms",
                          "pool-0a1b2c",
                          "0.05",
                          true));
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.20", ".5", "10.12345", "0.00001"})
  void acceptsAMaxPriceInTheFormAwsTakes(String maxPrice) {
    withEnvironment(Map.of("BRANCHBOOK_SMS_AWS_MAX_PRICE", maxPrice))
        .run(
            started ->
                assertThat(started.getBean(SmsProperties.class).aws().maxPrice())
                    .isEqualTo(maxPrice));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "0.123456", "100.5", "0,20", "$0.20", "0.20 "})
  void aMaxPriceAwsWouldRefuseStopsStartUp(String maxPrice) {
    withEnvironment(Map.of("BRANCHBOOK_SMS_AWS_MAX_PRICE", maxPrice))
        .run(
            started ->
                assertThat(started)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("maxPrice"));
  }

  @Test
  void aNegativeCapStopsStartUp() {
    withEnvironment(Map.of("BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP", "-1"))
        .run(
            started ->
                assertThat(started)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("recipientDailyCap"));
  }

  @Test
  void anUnknownGatewayStopsStartUp() {
    withEnvironment(Map.of("BRANCHBOOK_SMS_GATEWAY", "sns"))
        .run(started -> assertThat(started).hasFailed());
  }

  /** The runner with the developer's environment replaced by the given variables. */
  private ApplicationContextRunner withEnvironment(Map<String, Object> variables) {
    return context.withInitializer(
        started ->
            started
                .getEnvironment()
                .getPropertySources()
                .replace(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)));
  }

  /** Binds the SMS settings alone. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(SmsProperties.class)
  static class SmsSettings {}
}
