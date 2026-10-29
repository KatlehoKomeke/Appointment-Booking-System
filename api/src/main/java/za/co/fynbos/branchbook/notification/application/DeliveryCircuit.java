package za.co.fynbos.branchbook.notification.application;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import za.co.fynbos.branchbook.notification.application.MessageGateway.Delivery;
import za.co.fynbos.branchbook.notification.application.MessageGateway.Refused;

/**
 * The circuit breaker the dispatcher asks before it claims a message (BR-17, NFR-13). While the SMS
 * provider is failing, it is open and the dispatcher claims nothing, so every message stays PENDING
 * with no attempt spent: an outage of the provider never uses up a customer's five attempts.
 *
 * <p>It is Resilience4j's breaker, used as a library and driven by hand rather than by wrapping a
 * call, because the question comes before the claim: the dispatcher {@link #tryAcquire acquires} a
 * permission, and then either {@link #record records} what the gateway answered or, where it sent
 * nothing after all, {@link #release releases} it. A retryable refusal, a gateway that throws and
 * an {@link Error} from it count as failures; an acceptance and a permanent refusal, the answer of
 * a provider that works, count as successes. A refusal whose code is one of {@code uncountedCodes},
 * such as the simulated gateway's deliberate refusal of numbers ending 0000, counts as neither
 * kind, as it says nothing about the provider. Settings come from {@code
 * branchbook.dispatcher.circuit-breaker}.
 *
 * <p>Each change of state is logged once, as {@code outbox.circuit from=CLOSED to=OPEN} with the
 * failure and slow-call rates; never a recipient, a body or a reference. Its meters, bound by
 * {@link #bindTo}, are Resilience4j's {@code resilience4j.circuitbreaker.*}, tagged with the
 * breaker's {@link #NAME}.
 */
public final class DeliveryCircuit {

  /** The breaker's name: the {@code name} tag on its meters. */
  public static final String NAME = "sms";

  private static final Logger log = LoggerFactory.getLogger(DeliveryCircuit.class);

  private final CircuitBreakerRegistry registry;
  private final CircuitBreaker breaker;
  private final Set<String> uncounted;

  /**
   * Builds a closed breaker.
   *
   * @param settings thresholds, window and waits
   * @param clock what times the open state's wait; the tests pass one they can move
   * @param uncountedCodes refusal codes that count neither as a failure nor as a success
   */
  public DeliveryCircuit(
      DispatcherProperties.CircuitBreaker settings, Clock clock, Set<String> uncountedCodes) {
    CircuitBreakerConfig config =
        CircuitBreakerConfig.custom()
            .slidingWindowType(SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(settings.slidingWindowSize())
            .minimumNumberOfCalls(settings.minimumNumberOfCalls())
            .failureRateThreshold(settings.failureRateThreshold())
            .slowCallRateThreshold(settings.slowCallRateThreshold())
            .slowCallDurationThreshold(settings.slowCallDuration())
            .waitDurationInOpenState(settings.waitInOpenState())
            .permittedNumberOfCallsInHalfOpenState(settings.permittedCallsInHalfOpenState())
            .recordResult(result -> result instanceof Refused refused && !refused.permanent())
            .ignoreExceptions(Uncounted.class)
            .clock(clock)
            .build();
    this.registry = CircuitBreakerRegistry.of(config);
    this.breaker = registry.circuitBreaker(NAME);
    this.uncounted = Set.copyOf(uncountedCodes);
    breaker.getEventPublisher().onStateTransition(this::logTransition);
    if (!settings.enabled()) {
      breaker.transitionToDisabledState();
    }
  }

  /**
   * Publishes the breaker's meters, {@code resilience4j.circuitbreaker.state} among them, tagged
   * {@code name=sms}. Called once, by the bean that builds the application's breaker.
   *
   * @param meters where to publish them
   */
  public void bindTo(MeterRegistry meters) {
    TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
  }

  /**
   * Asks for a permission to call the gateway once. Open, it answers false; once the open state's
   * wait is over, it turns half-open and answers true for each probe it allows. Every true answer
   * must be followed by exactly one {@link #record}, {@link #recordError} or {@link #release}.
   *
   * @return true if the dispatcher may claim a message and send it
   */
  public boolean tryAcquire() {
    return breaker.tryAcquirePermission();
  }

  /** Gives back a permission the dispatcher did not use, because it sent nothing after all. */
  public void release() {
    breaker.releasePermission();
  }

  /**
   * Records what the gateway answered to a call made with a permission.
   *
   * @param elapsed how long the call took, which may make it a slow call
   * @param outcome the gateway's answer
   */
  public void record(Duration elapsed, Delivery outcome) {
    if (outcome instanceof Refused refused && uncounted.contains(refused.errorCode())) {
      breaker.onError(elapsed.toNanos(), TimeUnit.NANOSECONDS, Uncounted.INSTANCE);
      return;
    }
    breaker.onResult(elapsed.toNanos(), TimeUnit.NANOSECONDS, outcome);
  }

  /**
   * Records a call made with a permission that ended in an {@link Error} rather than an answer,
   * which counts as a failure.
   *
   * @param elapsed how long the call took
   * @param failure what the gateway threw
   */
  public void recordError(Duration elapsed, Throwable failure) {
    breaker.onError(elapsed.toNanos(), TimeUnit.NANOSECONDS, failure);
  }

  /**
   * The breaker's state, for the tests and the start-up line.
   *
   * @return for example {@code CLOSED} or {@code OPEN}
   */
  public CircuitBreaker.State state() {
    return breaker.getState();
  }

  private void logTransition(CircuitBreakerOnStateTransitionEvent event) {
    CircuitBreaker.StateTransition transition = event.getStateTransition();
    CircuitBreaker.Metrics metrics = breaker.getMetrics();
    String line = "outbox.circuit from={} to={} failureRate={} slowCallRate={}";
    if (transition.getToState() == CircuitBreaker.State.OPEN) {
      log.warn(
          line,
          transition.getFromState(),
          transition.getToState(),
          metrics.getFailureRate(),
          metrics.getSlowCallRate());
    } else {
      log.info(
          line,
          transition.getFromState(),
          transition.getToState(),
          metrics.getFailureRate(),
          metrics.getSlowCallRate());
    }
  }

  /**
   * Stands for a refusal that says nothing about the provider, which the breaker is configured to
   * ignore. It carries no message and no stack trace.
   */
  private static final class Uncounted extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private static final Uncounted INSTANCE = new Uncounted();

    private Uncounted() {
      super(null, null, false, false);
    }
  }
}
