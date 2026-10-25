package za.co.fynbos.branchbook.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;

import java.time.Duration;
import java.util.UUID;
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
 * BR-12: an {@code Idempotency-Key} is honoured for 24 hours and then until the hourly purge
 * removes it, so for at most 25. The job is called directly; its schedule is Spring's.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class IdempotencyKeyPurgeJobIT {

  @Autowired private MockMvcTester mvc;

  @Autowired private MutableClock clock;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private IdempotencyKeyPurgeJob job;

  @Test
  void keyIsHonouredUntilPurgedAfterTwentyFourHoursThenFreeAgain() {
    String key = UUID.randomUUID().toString();
    assertThat(book(mvc, key, body("SAN", "FICA", "2026-10-28T10:00:00+02:00", "082 555 0170")))
        .hasStatus(HttpStatus.CREATED);

    clock.advance(Duration.ofHours(24));
    job.purge();
    assertThat(keys()).as("a key is kept for a full 24 hours").isOne();

    clock.advance(Duration.ofMinutes(30));
    MvcTestResult beforePurge =
        book(mvc, key, body("SAN", "FICA", "2026-10-28T10:00:00+02:00", "082 555 0170"));
    assertThat(beforePurge)
        .as("an expired key still replays until the purge runs")
        .hasStatus(HttpStatus.CREATED)
        .hasHeader("Idempotent-Replayed", "true");

    job.purge();
    assertThat(keys()).isZero();
    MvcTestResult reused =
        book(mvc, key, body("SAN", "FICA", "2026-10-29T10:00:00+02:00", "082 555 0171"));
    assertThat(reused)
        .as("once purged, the key books anew instead of being refused as reused")
        .hasStatus(HttpStatus.CREATED)
        .doesNotContainHeader("Idempotent-Replayed");
    assertThat(keys()).isOne();
  }

  private int keys() {
    Integer rows = jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Integer.class);
    return rows == null ? 0 : rows;
  }
}
