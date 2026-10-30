package za.co.fynbos.branchbook.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import za.co.fynbos.branchbook.notification.application.MessageGateway.Accepted;
import za.co.fynbos.branchbook.notification.application.MessageGateway.Refused;
import za.co.fynbos.branchbook.support.MutableClock;
import za.co.fynbos.branchbook.support.TestClockConfiguration;

/**
 * NFR-13: the circuit breaker opens once enough calls to the SMS provider fail, refuses every
 * permission while open, lets probes through once its wait is over, and closes again when they
 * pass. Only failures that speak about the provider count: a permanent refusal is a working
 * provider's answer, and the simulated gateway's deliberate refusal says nothing at all. Each
 * change of state is logged once, with no personal data. NotificationDispatcherIT proves what the
 * dispatcher does with it.
 */
@ExtendWith(OutputCaptureExtension.class)
class DeliveryCircuitTest {

  /** Judges the last four calls, once four are made; opens at half of them failed. */
  private static final DispatcherProperties.CircuitBreaker SMALL =
      new DispatcherProperties.CircuitBreaker(
          true, 50, 100, Duration.ofSeconds(8), 4, 4, Duration.ofSeconds(30), 2);

  private static final Duration QUICK = Duration.ofMillis(5);

  /** The open state lets a probe through only once its 30 seconds are over. */
  private static final Duration JUST_OVER_THE_WAIT = Duration.ofSeconds(30).plusMillis(1);

  private final MutableClock clock = new MutableClock(TestClockConfiguration.DEFAULT_INSTANT);

  private final DeliveryCircuit circuit =
      new DeliveryCircuit(SMALL, clock, Set.of("SIMULATED_FAILURE"));

  @Test
  void opensOnceTheFailureRateReachesItsThresholdAndThenRefusesEveryPermission() {
    call(new Accepted("ok-1"));
    call(new Accepted("ok-2"));
    call(Refused.retryable("AWS_THROTTLED"));
    assertThat(circuit.state())
        .as("three calls are fewer than the minimum")
        .isEqualTo(State.CLOSED);

    call(Refused.retryable("AWS_UNREACHABLE"));

    assertThat(circuit.state()).isEqualTo(State.OPEN);
    assertThat(circuit.tryAcquire()).isFalse();
    clock.advance(Duration.ofSeconds(30));
    assertThat(circuit.tryAcquire()).as("still within its wait").isFalse();
  }

  /** A gateway that throws an Error is a failure like any other. */
  @Test
  void anErrorFromTheGatewayCountsAsAFailure() {
    for (int call = 0; call < 4; call++) {
      assertThat(circuit.tryAcquire()).isTrue();
      circuit.recordError(QUICK, new LinkageError("test: a class is missing"));
    }

    assertThat(circuit.state()).isEqualTo(State.OPEN);
  }

  /**
   * Calls that each answer but take longer than the slow-call duration open it too, as a provider
   * that times out would.
   */
  @Test
  void callsThatAreAllSlowOpenIt() {
    for (int call = 0; call < 4; call++) {
      assertThat(circuit.tryAcquire()).isTrue();
      circuit.record(Duration.ofSeconds(9), new Accepted("slow-" + call));
    }

    assertThat(circuit.state()).isEqualTo(State.OPEN);
  }

  @Test
  void permanentRefusalsNeverOpenIt() {
    for (int call = 0; call < 20; call++) {
      call(Refused.permanent("AWS_CONFLICT_DESTINATION_PHONE_NUMBER_NOT_VERIFIED"));
    }

    assertThat(circuit.state()).isEqualTo(State.CLOSED);
  }

  /** The simulated gateway's refusal of numbers ending 0000 is the demo's, not the provider's. */
  @Test
  void anUncountedRefusalNeverOpensIt() {
    for (int call = 0; call < 20; call++) {
      call(Refused.retryable("SIMULATED_FAILURE"));
    }

    assertThat(circuit.state()).isEqualTo(State.CLOSED);
  }

  /**
   * Once the wait is over, the next permission turns it half-open; two probes that pass close it,
   * and the log has one line for each change of state.
   */
  @Test
  void recoversThroughHalfOpenWhenItsProbesPass(CapturedOutput output) {
    openIt();
    clock.advance(JUST_OVER_THE_WAIT);

    assertThat(circuit.tryAcquire()).isTrue();
    assertThat(circuit.state()).isEqualTo(State.HALF_OPEN);
    circuit.record(QUICK, new Accepted("probe-1"));
    call(new Accepted("probe-2"));

    assertThat(circuit.state()).isEqualTo(State.CLOSED);
    assertThat(output.getAll())
        .containsOnlyOnce("outbox.circuit from=CLOSED to=OPEN")
        .containsOnlyOnce("outbox.circuit from=OPEN to=HALF_OPEN")
        .containsOnlyOnce("outbox.circuit from=HALF_OPEN to=CLOSED");
  }

  @Test
  void opensAgainWhenItsProbesFail() {
    openIt();
    clock.advance(JUST_OVER_THE_WAIT);

    call(Refused.retryable("AWS_INTERNAL"));
    call(Refused.retryable("AWS_INTERNAL"));

    assertThat(circuit.state()).isEqualTo(State.OPEN);
    assertThat(circuit.tryAcquire()).isFalse();
  }

  /**
   * Half-open allows only as many probes as it is set to, and a permission given back, because
   * nothing was sent, is a probe that can still be made.
   */
  @Test
  void aReleasedPermissionLeavesItsProbeForLater() {
    openIt();
    clock.advance(JUST_OVER_THE_WAIT);

    assertThat(circuit.tryAcquire()).isTrue();
    assertThat(circuit.tryAcquire()).isTrue();
    assertThat(circuit.tryAcquire()).as("both probes are out").isFalse();
    circuit.release();
    circuit.release();

    call(new Accepted("probe-1"));
    call(new Accepted("probe-2"));
    assertThat(circuit.state()).isEqualTo(State.CLOSED);
  }

  /** Switched off, it never opens, whatever fails. */
  @Test
  void switchedOffItLetsEveryCallThrough() {
    DeliveryCircuit off =
        new DeliveryCircuit(
            new DispatcherProperties.CircuitBreaker(
                false, 50, 100, Duration.ofSeconds(8), 4, 4, Duration.ofSeconds(30), 2),
            clock,
            Set.of());

    for (int call = 0; call < 20; call++) {
      assertThat(off.tryAcquire()).isTrue();
      off.record(QUICK, Refused.retryable("AWS_THROTTLED"));
    }

    assertThat(off.state()).isEqualTo(State.DISABLED);
  }

  /** Its meters carry the breaker's name and state, nothing about any message. */
  @Test
  void itsMetersAreTaggedWithItsNameAndState() {
    MeterRegistry meters = new SimpleMeterRegistry();
    circuit.bindTo(meters);
    openIt();

    assertThat(
            meters
                .get("resilience4j.circuitbreaker.state")
                .tags("name", "sms", "state", "open")
                .gauge()
                .value())
        .isEqualTo(1.0);
    assertThat(
            meters
                .get("resilience4j.circuitbreaker.state")
                .tags("name", "sms", "state", "closed")
                .gauge()
                .value())
        .isZero();
  }

  private void openIt() {
    for (int call = 0; call < 4; call++) {
      call(Refused.retryable("AWS_THROTTLED"));
    }
    assertThat(circuit.state()).isEqualTo(State.OPEN);
  }

  /** One call made with a permission, which must be granted. */
  private void call(MessageGateway.Delivery outcome) {
    assertThat(circuit.tryAcquire()).isTrue();
    circuit.record(QUICK, outcome);
  }
}
