package za.co.fynbos.branchbook.notification.infrastructure;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import za.co.fynbos.branchbook.booking.domain.DemoNumberBlock;
import za.co.fynbos.branchbook.notification.application.DispatcherProperties;
import za.co.fynbos.branchbook.notification.application.MessageGateway;
import za.co.fynbos.branchbook.notification.application.SmsProperties;

/**
 * Builds the one {@link MessageGateway} the dispatcher sends through, from {@code branchbook.sms}:
 * the {@link SimulatedSmsGateway} where the gateway is {@code simulated}, the default, or a {@link
 * RoutingSmsGateway} where it is {@code aws}, which keeps the published demo block simulated and
 * sends every other number a real SMS through {@link AwsSmsGateway}. Spring closes the AWS client
 * on shutdown, because the routing gateway is {@link AutoCloseable}.
 *
 * <p>It logs one line at start-up that names the gateway, the region and whether a protect
 * configuration and a daily cap are set, so a deployment's log shows what it sends. It names no
 * number, identifier or credential.
 */
@Configuration(proxyBeanMethods = false)
class SmsGatewayConfiguration {

  private static final Logger log = LoggerFactory.getLogger(SmsGatewayConfiguration.class);

  @Bean
  MessageGateway messageGateway(
      SmsProperties sms, DispatcherProperties dispatcher, DemoNumberBlock demoNumbers) {
    SimulatedSmsGateway simulated = new SimulatedSmsGateway(dispatcher);
    String cap =
        sms.recipientDailyCap() == 0
            ? "no recipient daily cap"
            : "a recipient daily cap of " + sms.recipientDailyCap() + " SMS";
    if (sms.gateway() == SmsProperties.Gateway.SIMULATED) {
      log.info(
          "SMS gateway: simulated for every number, so no SMS is sent"
              + " (branchbook.sms.gateway is simulated); {}.",
          cap);
      return simulated;
    }
    SmsProperties.Aws settings = sms.aws();
    AwsSmsGateway aws = AwsSmsGateway.create(settings);
    log.info(
        "SMS gateway: AWS End User Messaging SMS in {} for every number outside the demo block"
            + " {}, which stays simulated (branchbook.sms.gateway is aws); {}; {}{}.",
        aws.region(),
        demoNumbers,
        hasText(settings.protectConfigurationId())
            ? "a protect configuration is set"
            : "no protect configuration",
        cap,
        settings.dryRun() ? "; dry run, so AWS checks each message and sends none" : "");
    return new RoutingSmsGateway(simulated, aws, demoNumbers);
  }

  private static boolean hasText(@Nullable String value) {
    return value != null && !value.isBlank();
  }
}
