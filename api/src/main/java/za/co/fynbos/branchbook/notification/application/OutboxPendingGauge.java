package za.co.fynbos.branchbook.notification.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The gauge {@value #METER}, which Prometheus reads as {@code outbox_pending}: how many messages
 * were still PENDING at the end of the dispatcher's last tick (FR-30).
 *
 * <p>A backlog that keeps growing means delivery is not keeping up, or not running at all; one that
 * returns to zero between bursts is healthy (docs/runbook.md). The value is counted with one query
 * per tick, by {@link NotificationDispatcher#runOnce}, and held here, rather than counted on every
 * scrape: a scrape never touches the database, and the tests, which drive the dispatcher
 * themselves, see the gauge move exactly when a tick runs. It counts every PENDING message, those
 * backing off after a refusal included.
 *
 * <p>Until a tick has counted, the gauge is NaN, not 0. Only the dispatcher's ticks refresh it, so
 * a dispatcher that is switched off ({@code branchbook.dispatcher.enabled} false) or never ran
 * shows no number, rather than the 0 of one that keeps up while messages wait.
 */
@Component
class OutboxPendingGauge {

  /** The gauge's name, in Micrometer's dotted form. It has no tags. */
  static final String METER = "outbox.pending";

  private static final Logger log = LoggerFactory.getLogger(OutboxPendingGauge.class);

  /** What the gauge holds until the first count; a count is never negative. */
  private static final long NOT_COUNTED = -1;

  private final AtomicLong pending = new AtomicLong(NOT_COUNTED);
  private final NotificationMessageRepository messages;

  OutboxPendingGauge(NotificationMessageRepository messages, MeterRegistry registry) {
    this.messages = messages;
    Gauge.builder(METER, pending, OutboxPendingGauge::value)
        .description("Messages still PENDING after the dispatcher's last tick")
        .strongReference(true)
        .register(registry);
  }

  /** The last count, or NaN before the first. */
  private static double value(AtomicLong pending) {
    long count = pending.get();
    return count == NOT_COUNTED ? Double.NaN : count;
  }

  /**
   * Counts the PENDING messages again. If the count fails, the gauge keeps its last value and the
   * failure is logged by type, so a database outage never ends the dispatcher's tick.
   */
  void refresh() {
    try {
      pending.set(messages.countPending());
    } catch (RuntimeException failure) {
      log.warn("outbox.pending_not_counted cause={}", failure.getClass().getSimpleName());
    }
  }
}
