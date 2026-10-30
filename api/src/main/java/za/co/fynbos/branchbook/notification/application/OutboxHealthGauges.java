package za.co.fynbos.branchbook.notification.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The three gauges an alarm watches to see that delivery is healthy (FR-30), beside {@code
 * outbox.pending}. None has a tag, and none names a message, a recipient or a reference.
 *
 * <ul>
 *   <li>{@value #OLDEST_PENDING_AGE}, in seconds, which Prometheus reads as {@code
 *       outbox_oldest_pending_age_seconds}: how long the oldest PENDING message has waited, or 0
 *       when none waits. A message backing off waits at most about 30 seconds, so a larger value
 *       means delivery is stuck, for example while the circuit breaker holds the dispatcher back.
 *   <li>{@value #FAILED}, which Prometheus reads as {@code outbox_failed}: how many messages are
 *       FAILED, for any reason but the simulated gateway's deliberate refusal of a demo number
 *       ending 0000, which anyone may book to see a failure.
 *   <li>{@value #LAST_TICK_AGE}, in seconds, which Prometheus reads as {@code
 *       outbox_last_tick_age_seconds}: how long ago the dispatcher last finished a tick. It counts
 *       from start-up until the first tick, and a dispatcher that has stopped or hangs shows a
 *       value that keeps growing.
 * </ul>
 *
 * <p>Like {@code outbox.pending}, the figures are read with one query at the end of each tick and
 * held, so a scrape never touches the database; the two ages are worked out at each scrape from the
 * application's clock, so they keep growing while nothing refreshes them. Before the first read,
 * the first two are NaN, not 0. Where {@code branchbook.dispatcher.enabled} is false, as in the
 * tests, the tick age is NaN until a tick is run by hand, because no tick is expected.
 */
@Component
class OutboxHealthGauges {

  /** The age of the oldest PENDING message, in seconds. */
  static final String OLDEST_PENDING_AGE = "outbox.oldest.pending.age";

  /** The number of FAILED messages. */
  static final String FAILED = "outbox.failed";

  /** The seconds since the dispatcher last finished a tick. */
  static final String LAST_TICK_AGE = "outbox.last.tick.age";

  private static final Logger log = LoggerFactory.getLogger(OutboxHealthGauges.class);

  private final OutboxStatistics statistics;
  private final Clock clock;
  private final AtomicReference<OutboxStatistics.@Nullable Snapshot> snapshot =
      new AtomicReference<>();
  private final AtomicReference<@Nullable Instant> lastTick = new AtomicReference<>();

  /**
   * Registers the three gauges.
   *
   * @param statistics where the figures are read from
   * @param registry where the gauges are registered
   * @param clock the application's clock, which the ages are measured against
   * @param dispatcher whether the dispatcher runs on its schedule, which starts the tick age at
   *     start-up
   */
  OutboxHealthGauges(
      OutboxStatistics statistics,
      MeterRegistry registry,
      Clock clock,
      DispatcherProperties dispatcher) {
    this.statistics = statistics;
    this.clock = clock;
    if (dispatcher.enabled()) {
      lastTick.set(clock.instant());
    }
    Gauge.builder(OLDEST_PENDING_AGE, this, OutboxHealthGauges::oldestPendingAge)
        .description("Seconds the oldest PENDING message has waited, 0 when none waits")
        .baseUnit("seconds")
        .strongReference(true)
        .register(registry);
    Gauge.builder(FAILED, this, OutboxHealthGauges::failed)
        .description("Messages FAILED, except the simulated gateway's deliberate failures")
        .strongReference(true)
        .register(registry);
    Gauge.builder(LAST_TICK_AGE, this, OutboxHealthGauges::lastTickAge)
        .description("Seconds since the dispatcher last finished a tick")
        .baseUnit("seconds")
        .strongReference(true)
        .register(registry);
  }

  /**
   * Notes that a tick has finished, and reads the figures again. If the read fails, the gauges keep
   * their last figures and the failure is logged by type, so a database outage never ends the
   * dispatcher's tick.
   */
  void tickCompleted() {
    lastTick.set(clock.instant());
    try {
      snapshot.set(statistics.read());
    } catch (RuntimeException failure) {
      log.warn("outbox.health_not_read cause={}", failure.getClass().getSimpleName());
    }
  }

  private double oldestPendingAge() {
    OutboxStatistics.Snapshot figures = snapshot.get();
    if (figures == null) {
      return Double.NaN;
    }
    Instant oldest = figures.oldestPendingCreatedAt();
    return oldest == null ? 0.0 : secondsSince(oldest);
  }

  private double failed() {
    OutboxStatistics.Snapshot figures = snapshot.get();
    return figures == null ? Double.NaN : figures.failed();
  }

  private double lastTickAge() {
    Instant tick = lastTick.get();
    return tick == null ? Double.NaN : secondsSince(tick);
  }

  /** Seconds from an instant to now, never negative, with millisecond precision. */
  private double secondsSince(Instant then) {
    Duration age = Duration.between(then, clock.instant());
    return age.isNegative() ? 0.0 : age.toMillis() / 1000.0;
  }
}
