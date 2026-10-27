package za.co.fynbos.branchbook.notification.infrastructure;

import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.notification.application.MessageGateway;
import za.co.fynbos.branchbook.notification.application.OutgoingMessage;

/**
 * The gateway where {@code branchbook.sms.gateway} is {@code aws}: it sends every message to a
 * number in the published demo block, 082 555 0000 to 0199, through the simulated gateway, and
 * every other message through the real one.
 *
 * <p>The block is the safety rule. Its numbers are printed in the README for anyone to try, so one
 * of them may belong to a real stranger, who must never be sent a booking someone else made. They
 * are always simulated, whatever the deployment sets.
 */
final class RoutingSmsGateway implements MessageGateway, AutoCloseable {

  private final MessageGateway simulated;
  private final MessageGateway real;

  /**
   * Routes between two gateways.
   *
   * @param simulated the gateway for the published demo block
   * @param real the gateway for every other number, which this gateway closes on shutdown where it
   *     is an {@link AwsSmsGateway}
   */
  RoutingSmsGateway(MessageGateway simulated, MessageGateway real) {
    this.simulated = simulated;
    this.real = real;
  }

  @Override
  public Delivery send(OutgoingMessage message) {
    return gatewayFor(message).send(message);
  }

  /** The gateway one message goes through: simulated for the demo block, real for the rest. */
  MessageGateway gatewayFor(OutgoingMessage message) {
    return MobileNumber.ofE164(message.recipient()).isPublishedDemoNumber() ? simulated : real;
  }

  /** The real gateway, for the wiring test. */
  MessageGateway real() {
    return real;
  }

  /** The simulated gateway, for the wiring test. */
  MessageGateway simulated() {
    return simulated;
  }

  /** Closes the real gateway where it is AWS's, and with it the AWS client. */
  @Override
  public void close() {
    if (real instanceof AwsSmsGateway aws) {
      aws.close();
    }
  }
}
