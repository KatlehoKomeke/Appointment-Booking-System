package za.co.fynbos.branchbook.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import software.amazon.awssdk.regions.Region;
import za.co.fynbos.branchbook.notification.application.DispatcherProperties;
import za.co.fynbos.branchbook.notification.application.MessageGateway;
import za.co.fynbos.branchbook.notification.application.MessageGateway.Accepted;
import za.co.fynbos.branchbook.notification.application.OutgoingMessage;
import za.co.fynbos.branchbook.notification.application.SmsProperties;
import za.co.fynbos.branchbook.notification.domain.NotificationChannel;

/**
 * FR-15: exactly one {@link MessageGateway} reaches the dispatcher. By default it is the simulated
 * gateway, for every number; where {@code branchbook.sms.gateway} is {@code aws} it is the router,
 * with the simulated gateway for the demo block and an AWS gateway for the rest. One INFO line says
 * which, naming no identifier. The AWS client is built with an explicit region and never called, so
 * nothing reaches AWS and no credentials are read.
 */
@ExtendWith(OutputCaptureExtension.class)
class SmsGatewayConfigurationTest {

  /**
   * The settings alone, with the developer's environment replaced by an empty one, so a {@code
   * BRANCHBOOK_SMS_GATEWAY} exported in their shell cannot change what the tests prove.
   */
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(Settings.class, SmsGatewayConfiguration.class)
          .withPropertyValues(
              "branchbook.dispatcher.interval=PT2S", "branchbook.dispatcher.simulated-latency=PT0S")
          .withInitializer(
              started ->
                  started
                      .getEnvironment()
                      .getPropertySources()
                      .replace(
                          StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                          new SystemEnvironmentPropertySource(
                              StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                              Map.of())));

  @Test
  void byDefaultEveryNumberIsSimulated(CapturedOutput output) {
    context.run(
        started -> {
          assertThat(started).hasSingleBean(MessageGateway.class);
          MessageGateway gateway = started.getBean(MessageGateway.class);
          assertThat(gateway).isInstanceOf(SimulatedSmsGateway.class);
          assertThat(gateway.send(message("+27723456789")))
              .isInstanceOfSatisfying(
                  Accepted.class,
                  accepted -> assertThat(accepted.providerMessageId()).startsWith("sim-"));
        });

    assertThat(output)
        .contains(
            "SMS gateway: simulated for every number, so no SMS is sent"
                + " (branchbook.sms.gateway is simulated); no recipient daily cap.");
  }

  @Test
  void awsRoutesTheDemoBlockToTheSimulatedGatewayAndEveryOtherNumberToAws(CapturedOutput output) {
    context
        .withPropertyValues(
            "branchbook.sms.gateway=aws",
            "branchbook.sms.recipient-daily-cap=5",
            "branchbook.sms.aws.region=af-south-1",
            "branchbook.sms.aws.protect-configuration-id=protect-0a1b2c")
        .run(
            started -> {
              assertThat(started).hasSingleBean(MessageGateway.class);
              assertThat(started.getBean(MessageGateway.class))
                  .isInstanceOfSatisfying(
                      RoutingSmsGateway.class,
                      routing -> {
                        assertThat(routing.simulated()).isInstanceOf(SimulatedSmsGateway.class);
                        assertThat(routing.real())
                            .isInstanceOfSatisfying(
                                AwsSmsGateway.class,
                                aws -> assertThat(aws.region()).isEqualTo(Region.AF_SOUTH_1));
                        assertThat(routing.gatewayFor(message("+27825550101")))
                            .isSameAs(routing.simulated());
                        assertThat(routing.gatewayFor(message("+27723456789")))
                            .isSameAs(routing.real());
                      });
            });

    assertThat(output)
        .contains(
            "SMS gateway: AWS End User Messaging SMS in af-south-1 for every number outside the"
                + " demo block 082 555 0000 to 0199, which stays simulated"
                + " (branchbook.sms.gateway is aws); a protect configuration is set;"
                + " a recipient daily cap of 5 SMS.")
        .doesNotContain("protect-0a1b2c");
  }

  /** A dry run says so, and no protect configuration says that too. */
  @Test
  void theLineSaysWhenItIsADryRunWithoutAProtectConfiguration(CapturedOutput output) {
    context
        .withPropertyValues(
            "branchbook.sms.gateway=AWS",
            "branchbook.sms.aws.region=af-south-1",
            "branchbook.sms.aws.dry-run=true")
        .run(started -> assertThat(started).hasSingleBean(RoutingSmsGateway.class));

    assertThat(output)
        .contains(
            "no protect configuration; no recipient daily cap;"
                + " dry run, so AWS checks each message and sends none.");
  }

  private static OutgoingMessage message(String recipient) {
    return new OutgoingMessage(UUID.randomUUID(), NotificationChannel.SMS, recipient, "Appt");
  }

  /** Binds the SMS and dispatcher settings alone. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({SmsProperties.class, DispatcherProperties.class})
  static class Settings {}
}
