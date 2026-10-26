package za.co.fynbos.branchbook.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * FR-30: the {@code outbox.pending} gauge holds the count of the last tick, shows no number before
 * the first, and a count that fails leaves the last value in place rather than ending the
 * dispatcher's tick. MetricsIT reads the gauge through {@code /actuator/prometheus} on a real
 * database.
 */
class OutboxPendingGaugeTest {

  private final NotificationMessageRepository messages = mock(NotificationMessageRepository.class);
  private final MeterRegistry registry = new SimpleMeterRegistry();
  private final OutboxPendingGauge gauge = new OutboxPendingGauge(messages, registry);

  /**
   * Until a tick has counted, the gauge is NaN rather than 0, so a dispatcher that is switched off
   * or never ran cannot pass for one that keeps up while messages wait.
   */
  @Test
  void showsNoNumberUntilTheFirstCount() {
    when(messages.countPending()).thenReturn(12L);

    assertThat(value()).isNaN();
    gauge.refresh();
    assertThat(value()).isEqualTo(12.0);
  }

  @Test
  void countThatFailsBeforeAnyOtherLeavesTheGaugeWithoutANumber() {
    when(messages.countPending())
        .thenThrow(new DataAccessResourceFailureException("test: the database is down"));

    gauge.refresh();

    assertThat(value()).isNaN();
  }

  @Test
  void showsTheCountOfTheLastRefresh() {
    when(messages.countPending()).thenReturn(7L, 2L);

    gauge.refresh();
    assertThat(value()).isEqualTo(7.0);
    gauge.refresh();
    assertThat(value()).isEqualTo(2.0);
  }

  @Test
  void countThatFailsKeepsTheLastValueAndThrowsNothing() {
    when(messages.countPending())
        .thenReturn(3L)
        .thenThrow(new DataAccessResourceFailureException("test: the database is down"));
    gauge.refresh();

    gauge.refresh();

    assertThat(value()).isEqualTo(3.0);
  }

  private double value() {
    return registry.get(OutboxPendingGauge.METER).gauge().value();
  }
}
