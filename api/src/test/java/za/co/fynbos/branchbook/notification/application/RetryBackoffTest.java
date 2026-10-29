package za.co.fynbos.branchbook.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * BR-17: the wait after a retryable refusal grows from {@code base} by {@code multiplier} up to
 * {@code cap}, and jitter only ever shortens it, by at most its share. With the defaults the waits
 * are 2, 4, 8 and 16 seconds at most, as before jitter, and never less than four fifths of them.
 */
class RetryBackoffTest {

  private static final DispatcherProperties.Backoff DEFAULTS =
      new DispatcherProperties.Backoff(Duration.ofSeconds(2), 2, Duration.ofSeconds(16), 0.2);

  @Test
  void withoutJitterTheDefaultsWaitTwoFourEightAndSixteenSeconds() {
    RetryBackoff backoff = new RetryBackoff(withJitter(0), () -> 0.7);

    assertThat(waits(backoff, 4)).containsExactly(2_000L, 4_000L, 8_000L, 16_000L);
  }

  @Test
  void theCapHoldsForEveryLaterAttempt() {
    RetryBackoff backoff = new RetryBackoff(DEFAULTS, () -> 0.0);

    assertThat(backoff.nominalAfter(5)).isEqualTo(Duration.ofSeconds(16));
    assertThat(backoff.nominalAfter(60)).isEqualTo(Duration.ofSeconds(16));
    assertThat(backoff.after(60)).isEqualTo(Duration.ofSeconds(16));
  }

  /**
   * The default jitter keeps every wait between 0.8 and 1 times today's: ten thousand random draws
   * per attempt stay inside the band, and the band's two ends are reached by the extreme draws.
   */
  @Test
  void defaultJitterKeepsEveryWaitWithinAFifthBelowTodaysSchedule() {
    SplittableRandom random = new SplittableRandom(17);
    RetryBackoff backoff = new RetryBackoff(DEFAULTS, random::nextDouble);

    for (int attempts = 1; attempts <= 4; attempts++) {
      long nominal = 1_000L << attempts;
      for (int draw = 0; draw < 10_000; draw++) {
        assertThat(backoff.after(attempts).toMillis()).isBetween(nominal * 8 / 10, nominal);
      }
    }
    assertThat(new RetryBackoff(DEFAULTS, () -> 0.0).after(1)).hasMillis(2_000);
    assertThat(new RetryBackoff(DEFAULTS, () -> 0.999_999).after(1)).hasMillis(1_600);
  }

  /** A message still reaches FAILED within 30 seconds of its first attempt, whatever the draws. */
  @Test
  void jitterNeverLengthensTheTimeToTheFifthAttempt() {
    RetryBackoff earliest = new RetryBackoff(DEFAULTS, () -> 0.999_999);
    RetryBackoff latest = new RetryBackoff(DEFAULTS, () -> 0.0);

    assertThat(waits(latest, 4).stream().mapToLong(Long::longValue).sum()).isEqualTo(30_000);
    assertThat(waits(earliest, 4).stream().mapToLong(Long::longValue).sum()).isEqualTo(24_000);
  }

  @Test
  void baseMultiplierAndCapAreEachHonoured() {
    DispatcherProperties.Backoff tuned =
        new DispatcherProperties.Backoff(Duration.ofSeconds(1), 3, Duration.ofSeconds(20), 0);
    RetryBackoff backoff = new RetryBackoff(tuned, () -> 0.5);

    assertThat(waits(backoff, 4)).containsExactly(1_000L, 3_000L, 9_000L, 20_000L);
  }

  /** However large the jitter and however small the base, a message is never due again at once. */
  @Test
  void aWaitIsNeverZero() {
    DispatcherProperties.Backoff tiny =
        new DispatcherProperties.Backoff(Duration.ofMillis(1), 1, Duration.ofMillis(1), 0.99);

    assertThat(new RetryBackoff(tiny, () -> 0.999_999).after(1)).hasMillis(1);
  }

  private static DispatcherProperties.Backoff withJitter(double jitter) {
    return new DispatcherProperties.Backoff(DEFAULTS.base(), 2, DEFAULTS.cap(), jitter);
  }

  private static List<Long> waits(RetryBackoff backoff, int attempts) {
    List<Long> waits = new ArrayList<>();
    for (int attempt = 1; attempt <= attempts; attempt++) {
      waits.add(backoff.after(attempt).toMillis());
    }
    return waits;
  }
}
