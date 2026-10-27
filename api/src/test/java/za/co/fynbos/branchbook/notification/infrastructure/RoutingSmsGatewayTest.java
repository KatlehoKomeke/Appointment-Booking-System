package za.co.fynbos.branchbook.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;
import za.co.fynbos.branchbook.notification.application.MessageGateway;
import za.co.fynbos.branchbook.notification.application.MessageGateway.Accepted;
import za.co.fynbos.branchbook.notification.application.OutgoingMessage;
import za.co.fynbos.branchbook.notification.application.SmsProperties;
import za.co.fynbos.branchbook.notification.domain.NotificationChannel;

/**
 * The safety rule where {@code branchbook.sms.gateway} is {@code aws}: every number in the
 * published demo block, 082 555 0000 to 0199, goes through the simulated gateway, because anyone
 * may type it and it may belong to a real stranger; every other number goes through the real one.
 */
class RoutingSmsGatewayTest {

  private final List<String> simulatedSent = new ArrayList<>();
  private final List<String> realSent = new ArrayList<>();

  private final MessageGateway simulated =
      message -> {
        simulatedSent.add(message.recipient());
        return new Accepted("sim-1");
      };

  private final MessageGateway real =
      message -> {
        realSent.add(message.recipient());
        return new Accepted("aws-1");
      };

  private final RoutingSmsGateway routing = new RoutingSmsGateway(simulated, real);

  @ParameterizedTest
  @ValueSource(strings = {"+27825550000", "+27825550101", "+27825550199"})
  void theDemoBlockIsAlwaysSimulated(String recipient) {
    assertThat(routing.send(message(recipient))).isEqualTo(new Accepted("sim-1"));

    assertThat(simulatedSent).containsExactly(recipient);
    assertThat(realSent).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"+27825549999", "+27825550200", "+27723456789", "+27610000000"})
  void everyOtherNumberGoesThroughTheRealGateway(String recipient) {
    assertThat(routing.send(message(recipient))).isEqualTo(new Accepted("aws-1"));

    assertThat(realSent).containsExactly(recipient);
    assertThat(simulatedSent).isEmpty();
  }

  /** Closing the router on shutdown closes the AWS gateway, and with it the AWS client. */
  @Test
  void closingTheRouterClosesTheAwsClient() {
    PinpointSmsVoiceV2Client client = mock(PinpointSmsVoiceV2Client.class);
    AwsSmsGateway aws =
        new AwsSmsGateway(client, new SmsProperties.Aws(null, null, null, null, "0.20", false));

    new RoutingSmsGateway(simulated, aws).close();

    verify(client).close();
  }

  private static OutgoingMessage message(String recipient) {
    return new OutgoingMessage(UUID.randomUUID(), NotificationChannel.SMS, recipient, "Appt");
  }
}
