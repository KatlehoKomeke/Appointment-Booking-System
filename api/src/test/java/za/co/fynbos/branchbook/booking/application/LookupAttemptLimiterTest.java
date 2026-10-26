package za.co.fynbos.branchbook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;
import za.co.fynbos.branchbook.shared.config.BookingProperties;

/**
 * BR-13: five wrong lookups of a reference block it until an hour after the first, and lookups that
 * arrive together cannot slip past that limit.
 */
class LookupAttemptLimiterTest {

  private static final AppointmentReference REFERENCE = new AppointmentReference("APT-7K3M-9Q2X");
  private static final Instant FIRST = Instant.parse("2026-10-26T06:00:00Z");

  private final LookupAttemptLimiter limiter =
      new LookupAttemptLimiter(
          new BookingProperties(
              Duration.ofHours(2), Period.ofDays(30), 5, Duration.ofHours(1), false));

  @Test
  void fifthFailureBlocksTheReferenceUntilAnHourAfterTheFirst() {
    for (int failure = 0; failure < 5; failure++) {
      wrongLookup(REFERENCE, FIRST.plusSeconds(failure));
    }

    Instant tenMinutesLater = FIRST.plusSeconds(600);
    Instant anHourLater = FIRST.plus(Duration.ofHours(1));

    assertThatExceptionOfType(LookupRateLimitedException.class)
        .isThrownBy(() -> limiter.begin(REFERENCE, tenMinutesLater))
        .satisfies(limited -> assertThat(limited.retryAfter()).contains(Duration.ofSeconds(3000)));
    assertThatNoException().isThrownBy(() -> limiter.begin(REFERENCE, anHourLater));
  }

  /** Failures spread over more than an hour never add up: the window never slides forward. */
  @Test
  void windowOpensAtTheFirstFailureAndDoesNotSlide() {
    for (int failure = 0; failure < 4; failure++) {
      wrongLookup(REFERENCE, FIRST.plus(Duration.ofMinutes(10L * failure)));
    }
    Instant afterTheWindow = FIRST.plus(Duration.ofMinutes(61));
    wrongLookup(REFERENCE, afterTheWindow);

    assertThatNoException().isThrownBy(() -> limiter.begin(REFERENCE, afterTheWindow));
  }

  @Test
  void otherReferencesAreNotAffected() {
    for (int failure = 0; failure < 5; failure++) {
      wrongLookup(REFERENCE, FIRST);
    }

    AppointmentReference other = new AppointmentReference("APT-2222-3333");

    assertThatNoException().isThrownBy(() -> limiter.begin(other, FIRST));
  }

  /** A customer who keeps their appointment page open is never blocked by their own lookups. */
  @Test
  void lookupsWithTheRightDigitsAreNeverCounted() {
    for (int failure = 0; failure < 4; failure++) {
      wrongLookup(REFERENCE, FIRST);
    }
    for (int refresh = 0; refresh < 20; refresh++) {
      limiter.end(limiter.begin(REFERENCE, FIRST), false);
    }

    assertThatNoException().isThrownBy(() -> wrongLookup(REFERENCE, FIRST));
    assertThatExceptionOfType(LookupRateLimitedException.class)
        .isThrownBy(() -> limiter.begin(REFERENCE, FIRST));
  }

  /**
   * A lookup still being checked counts as if it were wrong, so with four failures only one more
   * may be in flight. The others are told to retry after a second, not after the hour, because they
   * were refused only while it was in flight.
   */
  @Test
  void lookupsInFlightCountTowardsTheLimit() {
    for (int failure = 0; failure < 4; failure++) {
      wrongLookup(REFERENCE, FIRST);
    }
    LookupAttemptLimiter.Attempt inFlight = limiter.begin(REFERENCE, FIRST);

    assertThatExceptionOfType(LookupRateLimitedException.class)
        .isThrownBy(() -> limiter.begin(REFERENCE, FIRST))
        .satisfies(limited -> assertThat(limited.retryAfter()).contains(Duration.ofSeconds(1)));

    limiter.end(inFlight, false);
    assertThatNoException().isThrownBy(() -> limiter.begin(REFERENCE, FIRST));
  }

  /**
   * The race the limit must survive: with four failures counted, many lookups arrive at the same
   * moment. Exactly one is admitted, so at most one more guess reaches the database.
   */
  @Test
  void lookupsArrivingTogetherAdmitOnlyAsManyAsTheLimitLeaves() throws Exception {
    for (int failure = 0; failure < 4; failure++) {
      wrongLookup(REFERENCE, FIRST);
    }
    int racers = 32;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> admitted = new ArrayList<>();
    try (ExecutorService threads = Executors.newFixedThreadPool(racers)) {
      for (int racer = 0; racer < racers; racer++) {
        admitted.add(
            threads.submit(
                () -> {
                  start.await();
                  try {
                    limiter.begin(REFERENCE, FIRST);
                    return true;
                  } catch (LookupRateLimitedException _) {
                    return false;
                  }
                }));
      }
      start.countDown();
    }

    long passed = 0;
    for (Future<Boolean> racer : admitted) {
      passed += racer.get() ? 1 : 0;
    }
    assertThat(passed).isOne();
  }

  /**
   * A lookup ended as not wrong, however many, leaves nothing behind. The handler ends a lookup
   * whose read failed this way (LookupAppointmentHandlerTest).
   */
  @Test
  void lookupEndedAsNotWrongLeavesNothingBehind() {
    for (int lookup = 0; lookup < 10; lookup++) {
      limiter.end(limiter.begin(REFERENCE, FIRST), false);
    }

    for (int failure = 0; failure < 4; failure++) {
      wrongLookup(REFERENCE, FIRST);
    }
    assertThatNoException().isThrownBy(() -> limiter.begin(REFERENCE, FIRST));
  }

  /**
   * A slow lookup admitted in a window that has since closed releases nothing when it ends: it was
   * counted in that window, not in the one that replaced it. With four failures in the new window,
   * one lookup is admitted and the next is refused.
   */
  @Test
  void staleLookupDoesNotFreeAPlaceInTheNextWindow() {
    LookupAttemptLimiter.Attempt slow = limiter.begin(REFERENCE, FIRST);
    Instant nextWindow = FIRST.plus(Duration.ofHours(1));
    for (int failure = 0; failure < 4; failure++) {
      wrongLookup(REFERENCE, nextWindow);
    }

    limiter.end(slow, false);

    assertThatNoException().isThrownBy(() -> limiter.begin(REFERENCE, nextWindow));
    assertThatExceptionOfType(LookupRateLimitedException.class)
        .isThrownBy(() -> limiter.begin(REFERENCE, nextWindow))
        .satisfies(limited -> assertThat(limited.retryAfter()).contains(Duration.ofSeconds(1)));
  }

  /**
   * Nor does it wipe the next window while a guess is in flight there, which would lose that
   * guess's failure: five failures in the new window still block the reference for the hour.
   */
  @Test
  void staleLookupDoesNotLoseAFailureInTheNextWindow() {
    LookupAttemptLimiter.Attempt slow = limiter.begin(REFERENCE, FIRST);
    Instant nextWindow = FIRST.plus(Duration.ofHours(1));
    LookupAttemptLimiter.Attempt guess = limiter.begin(REFERENCE, nextWindow);

    limiter.end(slow, false);
    limiter.end(guess, true);
    for (int failure = 0; failure < 4; failure++) {
      wrongLookup(REFERENCE, nextWindow);
    }

    assertThatExceptionOfType(LookupRateLimitedException.class)
        .isThrownBy(() -> limiter.begin(REFERENCE, nextWindow))
        .satisfies(limited -> assertThat(limited.retryAfter()).contains(Duration.ofHours(1)));
  }

  @Test
  void attemptNeverShowsTheReference() {
    assertThat(limiter.begin(REFERENCE, FIRST)).hasToString("Attempt[redacted]");
  }

  private void wrongLookup(AppointmentReference reference, Instant at) {
    limiter.end(limiter.begin(reference, at), true);
  }
}
