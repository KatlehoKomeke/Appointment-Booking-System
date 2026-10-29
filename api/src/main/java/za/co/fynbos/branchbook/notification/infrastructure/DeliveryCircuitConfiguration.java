package za.co.fynbos.branchbook.notification.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import za.co.fynbos.branchbook.notification.application.DeliveryCircuit;
import za.co.fynbos.branchbook.notification.application.DispatcherProperties;

/**
 * Builds the dispatcher's {@link DeliveryCircuit} from {@code
 * branchbook.dispatcher.circuit-breaker} and publishes its meters.
 *
 * <p>The simulated gateway's refusal of a number ending 0000 is the demo's way of showing a failed
 * delivery, not a provider in trouble, so it never counts: a visitor who books that number
 * repeatedly cannot stop everyone else's messages. One INFO line at start-up gives the breaker's
 * thresholds, naming no number or identifier.
 */
@Configuration(proxyBeanMethods = false)
class DeliveryCircuitConfiguration {

  private static final Logger log = LoggerFactory.getLogger(DeliveryCircuitConfiguration.class);

  @Bean
  DeliveryCircuit deliveryCircuit(
      DispatcherProperties dispatcher, Clock clock, MeterRegistry meters) {
    DispatcherProperties.CircuitBreaker settings = dispatcher.circuitBreaker();
    DeliveryCircuit circuit =
        new DeliveryCircuit(settings, clock, Set.of(SimulatedSmsGateway.SIMULATED_FAILURE));
    circuit.bindTo(meters);
    if (settings.enabled()) {
      log.info(
          "SMS circuit breaker: opens when {}% of the last {} calls fail or {}% take over {}, once"
              + " {} are made; stays open {}, then probes with {} calls.",
          settings.failureRateThreshold(),
          settings.slidingWindowSize(),
          settings.slowCallRateThreshold(),
          settings.slowCallDuration(),
          settings.minimumNumberOfCalls(),
          settings.waitInOpenState(),
          settings.permittedCallsInHalfOpenState());
    } else {
      log.warn(
          "SMS circuit breaker: off (branchbook.dispatcher.circuit-breaker.enabled is false), so"
              + " a provider's outage uses up each message's attempts.");
    }
    return circuit;
  }
}
