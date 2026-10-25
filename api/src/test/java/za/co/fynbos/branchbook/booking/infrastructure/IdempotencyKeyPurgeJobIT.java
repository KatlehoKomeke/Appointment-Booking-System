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
 * BR-12: an {@code Idempotency-Key} is honoured for exactly 24 hours, purged or not, and the hourly
 * purge removes only keys that have expired. The job is called directly; its schedule is Spring's.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class IdempotencyKeyPurgeJobIT {

  @Autowired private MockMvcTester mvc;

  @Autowired private MutableClock clock;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private IdempotencyKeyPurgeJob job;

  @Test
  void keyIsHonouredForTwentyFourHoursThenBooksAnewEvenBeforeThePurge() {
    String key = UUID.randomUUID().toString();
    assertThat(book(mvc, key, body("SAN", "FICA", "2026-10-28T10:00:00+02:00", "082 555 0170")))
        .hasStatus(HttpStatus.CREATED);

    clock.advance(Duration.ofHours(24).minusSeconds(1));
    MvcTestResult replayed =
        book(mvc, key, body("SAN", "FICA", "2026-10-28T10:00:00+02:00", "082 555 0170"));
    MvcTestResult reused =
        book(mvc, key, body("SAN", "FICA", "2026-10-29T10:00:00+02:00", "082 555 0171"));
    assertThat(replayed).hasStatus(HttpStatus.CREATED).hasHeader("Idempotent-Replayed", "true");
    assertThat(reused).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);

    clock.advance(Duration.ofSeconds(1));
    MvcTestResult afterADay =
        book(mvc, key, body("SAN", "FICA", "2026-10-29T10:00:00+02:00", "082 555 0171"));
    assertThat(afterADay)
        .as("24 hours after its booking, the key books anew though no purge has run")
        .hasStatus(HttpStatus.CREATED)
        .doesNotContainHeader("Idempotent-Replayed");
    assertThat(count("SELECT count(*) FROM idempotency_keys")).isOne();
    assertThat(count("SELECT count(*) FROM appointments")).isEqualTo(2);
  }

  @Test
  void purgeRemovesOnlyKeysThatHaveExpired() {
    String older = UUID.randomUUID().toString();
    String newer = UUID.randomUUID().toString();
    assertThat(book(mvc, older, body("SAN", "FICA", "2026-10-28T10:00:00+02:00", "082 555 0172")))
        .hasStatus(HttpStatus.CREATED);
    clock.advance(Duration.ofHours(12));
    assertThat(book(mvc, newer, body("SAN", "FICA", "2026-10-28T11:00:00+02:00", "082 555 0173")))
        .hasStatus(HttpStatus.CREATED);

    clock.advance(Duration.ofHours(12));
    job.purge();

    assertThat(jdbc.queryForList("SELECT key::text FROM idempotency_keys", String.class))
        .containsExactly(newer);
  }

  private int count(String sql) {
    Integer rows = jdbc.queryForObject(sql, Integer.class);
    return rows == null ? 0 : rows;
  }
}
