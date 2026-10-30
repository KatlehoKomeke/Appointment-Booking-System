package za.co.fynbos.branchbook.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;

/**
 * FR-30, NFR-13: {@code /actuator/prometheus} serves the alarm gauges beside {@code
 * outbox_pending}, as Prometheus reads them: the oldest PENDING message's age, the FAILED count and
 * the seconds since the last tick, and the SMS circuit breaker's state. The ages follow the clock
 * between ticks; nothing in their names or tags is personal data. The numbers are synthetic.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class OutboxHealthMetricsIT {

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private MutableClock clock;

  @Autowired private NotificationDispatcher dispatcher;

  @Test
  void theAlarmGaugesFollowTheOutboxAndTheClock() {
    assertThat(book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0000")))
        .hasStatus(HttpStatus.CREATED);
    assertThat(book(mvc, body("BEL", "CARD", "2026-10-28T10:00:00+02:00", "082 555 0177")))
        .hasStatus(HttpStatus.CREATED);
    Instant start = clock.instant();

    assertThat(dispatcher.runOnce(start)).isEqualTo(2);
    clock.advance(Duration.ofSeconds(10));
    String scrape = scrape();

    assertThat(value(scrape, "outbox_oldest_pending_age_seconds"))
        .as("the 0000 message has waited since it was booked")
        .isEqualTo(10.0);
    assertThat(value(scrape, "outbox_last_tick_age_seconds")).isEqualTo(10.0);
    assertThat(value(scrape, "outbox_failed")).isZero();
    assertThat(value(scrape, "outbox_pending")).isOne();
    assertThat(scrape)
        .contains("# TYPE outbox_oldest_pending_age_seconds gauge")
        .contains("# TYPE outbox_failed gauge")
        .contains("# TYPE outbox_last_tick_age_seconds gauge")
        .contains("resilience4j_circuitbreaker_state{name=\"sms\",state=\"closed\"} 1.0");

    jdbc.update(
        "UPDATE notification_messages SET status = 'FAILED', failure_reason = 'DELIVERY_FAILED',"
            + " attempts = 5 WHERE status = 'PENDING'");
    assertThat(dispatcher.runOnce(clock.instant())).isZero();
    scrape = scrape();

    assertThat(value(scrape, "outbox_oldest_pending_age_seconds")).isZero();
    assertThat(value(scrape, "outbox_failed")).isOne();
    assertThat(value(scrape, "outbox_last_tick_age_seconds")).isZero();
  }

  private String scrape() {
    MvcTestResult scrape = mvc.get().uri("/actuator/prometheus").exchange();
    assertThat(scrape).hasStatusOk();
    try {
      return scrape.getResponse().getContentAsString();
    } catch (UnsupportedEncodingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** The value of an untagged series in the scrape. */
  private static double value(String scrape, String series) {
    Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(series) + " (\\S+)$").matcher(scrape);
    assertThat(matcher.find()).as("%s is in the scrape", series).isTrue();
    return Double.parseDouble(matcher.group(1));
  }
}
