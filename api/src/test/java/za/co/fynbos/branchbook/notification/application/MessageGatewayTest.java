package za.co.fynbos.branchbook.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/**
 * What a gateway may return fits {@code notification_messages}, so recording it can never fail on a
 * length: a provider id from 1 to 64 characters, and an error code cut to 500.
 */
class MessageGatewayTest {

  @Test
  void providerIdMustFitItsColumn() {
    assertThat(new MessageGateway.Accepted("p".repeat(64)).providerMessageId()).hasSize(64);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new MessageGateway.Accepted("p".repeat(65)));
    assertThatIllegalArgumentException().isThrownBy(() -> new MessageGateway.Accepted(" "));
  }

  @Test
  void longErrorCodeIsCutToItsColumn() {
    assertThat(new MessageGateway.Refused("e".repeat(700)).errorCode()).hasSize(500);
    assertThat(new MessageGateway.Refused("SIMULATED_FAILURE").errorCode())
        .isEqualTo("SIMULATED_FAILURE");
  }
}
