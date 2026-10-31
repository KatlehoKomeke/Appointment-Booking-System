package za.co.fynbos.branchbook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.support.TransactionOperations;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;
import za.co.fynbos.branchbook.support.BookingRules;

/**
 * BR-13: settling a count runs after the lookup's read and after the cancel's commit, so it must
 * never change the customer's answer. The store fails on cue here, which the integration tests
 * cannot arrange.
 */
@ExtendWith(OutputCaptureExtension.class)
class LookupAttemptLimiterTest {

  private static final AppointmentReference REFERENCE = new AppointmentReference("APT-7K3M-9Q2X");
  private static final Instant FIRST = Instant.parse("2026-10-26T06:00:00Z");

  /** A store whose lock fails once {@link #failing} is set, as on a lock or statement timeout. */
  private static final class FailingStore extends InMemoryLookupAttemptStore {
    private boolean failing;

    @Override
    public Optional<Window> lockWindow(String reference) {
      if (failing) {
        throw new QueryTimeoutException("canceling statement due to lock timeout");
      }
      return super.lockWindow(reference);
    }
  }

  private final FailingStore store = new FailingStore();

  private final LookupAttemptLimiter limiter =
      new LookupAttemptLimiter(
          BookingRules.defaults(), store, TransactionOperations.withoutTransaction());

  /**
   * A settle that fails is logged without the reference or the database's message, and does not
   * throw: the lookup's or the cancel's own answer stands.
   */
  @Test
  void aSettleThatFailsNeverThrowsAndLogsNeitherTheReferenceNorTheMessage(CapturedOutput output) {
    LookupAttemptLimiter.Attempt attempt = limiter.begin(REFERENCE, FIRST);
    store.failing = true;

    assertThatNoException().isThrownBy(() -> limiter.end(attempt, true, FIRST.plusSeconds(1)));

    assertThat(output)
        .contains("lookup.count_not_settled cause=QueryTimeoutException")
        .doesNotContain(REFERENCE.value())
        .doesNotContain("lock timeout");
  }

  /**
   * The try it could not settle stays in flight only until its lease runs out: with four failures
   * counted, the reference is refused for a second while the lease holds, then admits one more.
   */
  @Test
  void aTryThatCouldNotBeSettledHoldsItsPlaceOnlyForItsLease() {
    for (int failure = 0; failure < 4; failure++) {
      limiter.end(limiter.begin(REFERENCE, FIRST), true, FIRST);
    }
    LookupAttemptLimiter.Attempt unsettled = limiter.begin(REFERENCE, FIRST);
    store.failing = true;
    limiter.end(unsettled, false, FIRST);
    store.failing = false;

    assertThatExceptionOfType(LookupRateLimitedException.class)
        .isThrownBy(() -> limiter.begin(REFERENCE, FIRST.plusSeconds(1)))
        .satisfies(limited -> assertThat(limited.retryAfter()).contains(Duration.ofSeconds(1)));
    Instant afterTheLease =
        FIRST.plus(BookingRules.defaults().lookupInFlightLease()).plusSeconds(1);
    assertThatNoException().isThrownBy(() -> limiter.begin(REFERENCE, afterTheLease));
  }
}
