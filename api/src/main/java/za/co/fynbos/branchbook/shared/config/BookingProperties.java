package za.co.fynbos.branchbook.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.time.Period;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The booking rules and limits that are configuration rather than code, under {@code
 * branchbook.booking}. application.yml sets each from an environment variable with a safe default
 * and explains the value; docs/configuration.md lists them all. The application refuses to start
 * without them, or with a duration that is zero or negative.
 *
 * @param leadTime how far ahead of now the earliest bookable slot starts (BR-4)
 * @param horizon how many days are bookable, counted from today in the branch's time zone (BR-5)
 * @param lookupMaxFailures how many wrong lookups of one reference are answered before it is
 *     blocked (BR-13)
 * @param lookupWindow how long a reference stays blocked, counted from its first wrong lookup
 * @param lookupInFlightLease how long an admitted lookup counts as in flight if it is never
 *     settled, so one an instance never ended does bounded harm (BR-13)
 * @param lookupInFlightRetryAfter how long a lookup refused only because others are still in flight
 *     is told to wait, sent as {@code Retry-After}
 * @param conflictRetryAfter how long a booking that met a second transaction conflict is told to
 *     wait, sent as {@code Retry-After} with {@code 503 TRY_AGAIN}
 * @param idempotencyKeyTtl how long an {@code Idempotency-Key} is honoured after its booking; from
 *     then on the same key books anew (BR-12)
 * @param maxAlternatives the most later slots a {@code SLOT_FULL} answer offers (BR-7)
 * @param demoNumbersOnly whether a booking must use one of the published demo numbers, so a public
 *     demo can never be given a real person's number (ADR-0006); off unless a deployment switches
 *     it on
 * @param demoNumberBlock the published demo numbers: bookable where {@code demoNumbersOnly} is on,
 *     always sent through the simulated gateway, and never counted by the SMS cap
 */
@Validated
@ConfigurationProperties("branchbook.booking")
public record BookingProperties(
    @NotNull Duration leadTime,
    @NotNull Period horizon,
    @Positive int lookupMaxFailures,
    @NotNull Duration lookupWindow,
    @NotNull Duration lookupInFlightLease,
    @NotNull Duration lookupInFlightRetryAfter,
    @NotNull Duration conflictRetryAfter,
    @NotNull Duration idempotencyKeyTtl,
    @Positive int maxAlternatives,
    boolean demoNumbersOnly,
    @NotNull @Valid DemoNumbers demoNumberBlock) {

  /** Refuses a duration that is zero or negative, which would switch a rule off unnoticed. */
  public BookingProperties {
    requirePositive("lookup-window", lookupWindow);
    requirePositive("lookup-in-flight-lease", lookupInFlightLease);
    requirePositive("lookup-in-flight-retry-after", lookupInFlightRetryAfter);
    requirePositive("conflict-retry-after", conflictRetryAfter);
    requirePositive("idempotency-key-ttl", idempotencyKeyTtl);
  }

  /**
   * The same rules with {@code demo-numbers-only} switched on or off, for a test that compares the
   * two.
   *
   * @param on whether only the demo numbers book
   * @return a copy that differs only in that setting
   */
  public BookingProperties withDemoNumbersOnly(boolean on) {
    return new BookingProperties(
        leadTime,
        horizon,
        lookupMaxFailures,
        lookupWindow,
        lookupInFlightLease,
        lookupInFlightRetryAfter,
        conflictRetryAfter,
        idempotencyKeyTtl,
        maxAlternatives,
        on,
        demoNumberBlock);
  }

  private static void requirePositive(String name, Duration value) {
    if (value != null && (value.isZero() || value.isNegative())) {
      throw new IllegalArgumentException(
          "branchbook.booking." + name + " must be more than zero, an ISO-8601 duration");
    }
  }

  /**
   * The published demo block, both ends included, each in E.164 form such as {@code +27825550000}.
   * The booking module checks that each end is a South African mobile number and that the first is
   * not after the last.
   *
   * @param first the first number of the block
   * @param last the last number of the block
   */
  public record DemoNumbers(@NotBlank String first, @NotBlank String last) {}
}
