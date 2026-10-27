package za.co.fynbos.branchbook.notification.infrastructure;

import java.time.Duration;
import java.util.UUID;
import za.co.fynbos.branchbook.notification.application.DispatcherProperties;
import za.co.fynbos.branchbook.notification.application.MessageGateway;
import za.co.fynbos.branchbook.notification.application.OutgoingMessage;

/**
 * The simulated SMS gateway (FR-15): nothing is ever sent. It takes {@code simulated-latency} to
 * answer, accepts every message with a made-up provider id, and refuses every message to a number
 * ending in {@code 0000}, such as the demo number 082 555 0000, so a failed delivery can be shown.
 * That refusal is retryable, so the demo shows all five attempts and the backoff between them. It
 * logs nothing; the dispatcher logs the outcome by message id.
 *
 * <p>It is not a bean of its own: {@link SmsGatewayConfiguration} hands it to the dispatcher for
 * every number where {@code branchbook.sms.gateway} is {@code simulated}, the default, and for the
 * published demo block where it is {@code aws}.
 */
class SimulatedSmsGateway implements MessageGateway {

  /** The code a refused simulated message records as its last error. */
  static final String SIMULATED_FAILURE = "SIMULATED_FAILURE";

  private static final String FAILING_SUFFIX = "0000";

  private final Duration latency;

  SimulatedSmsGateway(DispatcherProperties dispatcher) {
    this.latency = dispatcher.simulatedLatency();
  }

  @Override
  public Delivery send(OutgoingMessage message) {
    if (!latency.isZero()) {
      try {
        Thread.sleep(latency);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return Refused.retryable("INTERRUPTED");
      }
    }
    if (message.recipient().endsWith(FAILING_SUFFIX)) {
      return Refused.retryable(SIMULATED_FAILURE);
    }
    return new Accepted("sim-" + UUID.randomUUID());
  }
}
