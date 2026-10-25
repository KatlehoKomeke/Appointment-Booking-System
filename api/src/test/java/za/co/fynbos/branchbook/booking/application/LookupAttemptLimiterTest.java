package za.co.fynbos.branchbook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import org.junit.jupiter.api.Test;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;
import za.co.fynbos.branchbook.shared.config.BookingProperties;

/** BR-13: five wrong lookups of a reference block it until an hour after the first. */
class LookupAttemptLimiterTest {

  private static final AppointmentReference REFERENCE = new AppointmentReference("APT-7K3M-9Q2X");
  private static final Instant FIRST = Instant.parse("2026-10-26T06:00:00Z");

  private final LookupAttemptLimiter limiter =
      new LookupAttemptLimiter(
          new BookingProperties(Duration.ofHours(2), Period.ofDays(30), 5, Duration.ofHours(1)));

  @Test
  void fifthFailureBlocksTheReferenceUntilAnHourAfterTheFirst() {
    for (int failure = 0; failure < 4; failure++) {
      limiter.recordFailure(REFERENCE, FIRST.plusSeconds(failure));
      assertThatNoException().isThrownBy(() -> limiter.checkNotBlocked(REFERENCE, FIRST));
    }
    limiter.recordFailure(REFERENCE, FIRST.plusSeconds(4));

    assertThatExceptionOfType(LookupRateLimitedException.class)
        .isThrownBy(() -> limiter.checkNotBlocked(REFERENCE, FIRST.plusSeconds(600)))
        .satisfies(limited -> assertThat(limited.retryAfter()).contains(Duration.ofSeconds(3000)));
    assertThatNoException()
        .isThrownBy(() -> limiter.checkNotBlocked(REFERENCE, FIRST.plus(Duration.ofHours(1))));
  }

  /** Failures spread over more than an hour never add up: the window never slides forward. */
  @Test
  void windowOpensAtTheFirstFailureAndDoesNotSlide() {
    for (int failure = 0; failure < 4; failure++) {
      limiter.recordFailure(REFERENCE, FIRST.plus(Duration.ofMinutes(10L * failure)));
    }
    Instant afterTheWindow = FIRST.plus(Duration.ofMinutes(61));
    limiter.recordFailure(REFERENCE, afterTheWindow);

    assertThatNoException().isThrownBy(() -> limiter.checkNotBlocked(REFERENCE, afterTheWindow));
  }

  @Test
  void otherReferencesAreNotAffected() {
    for (int failure = 0; failure < 5; failure++) {
      limiter.recordFailure(REFERENCE, FIRST);
    }

    assertThatNoException()
        .isThrownBy(
            () -> limiter.checkNotBlocked(new AppointmentReference("APT-2222-3333"), FIRST));
  }
}
