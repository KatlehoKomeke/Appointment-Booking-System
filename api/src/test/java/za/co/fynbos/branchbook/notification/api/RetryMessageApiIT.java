package za.co.fynbos.branchbook.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;
import static za.co.fynbos.branchbook.support.Bookings.cancel;
import static za.co.fynbos.branchbook.support.Bookings.retry;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import za.co.fynbos.branchbook.notification.application.NotificationDispatcher;
import za.co.fynbos.branchbook.support.BookingPause;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;
import za.co.fynbos.branchbook.support.ResponseText;

/**
 * FR-18, BR-24: {@code POST /api/v1/messages/{id}/retry}, the simulated inbox's Retry button. It
 * makes a message whose delivery failed due again, and leaves every other message exactly as it
 * was, among them the confirmation of an appointment that is cancelled or has started; the inbox's
 * {@code retryable} flag says which is which by the same rule. A failure the dispatcher records
 * after five refusals is set here with SQL, so the next tick, through the simulated gateway, can
 * deliver it. BR-14: a cancellation whose appointment the cancel deleted is retried, or left alone,
 * with no appointment, until it is final and deleted. The clock stands at 08:00 on Monday 26
 * October 2026 in Johannesburg; the numbers are synthetic.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class RetryMessageApiIT {

  private static final String TUESDAY_0930 = "2026-10-27T09:30:00+02:00";

  /** Tuesday 27 October 2026, 09:30 in Johannesburg. */
  private static final Instant TUESDAY_0930_UTC = Instant.parse("2026-10-27T07:30:00Z");

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private MutableClock clock;

  @Autowired private NotificationDispatcher dispatcher;

  @Autowired private DataSource dataSource;

  /**
   * FR-18: a delivery that failed is made due now, with a clean slate, and the next tick sends it.
   */
  @Test
  void retryOfDeliveryFailedResetsItAndTheNextTickSendsIt() throws Exception {
    String reference = reference(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0106")));
    String id = confirmationId();
    fail(id, "DELIVERY_FAILED", "SIMULATED_FAILURE", 5);
    assertThat(inboxEntry()).containsEntry("retryable", true);
    clock.advance(Duration.ofMinutes(3));

    MvcTestResult retried = retry(mvc, id);

    assertThat(retried).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
    assertThat(retried)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"id": "%s", "reference": "%s", "kind": "CONFIRMATION", "channel": "SMS",
             "recipientMasked": "082 *** ****", "status": "PENDING", "failureReason": null,
             "retryable": false, "attempts": 0, "nextAttemptAt": "2026-10-26T08:03:00+02:00",
             "lastError": null, "sentAt": null}"""
                .formatted(id, reference));
    assertThat(ResponseText.withoutIdentifiers(retried.getResponse().getContentAsString()))
        .doesNotContain("0106");
    assertThat(dispatcher.runOnce(clock.instant())).isOne();
    assertThat(row(id)).containsEntry("status", "SENT").containsEntry("attempts", 1);
  }

  /** FR-18: a message whose outcome could not be recorded is made due again too. */
  @Test
  void retryOfDispatchErrorResetsIt() {
    book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0107"));
    String id = confirmationId();
    fail(id, "DISPATCH_ERROR", "MARK_FAILED", 1);

    assertThat(retry(mvc, id)).hasStatus(HttpStatus.OK);

    assertThat(row(id))
        .containsEntry("status", "PENDING")
        .containsEntry("attempts", 0)
        .containsEntry("failure_reason", null)
        .containsEntry("last_error", null);
  }

  /** FR-18: a message still PENDING, or SENT, is returned as it is, and nothing is written. */
  @Test
  void retryOfPendingOrSentIsANoOpThatReturnsTheMessageUnchanged() {
    book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0108"));
    String id = confirmationId();

    for (String status : new String[] {"PENDING", "SENT"}) {
      if (status.equals("SENT")) {
        assertThat(dispatcher.runOnce(clock.instant())).isOne();
      }
      Map<String, Object> before = row(id);
      clock.advance(Duration.ofMinutes(1));

      MvcTestResult retried = retry(mvc, id);

      assertThat(retried).hasStatus(HttpStatus.OK);
      assertThat(retried)
          .bodyJson()
          .isLenientlyEqualTo(
              """
              {"status": "%s", "retryable": false, "failureReason": null}"""
                  .formatted(status));
      assertThat(row(id)).as(status).isEqualTo(before);
    }
  }

  /**
   * BR-24: a confirmation the dispatcher superseded, because its appointment was found cancelled,
   * stays superseded. A retry returns it unchanged, and the inbox never offers one.
   */
  @Test
  void retryOfSupersededConfirmationIsNoOp() {
    book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0109"));
    String id = confirmationId();
    fail(id, "SUPERSEDED_BY_CANCELLATION", null, 0);
    assertThat(inboxEntry()).containsEntry("retryable", false);
    Map<String, Object> before = row(id);

    MvcTestResult retried = retry(mvc, id);

    assertThat(retried).hasStatus(HttpStatus.OK);
    assertThat(retried)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"kind": "CONFIRMATION", "status": "FAILED",
             "failureReason": "SUPERSEDED_BY_CANCELLATION", "retryable": false}""");
    assertThat(row(id)).isEqualTo(before);
  }

  /**
   * BR-24: even a confirmation whose delivery failed is never sent again once its appointment is
   * cancelled. A cancel deletes both, so here the appointment is cancelled by SQL, as one cancelled
   * before cancels deleted their bookings was left, and its confirmation set FAILED.
   */
  @Test
  void retryOfAFailedConfirmationOfACancelledAppointmentIsANoOp() {
    book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0110"));
    jdbc.update(
        "UPDATE appointments SET status = 'CANCELLED', cancelled_at = updated_at,"
            + " version = version + 1");
    String id = confirmationId();
    fail(id, "DELIVERY_FAILED", "SIMULATED_FAILURE", 5);
    Map<String, Object> before = row(id);

    MvcTestResult retried = retry(mvc, id);

    assertThat(retried)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": "FAILED", "failureReason": "DELIVERY_FAILED", "retryable": false}""");
    assertThat(row(id)).isEqualTo(before);
  }

  /**
   * BR-24, BR-25: once the appointment has started, its failed confirmation is no longer worth
   * sending: at the start the inbox stops offering it and a retry leaves it as it is; one second
   * before, both still would.
   */
  @Test
  void retryOfAFailedConfirmationOfAStartedAppointmentIsANoOp() {
    book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0122"));
    String id = confirmationId();
    fail(id, "DELIVERY_FAILED", "SIMULATED_FAILURE", 5);
    Map<String, Object> before = row(id);

    clock.setInstant(TUESDAY_0930_UTC);
    assertThat(inboxEntry()).containsEntry("retryable", false);
    MvcTestResult atTheStart = retry(mvc, id);

    assertThat(atTheStart)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": "FAILED", "failureReason": "DELIVERY_FAILED", "retryable": false}""");
    assertThat(row(id)).isEqualTo(before);
    clock.setInstant(TUESDAY_0930_UTC.minusSeconds(1));
    assertThat(inboxEntry()).containsEntry("retryable", true);
    assertThat(retry(mvc, id)).bodyJson().extractingPath("$.status").isEqualTo("PENDING");
  }

  /**
   * FR-18: the cancellation guard is for confirmations only. A cancellation whose delivery failed
   * is still worth sending, though its appointment is gone. The dispatcher deletes one that fails
   * for good, so here an operator's SQL set it FAILED.
   */
  @Test
  void retryOfAFailedCancellationResetsIt() {
    String reference = reference(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0123")));
    assertThat(cancel(mvc, reference, "0123")).hasStatus(HttpStatus.OK);
    String id = cancellationId();
    fail(id, "DELIVERY_FAILED", "SIMULATED_FAILURE", 5);

    MvcTestResult retried = retry(mvc, id);

    assertThat(retried)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"reference": "APT-****-****", "kind": "CANCELLATION", "status": "PENDING",
             "attempts": 0, "retryable": false}""");
  }

  /**
   * BR-14, FR-18: a cancellation whose appointment the cancel deleted is shown with APT-****-****,
   * its recipient still redacted. While it is PENDING a retry is the same no-op as for any PENDING
   * message; once it is sent it is deleted, so it leaves the inbox and its retry is 404
   * MESSAGE_NOT_FOUND.
   */
  @Test
  void retryOfACancellationWithNoAppointmentIsANoOpUntilItIsSentThen404() throws Exception {
    String reference = reference(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0125")));
    assertThat(cancel(mvc, reference, "0125")).hasStatus(HttpStatus.OK);
    String id = cancellationId();
    Map<String, Object> before = row(id);
    clock.advance(Duration.ofMinutes(1));

    MvcTestResult pending = retry(mvc, id);

    assertThat(pending).hasStatus(HttpStatus.OK);
    assertThat(pending)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"id": "%s", "reference": "APT-****-****", "kind": "CANCELLATION",
             "recipientMasked": "082 *** ****", "status": "PENDING", "retryable": false,
             "attempts": 0, "createdAt": "2026-10-26T08:00:00+02:00"}"""
                .formatted(id));
    assertThat(ResponseText.withoutIdentifiers(pending.getResponse().getContentAsString()))
        .doesNotContain("0125");
    assertThat(row(id)).isEqualTo(before);
    assertThat(dispatcher.runOnce(clock.instant())).isOne();

    assertThat(retry(mvc, id))
        .hasStatus(HttpStatus.NOT_FOUND)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("MESSAGE_NOT_FOUND");
    assertThat(mvc.get().uri("/api/v1/messages"))
        .bodyJson()
        .extractingPath("$.messages")
        .isEqualTo(List.of());
  }

  /**
   * BR-24, FR-18: a cancel that arrives while a retry holds the confirmation still deletes it. The
   * retry keeps the message locked from its read to its commit, so the cancel waits for it and then
   * deletes the confirmation the retry has just made due. A test-only statement trigger holds the
   * retry at its reset, after it has locked the message and read the appointment as not cancelled,
   * until the cancel is seen waiting for that lock. Were the lock let go before the reset, the
   * cancel would commit in between and the reset would find no message to bring back.
   */
  @Test
  void cancelCommittingWhileARetryHoldsTheMessageStillDeletesIt() throws Exception {
    String reference = reference(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0124")));
    String id = confirmationId();
    fail(id, "DELIVERY_FAILED", "SIMULATED_FAILURE", 5);
    MvcTestResult retried;
    MvcTestResult cancelled;
    try (BookingPause pause =
        BookingPause.beforeFirstStatement("UPDATE", "notification_messages", dataSource, jdbc)) {
      CompletableFuture<MvcTestResult> retrying =
          CompletableFuture.supplyAsync(() -> retry(mvc, id));
      pause.awaitWaiting(1, 0);
      CompletableFuture<MvcTestResult> cancelling =
          CompletableFuture.supplyAsync(() -> cancel(mvc, reference, "0124"));
      pause.awaitWaiting(1, 1);

      pause.release();
      retried = retrying.get(10, TimeUnit.SECONDS);
      cancelled = cancelling.get(10, TimeUnit.SECONDS);
    }

    assertThat(retried).hasStatus(HttpStatus.OK);
    assertThat(retried).bodyJson().extractingPath("$.status").isEqualTo("PENDING");
    assertThat(cancelled).hasStatus(HttpStatus.OK);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification_messages WHERE id = ?::uuid", Integer.class, id))
        .as("the confirmation the retry made due is deleted")
        .isZero();
    assertThat(dispatcher.runOnce(clock.instant()))
        .as("the cancellation is sent, and the confirmation is not")
        .isOne();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_messages", Integer.class))
        .isZero();
  }

  /** FR-18: an id no message has is 404 MESSAGE_NOT_FOUND. */
  @Test
  void retryOfUnknownIdReturns404MessageNotFound() {
    MvcTestResult missing = retry(mvc, UUID.randomUUID().toString());

    assertThat(missing)
        .hasStatus(HttpStatus.NOT_FOUND)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(missing)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": 404, "code": "MESSAGE_NOT_FOUND", "title": "Message not found",
             "correlationId": "test-retry"}""");
  }

  /** An id that is not a UUID is a 400 naming the path parameter, as other parameters are. */
  @Test
  void retryWithMalformedIdReturns400NamingTheParameter() {
    MvcTestResult malformed = retry(mvc, "not-a-uuid");

    assertThat(malformed).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(malformed).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    assertThat(malformed)
        .bodyJson()
        .extractingPath("$.errors[*]['field','code']")
        .asArray()
        .containsExactly(Map.of("field", "id", "code", "PATTERN"));
  }

  /** Sets a message FAILED, as the dispatcher would after its attempts. */
  private void fail(String id, String reason, @Nullable String lastError, int attempts) {
    jdbc.update(
        "UPDATE notification_messages SET status = 'FAILED', failure_reason = ?, last_error = ?,"
            + " attempts = ? WHERE id = ?::uuid",
        reason,
        lastError,
        attempts,
        id);
  }

  private String cancellationId() {
    return jdbc.queryForObject(
        "SELECT id::text FROM notification_messages WHERE kind = 'CANCELLATION'", String.class);
  }

  private String confirmationId() {
    return jdbc.queryForObject(
        "SELECT id::text FROM notification_messages WHERE kind = 'CONFIRMATION'", String.class);
  }

  private Map<String, Object> row(String id) {
    return jdbc.queryForMap(
        "SELECT status, attempts, next_attempt_at, last_error, failure_reason, sent_at"
            + " FROM notification_messages WHERE id = ?::uuid",
        id);
  }

  /** The inbox's entry for the confirmation, the only message of these tests' appointments. */
  private Map<String, Object> inboxEntry() {
    return JsonPath.read(
        new String(
            mvc.get().uri("/api/v1/messages").exchange().getResponse().getContentAsByteArray(),
            StandardCharsets.UTF_8),
        "$.messages[0]");
  }

  private static String reference(MvcTestResult booked) {
    assertThat(booked).hasStatus(HttpStatus.CREATED);
    return JsonPath.read(
        new String(booked.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8),
        "$.reference");
  }
}
