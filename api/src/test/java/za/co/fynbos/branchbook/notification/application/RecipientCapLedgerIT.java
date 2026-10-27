package za.co.fynbos.branchbook.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;
import static za.co.fynbos.branchbook.support.Bookings.cancel;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;

/**
 * BR-17, V7: the daily cap counts every SMS of the last 24 hours in its ledger, so the messages a
 * cancel or the dispatcher deletes still count. With the cap at 5, as on the hosted demo, booking
 * and cancelling one number in turn reaches the cap although no message row is left. The ledger
 * holds only the number's keyed fingerprint and when its SMS was written, commits or rolls back
 * with the booking, and never hears of the demo block.
 *
 * <p>This test builds an application context of its own, with a database container of its own, and
 * a key it knows, so it can compute the fingerprint the ledger must hold. The clock stands at 08:00
 * on Monday 26 October 2026 in Johannesburg.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
@TestPropertySource(
    properties = {
      "branchbook.sms.recipient-daily-cap=5",
      "branchbook.sms.recipient-cap-key=" + RecipientCapLedgerIT.KEY
    })
class RecipientCapLedgerIT {

  /** The ledger's key in this context: a test value, never a deployment's. */
  static final String KEY = "a-test-key-a-test-key-a-test-key-a-test-key";

  /** A number just past the demo block, which a deployment with the AWS gateway really texts. */
  private static final String OUTSIDE_THE_BLOCK = "082 555 0200";

  /** The same number in E.164, the form the fingerprint is taken of. */
  private static final String OUTSIDE_THE_BLOCK_E164 = "+27825550200";

  /** The number in any of its forms: national, international, with or without separators. */
  private static final String ANY_FORM_OF_THE_NUMBER = "(?s).*555\\D{0,3}0200.*";

  private static final String IN_THE_BLOCK = "082 555 0150";

  private static final String TUESDAY_0900 = "2026-10-27T09:00:00+02:00";

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private NotificationDispatcher dispatcher;

  @Autowired private MutableClock clock;

  /**
   * The regression: book, cancel and let the dispatcher send and delete the cancellation, twice,
   * and no message is left to count; yet the number has been written four SMS. The fifth, a
   * booking's confirmation, is still sent, and the cancel after it meets the cap: its SMS is
   * reported FAILED with {@code RECIPIENT_DAILY_CAP} and never stored. So is the next booking's.
   */
  @Test
  void bookingAndCancellingInTurnReachesTheCapThoughEveryMessageIsDeleted() {
    for (int round = 1; round <= 2; round++) {
      MvcTestResult booked = bookLater(OUTSIDE_THE_BLOCK);
      assertThat(cancelLater(booked)).hasStatus(HttpStatus.OK);
      dispatcher.runOnce(clock.instant());
      assertThat(messages()).as("round %d leaves no message", round).isEmpty();
    }
    MvcTestResult fifth = bookLater(OUTSIDE_THE_BLOCK);
    assertThat(fifth).bodyJson().extractingPath("$.notifications[0].status").isEqualTo("PENDING");
    dispatcher.runOnce(clock.instant());

    MvcTestResult sixth = cancelLater(fifth);

    assertThat(sixth).hasStatus(HttpStatus.OK);
    assertThat(sixth)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": "CANCELLED", "notifications": [
              {"kind": "CANCELLATION", "status": "FAILED", "failureReason": "RECIPIENT_DAILY_CAP",
               "attempts": 0}]}""");
    assertThat(messages())
        .as("the sent confirmation goes with its booking, and the capped SMS is never stored")
        .isEmpty();
    assertThat(ledgerRows()).hasSize(5);

    MvcTestResult seventh = bookLater(OUTSIDE_THE_BLOCK);

    assertThat(seventh).hasStatus(HttpStatus.CREATED);
    assertThat(messages()).containsExactly("CONFIRMATION FAILED RECIPIENT_DAILY_CAP 0");
    assertThat(ledgerRows()).as("a capped SMS adds no row").hasSize(5);
  }

  /**
   * The ledger holds, for each SMS, the HMAC-SHA256 of the number's E.164 form under the key, and
   * when it was written: two columns, and nothing of the number in any form.
   */
  @Test
  void theLedgerHoldsOnlyTheNumbersKeyedFingerprint() throws GeneralSecurityException {
    MvcTestResult booked = bookLater(OUTSIDE_THE_BLOCK);
    Instant bookedAt = clock.instant();
    cancelLater(booked);
    Instant cancelledAt = clock.instant();

    String fingerprint = hmacSha256(KEY, OUTSIDE_THE_BLOCK_E164);
    assertThat(ledgerRows())
        .containsExactly(fingerprint + " " + bookedAt, fingerprint + " " + cancelledAt);
    assertThat(fingerprint).matches("[0-9a-f]{64}");
    assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns"
                    + " WHERE table_name = 'sms_recipient_sends' ORDER BY ordinal_position",
                String.class))
        .containsExactly("recipient_fingerprint", "written_at");
    assertThat(jdbc.queryForList("SELECT t::text FROM sms_recipient_sends t", String.class))
        .hasSize(2)
        .noneMatch(row -> row.matches(ANY_FORM_OF_THE_NUMBER));
  }

  /** A booking that fails after its SMS is written rolls its ledger row back with it. */
  @Test
  void aRolledBackBookingAddsNoRow() {
    jdbc.execute(
        """
        CREATE FUNCTION test_fail_key_completion() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          RAISE EXCEPTION 'test: completing the idempotency key fails';
        END $$""");
    jdbc.execute(
        """
        CREATE TRIGGER test_fail_key_completion BEFORE UPDATE ON idempotency_keys
        FOR EACH ROW EXECUTE FUNCTION test_fail_key_completion()""");
    MvcTestResult failed;
    try {
      failed = bookLater(OUTSIDE_THE_BLOCK);
    } finally {
      jdbc.execute("DROP TRIGGER test_fail_key_completion ON idempotency_keys");
      jdbc.execute("DROP FUNCTION test_fail_key_completion()");
    }

    assertThat(failed).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(messages()).isEmpty();
    assertThat(ledgerRows()).isEmpty();
  }

  /**
   * The demo block is always simulated, so the cap never counts it and the ledger never holds it.
   */
  @Test
  void theDemoBlockWritesNothing() {
    MvcTestResult booked = book(mvc, body("SAN", "FICA", TUESDAY_0900, IN_THE_BLOCK));
    assertThat(cancel(mvc, reference(booked), "0150")).hasStatus(HttpStatus.OK);

    assertThat(messages()).containsExactly("CANCELLATION PENDING null 0");
    assertThat(ledgerRows()).isEmpty();
  }

  /** Every message, oldest first: its kind, status, failure reason and attempts. */
  private List<String> messages() {
    return jdbc.queryForList(
        "SELECT kind || ' ' || status || ' ' || coalesce(failure_reason, 'null') || ' ' || attempts"
            + " FROM notification_messages ORDER BY created_at",
        String.class);
  }

  /** Every ledger row, oldest first: its fingerprint and when its SMS was written. */
  private List<String> ledgerRows() {
    return jdbc.query(
        "SELECT recipient_fingerprint, written_at FROM sms_recipient_sends ORDER BY written_at",
        (rs, row) ->
            rs.getString("recipient_fingerprint")
                + " "
                + rs.getObject("written_at", OffsetDateTime.class).toInstant());
  }

  /** The fingerprint as the test works it out: HMAC-SHA256 under the key, in lower-case hex. */
  private static String hmacSha256(String key, String text) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
  }

  private static String reference(MvcTestResult booked) {
    return JsonPath.read(
        new String(booked.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8),
        "$.reference");
  }

  /**
   * Books Tuesday's 09:00 FICA slot a minute after the last request, so every row has an instant of
   * its own. A cancel deletes the booking, so the same slot is free again each time.
   */
  private MvcTestResult bookLater(String mobile) {
    clock.advance(Duration.ofMinutes(1));
    return book(mvc, body("SAN", "FICA", TUESDAY_0900, mobile));
  }

  /** Cancels a minute after the last request, with the last four digits of the number outside. */
  private MvcTestResult cancelLater(MvcTestResult booked) {
    clock.advance(Duration.ofMinutes(1));
    return cancel(mvc, reference(booked), "0200");
  }
}
