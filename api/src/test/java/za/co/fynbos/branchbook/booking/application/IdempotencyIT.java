package za.co.fynbos.branchbook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;

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

/**
 * BR-12, one request at a time: a retry with the same {@code Idempotency-Key} returns the same
 * appointment, and only a successful booking keeps its key. The race of two concurrent requests
 * with one key is proven in milestone M5.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class IdempotencyIT {

  private static final String STARTS_AT = "2026-10-27T10:00:00+02:00";

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Test
  void sameKeySamePayloadReplaysOriginal201() {
    String key = UUID.randomUUID().toString();
    MvcTestResult first = book(mvc, key, body("SAN", "FICA", STARTS_AT, "082 555 0171"));

    MvcTestResult retry = book(mvc, key, body("SAN", "FICA", STARTS_AT, "082 555 0171"));

    assertThat(retry).hasStatus(HttpStatus.CREATED);
    assertThat(retry).headers().hasValue("Idempotent-Replayed", "true");
    assertThat(retry.getResponse().getHeader("Location"))
        .isEqualTo(first.getResponse().getHeader("Location"));
    assertThat(retry).bodyJson().extractingPath("$.reference").isEqualTo(reference(first));
    assertThat(count("appointments")).isOne();
    assertThat(count("notification_messages")).isOne();
  }

  /** The same details typed another way are the same booking, so the retry is a replay. */
  @Test
  void sameKeyWithTheNumberAndTimeTypedAnotherWayReplays() {
    String key = UUID.randomUUID().toString();
    book(mvc, key, body("SAN", "FICA", STARTS_AT, "082 555 0172"));

    MvcTestResult retry =
        book(mvc, key, body("san", "fica", "2026-10-27T08:00:00Z", "+27 82 555 0172"));

    assertThat(retry).hasStatus(HttpStatus.CREATED);
    assertThat(retry).headers().hasValue("Idempotent-Replayed", "true");
    assertThat(count("appointments")).isOne();
  }

  @Test
  void sameKeyDifferentPayloadReturns422() {
    String key = UUID.randomUUID().toString();
    book(mvc, key, body("SAN", "FICA", STARTS_AT, "082 555 0173"));

    MvcTestResult reused =
        book(mvc, key, body("SAN", "FICA", "2026-10-27T10:15:00+02:00", "082 555 0173"));

    assertThat(reused).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(reused).bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(count("appointments")).isOne();
  }

  /** A refused booking rolls its key back, so the client can retry the key with new details. */
  @Test
  void failedBookingLeavesNoKeyRow() {
    String key = UUID.randomUUID().toString();
    assertThat(book(mvc, key, body("SAN", "FICA", "2026-11-04T09:00:00+02:00", "082 555 0174")))
        .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);

    assertThat(count("idempotency_keys")).isZero();
    assertThat(book(mvc, key, body("SAN", "FICA", STARTS_AT, "082 555 0174")))
        .hasStatus(HttpStatus.CREATED)
        .doesNotContainHeader("Idempotent-Replayed");
  }

  private static String reference(MvcTestResult result) {
    return com.jayway.jsonpath.JsonPath.read(
        new String(
            result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8),
        "$.reference");
  }

  private int count(String table) {
    Integer rows = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    return rows == null ? 0 : rows;
  }
}
