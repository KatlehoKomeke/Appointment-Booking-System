package za.co.fynbos.branchbook.notification.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the confirmation dispatcher (BR-17), under {@code branchbook.dispatcher} in
 * application.yml, where each one names the environment variable that overrides it.
 *
 * <p>The defaults reproduce the dispatcher as it was before these became settings: twenty messages
 * a tick, five attempts, and waits of up to 2, 4, 8 and 16 seconds between them. Each default is
 * also declared here, so a context that binds these settings without application.yml, as a slice
 * test does, gets the same dispatcher.
 *
 * @param enabled whether the dispatcher runs on its schedule; the tests switch it off and drive
 *     {@link NotificationDispatcher#runOnce} themselves
 * @param interval the pause between the end of one tick and the start of the next
 * @param simulatedLatency how long the simulated gateway takes to accept a message
 * @param batchSize the most messages one tick handles, so one tick never runs for long
 * @param maxAttempts the attempt whose refusal, even a retryable one, marks a message FAILED; at
 *     most 5, the most {@code notification_messages.attempts} allows
 * @param backoff how long a message waits after a retryable refusal
 * @param circuitBreaker when the dispatcher stops calling a failing SMS provider, and how it tries
 *     again
 */
@Validated
@ConfigurationProperties("branchbook.dispatcher")
public record DispatcherProperties(
    boolean enabled,
    @NotNull Duration interval,
    @NotNull Duration simulatedLatency,
    @DefaultValue("20") @Min(1) @Max(1000) int batchSize,
    @DefaultValue("5") @Min(1) @Max(5) int maxAttempts,
    @NotNull @Valid @DefaultValue Backoff backoff,
    @NotNull @Valid @DefaultValue CircuitBreaker circuitBreaker) {

  /**
   * The wait after a retryable refusal: {@code base} after the first attempt, multiplied by {@code
   * multiplier} after each one after it, never more than {@code cap}; then shortened by a random
   * share of up to {@code jitter} of itself, so messages refused together are not all tried again
   * at the same instant. Jitter only ever shortens a wait, so {@code cap} stays a true ceiling and
   * the longest a message can take to reach FAILED is the same with jitter as without.
   *
   * @param base the wait after the first refused attempt
   * @param multiplier how much longer each next wait is, at least 1
   * @param cap the longest any wait can be, at least {@code base}
   * @param jitter the largest share of a wait that is taken off at random, from 0 (none) up to, but
   *     not including, 1
   */
  public record Backoff(
      @NotNull @DefaultValue("PT2S") Duration base,
      @DefaultValue("2") @DecimalMin("1") double multiplier,
      @NotNull @DefaultValue("PT16S") Duration cap,
      @DefaultValue("0.2") @DecimalMin("0") @DecimalMax(value = "1", inclusive = false)
          double jitter) {

    /**
     * Tells the validator whether the wait starts above zero and the cap is at least the first
     * wait; either mistake would retry a refused message at once.
     *
     * @return true if both hold
     */
    @AssertTrue(message = "base must be positive and cap at least base")
    public boolean isOrdered() {
      return base == null || cap == null || (base.isPositive() && cap.compareTo(base) >= 0);
    }
  }

  /**
   * The circuit breaker around the SMS gateway (Resilience4j's, used as a library). It looks at the
   * last {@code slidingWindowSize} calls; once at least {@code minimumNumberOfCalls} have been made
   * and {@code failureRateThreshold} percent of them failed, or {@code slowCallRateThreshold}
   * percent took longer than {@code slowCallDuration}, it opens, and the dispatcher claims no
   * message for {@code waitInOpenState}. Then it lets {@code permittedCallsInHalfOpenState} calls
   * through, and closes again if they pass or opens again if they fail. A retryable refusal or a
   * gateway that throws is a failure; a permanent refusal, such as a number the provider will never
   * text, is an answer from a working provider, so it is not.
   *
   * @param enabled whether the breaker can open at all; off, every call goes through, as before
   * @param failureRateThreshold the percentage of failed calls, from 1 to 100, that opens it
   * @param slowCallRateThreshold the percentage of slow calls, from 1 to 100, that opens it
   * @param slowCallDuration how long a call may take before it counts as slow
   * @param slidingWindowSize how many of the latest calls it judges by
   * @param minimumNumberOfCalls how many calls it waits for before it judges at all
   * @param waitInOpenState how long it stays open before it lets a probe through
   * @param permittedCallsInHalfOpenState how many probes decide whether it closes again
   */
  public record CircuitBreaker(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("50") @DecimalMin("1") @DecimalMax("100") float failureRateThreshold,
      @DefaultValue("100") @DecimalMin("1") @DecimalMax("100") float slowCallRateThreshold,
      @NotNull @DefaultValue("PT8S") Duration slowCallDuration,
      @DefaultValue("10") @Min(1) int slidingWindowSize,
      @DefaultValue("5") @Min(1) int minimumNumberOfCalls,
      @NotNull @DefaultValue("PT30S") Duration waitInOpenState,
      @DefaultValue("2") @Min(1) int permittedCallsInHalfOpenState) {

    /**
     * Tells the validator whether both durations are positive; Resilience4j refuses a zero wait.
     *
     * @return true if both are
     */
    @AssertTrue(message = "slow-call-duration and wait-in-open-state must be positive")
    public boolean isPositive() {
      return slowCallDuration == null
          || waitInOpenState == null
          || (slowCallDuration.isPositive() && waitInOpenState.isPositive());
    }
  }
}
