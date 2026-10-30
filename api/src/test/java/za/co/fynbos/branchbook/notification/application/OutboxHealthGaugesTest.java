package za.co.fynbos.branchbook.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import za.co.fynbos.branchbook.support.MutableClock;
import za.co.fynbos.branchbook.support.TestClockConfiguration;

/**
 * FR-30: the three alarm gauges. The oldest PENDING message's age and the seconds since the last
 * tick grow with the clock between ticks, so a stuck outbox or a stopped dispatcher shows; the
 * FAILED count holds the last tick's figure. None has a tag. OutboxHealthMetricsIT reads them
 * through {@code /actuator/prometheus}.
 */
class OutboxHealthGaugesTest {

  private static final Instant START = TestClockConfiguration.DEFAULT_INSTANT;

  private final OutboxStatistics statistics = mock(OutboxStatistics.class);
  private final MeterRegistry registry = new SimpleMeterRegistry();
  private final MutableClock clock = new MutableClock(START);

  @Test
  void showNoNumberUntilTheFirstTickWhereTheDispatcherIsSwitchedOff() {
    gauges(false);

    assertThat(value(OutboxHealthGauges.OLDEST_PENDING_AGE)).isNaN();
    assertThat(value(OutboxHealthGauges.FAILED)).isNaN();
    assertThat(value(OutboxHealthGauges.LAST_TICK_AGE)).isNaN();
  }

  /** A dispatcher that never finishes its first tick shows a tick age growing from start-up. */
  @Test
  void theTickAgeCountsFromStartUpWhereTheDispatcherRuns() {
    gauges(true);

    clock.advance(Duration.ofSeconds(45));

    assertThat(value(OutboxHealthGauges.LAST_TICK_AGE)).isEqualTo(45.0);
  }

  @Test
  void theTickAgeIsZeroAfterATickAndGrowsUntilTheNext() {
    OutboxHealthGauges gauges = gauges(true);
    when(statistics.read()).thenReturn(new OutboxStatistics.Snapshot(null, 0));
    clock.advance(Duration.ofSeconds(10));

    gauges.tickCompleted();
    assertThat(value(OutboxHealthGauges.LAST_TICK_AGE)).isZero();
    clock.advance(Duration.ofMillis(2_500));

    assertThat(value(OutboxHealthGauges.LAST_TICK_AGE)).isEqualTo(2.5);
  }

  /** The age is measured at each read, so it keeps growing while no tick refreshes it. */
  @Test
  void theOldestPendingAgeGrowsWithTheClock() {
    OutboxHealthGauges gauges = gauges(true);
    when(statistics.read()).thenReturn(new OutboxStatistics.Snapshot(START.minusSeconds(40), 3));

    gauges.tickCompleted();
    assertThat(value(OutboxHealthGauges.OLDEST_PENDING_AGE)).isEqualTo(40.0);
    assertThat(value(OutboxHealthGauges.FAILED)).isEqualTo(3.0);
    clock.advance(Duration.ofMinutes(5));

    assertThat(value(OutboxHealthGauges.OLDEST_PENDING_AGE)).isEqualTo(340.0);
  }

  @Test
  void theOldestPendingAgeIsZeroWhenNothingIsPending() {
    OutboxHealthGauges gauges = gauges(true);
    when(statistics.read()).thenReturn(new OutboxStatistics.Snapshot(null, 0));

    gauges.tickCompleted();

    assertThat(value(OutboxHealthGauges.OLDEST_PENDING_AGE)).isZero();
    assertThat(value(OutboxHealthGauges.FAILED)).isZero();
  }

  /** A read that fails keeps the last figures, throws nothing, and still notes the tick. */
  @Test
  void aReadThatFailsKeepsTheLastFigures() {
    OutboxHealthGauges gauges = gauges(true);
    when(statistics.read())
        .thenReturn(new OutboxStatistics.Snapshot(null, 2))
        .thenThrow(new DataAccessResourceFailureException("test: the database is down"));
    gauges.tickCompleted();
    clock.advance(Duration.ofSeconds(5));

    gauges.tickCompleted();

    assertThat(value(OutboxHealthGauges.FAILED)).isEqualTo(2.0);
    assertThat(value(OutboxHealthGauges.LAST_TICK_AGE)).isZero();
  }

  @Test
  void noGaugeHasATag() {
    gauges(true);

    assertThat(registry.getMeters())
        .extracting(Meter::getId)
        .allSatisfy(id -> assertThat(id.getTags()).isEmpty())
        .extracting(Meter.Id::getName)
        .containsExactlyInAnyOrder(
            "outbox.oldest.pending.age", "outbox.failed", "outbox.last.tick.age");
  }

  private OutboxHealthGauges gauges(boolean dispatcherEnabled) {
    DispatcherProperties dispatcher =
        new DispatcherProperties(
            dispatcherEnabled,
            Duration.ofSeconds(2),
            Duration.ZERO,
            20,
            5,
            new DispatcherProperties.Backoff(Duration.ofSeconds(2), 2, Duration.ofSeconds(16), 0.2),
            new DispatcherProperties.CircuitBreaker(
                true, 50, 100, Duration.ofSeconds(8), 10, 5, Duration.ofSeconds(30), 2));
    return new OutboxHealthGauges(statistics, registry, clock, dispatcher);
  }

  private double value(String name) {
    return registry.get(name).gauge().value();
  }
}
