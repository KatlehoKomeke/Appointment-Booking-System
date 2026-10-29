package za.co.fynbos.branchbook.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;
import static za.co.fynbos.branchbook.support.Bookings.cancel;
import static za.co.fynbos.branchbook.support.Bookings.lookup;
import static za.co.fynbos.branchbook.support.Bookings.retry;

import com.jayway.jsonpath.JsonPath;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fynbos.branchbook.booking.application.AppointmentDirectory;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.LockWaits;
import za.co.fynbos.branchbook.support.MutableClock;
import za.co.fynbos.branchbook.support.TestClockConfiguration;

/**
 * BR-17, FR-15, NFR-13: the dispatcher delivers PENDING messages through the gateway, one
 * transaction each, backing off after a retryable refusal and failing a message at once on a
 * permanent one. BR-24, FR-18: it never delivers a confirmation after its appointment's
 * cancellation, and a retry waits for a send in flight. BR-14: a cancellation, which outlives its
 * deleted appointment, is deleted once it is final. NFR-13: while the circuit breaker is open, no
 * message is claimed and no attempt is spent, and the breaker recovers through half-open. Its own
 * loop is off in the tests, so each test calls {@link NotificationDispatcher#runOnce} with the
 * instant it wants: no test sleeps. The numbers are synthetic; the simulated gateway refuses those
 * ending 0000.
 *
 * <p>The application's dispatcher has the default jitter, so a test that uses it reads each wait
 * from the row; a dispatcher a test builds itself has none, so its waits are exact.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class NotificationDispatcherIT {

  /** The code of AWS's refusal of a number the SMS sandbox has not verified: permanent. */
  private static final String NOT_VERIFIED = "AWS_CONFLICT_DESTINATION_PHONE_NUMBER_NOT_VERIFIED";

  /** The code of AWS's throttling: retryable. */
  private static final String THROTTLED = "AWS_THROTTLED";

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private MutableClock clock;

  @Autowired private NotificationDispatcher dispatcher;

  @Autowired private NotificationMessageRepository messages;

  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired private AppointmentDirectory appointments;

  @Autowired private OutboxPendingGauge pending;

  @Autowired private OutboxHealthGauges health;

  @Autowired private DispatcherProperties settings;

  /** The clock the circuits built by these tests wait on, apart from the application's. */
  private final MutableClock circuitClock =
      new MutableClock(TestClockConfiguration.DEFAULT_INSTANT);

  /** FR-15, FR-16: a delivered SMS is SENT with the gateway's id, and the appointment says so. */
  @Test
  void marksSentWithProviderMessageId() throws Exception {
    String reference =
        reference(book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0192")));
    Instant now = clock.instant();

    assertThat(dispatcher.runOnce(now)).isOne();

    Map<String, Object> row = onlyMessage();
    assertThat(row)
        .containsEntry("status", "SENT")
        .containsEntry("attempts", 1)
        .containsEntry("last_error", null)
        .containsEntry("failure_reason", null);
    assertThat((String) row.get("provider_message_id")).startsWith("sim-");
    assertThat(instant(row, "sent_at")).isEqualTo(now);
    assertThat(lookup(mvc, reference, "0192"))
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"notifications": [{"status": "SENT", "attempts": 1,
              "sentAt": "2026-10-26T08:00:00+02:00"}]}""");
    assertThat(dispatcher.runOnce(now)).as("nothing is left to send").isZero();
  }

  /**
   * BR-17: a refused SMS is tried again after up to 2, 4, 8 and 16 seconds, each wait shortened at
   * random by at most a fifth, and the fifth refusal marks it FAILED with DELIVERY_FAILED: within
   * 30 seconds of the first attempt, never later. A message is never tried before its wait is over.
   * The simulated gateway's refusal of a number ending 0000 is retryable, so the demo still shows
   * all five attempts.
   */
  @Test
  void retriesWithBackoffThenMarksFailedAfterFiveAttempts() {
    book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0000"));
    Instant start = clock.instant();

    Instant attempt = start;
    for (int retry = 1; retry <= 4; retry++) {
      assertThat(dispatcher.runOnce(attempt)).isOne();
      Map<String, Object> row = onlyMessage();
      assertThat(row)
          .containsEntry("status", "PENDING")
          .containsEntry("attempts", retry)
          .containsEntry("last_error", "SIMULATED_FAILURE");
      Duration nominal = Duration.ofSeconds(1L << retry);
      Instant due = instant(row, "next_attempt_at");
      assertThat(Duration.between(attempt, due))
          .as("within a fifth below the nominal %s", nominal)
          .isBetween(nominal.multipliedBy(4).dividedBy(5), nominal);
      assertThat(dispatcher.runOnce(due.minusMillis(1))).as("not due a millisecond early").isZero();
      attempt = due;
    }
    assertThat(attempt)
        .as("the fifth attempt is due by 30 s")
        .isBeforeOrEqualTo(start.plusSeconds(30));
    assertThat(dispatcher.runOnce(attempt)).isOne();

    assertThat(onlyMessage())
        .containsEntry("status", "FAILED")
        .containsEntry("failure_reason", "DELIVERY_FAILED")
        .containsEntry("attempts", 5)
        .containsEntry("sent_at", null);
    assertThat(dispatcher.runOnce(start.plusSeconds(3600)))
        .as("a FAILED message stays FAILED")
        .isZero();
    assertThat(jdbc.queryForObject("SELECT status FROM appointments", String.class))
        .as("BR-16: the appointment never depends on its delivery")
        .isEqualTo("CONFIRMED");
  }

  /**
   * BR-17, BR-19: a refusal the gateway calls permanent, such as AWS's for a number the SMS sandbox
   * has not verified, marks the message FAILED with DELIVERY_FAILED on that attempt, with the code
   * as its last error, and it is never tried again. The log line gives the code beside the
   * message's id, and the log holds the number in no form.
   */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void permanentRefusalMarksFailedOnItsFirstAttemptAndLogsItsCode(CapturedOutput output) {
    book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0181"));
    UUID confirmation = messageId("CONFIRMATION");
    ConcurrentLinkedQueue<UUID> tried = new ConcurrentLinkedQueue<>();
    NotificationDispatcher refusing =
        dispatcherWith(refusingInto(tried, MessageGateway.Refused.permanent(NOT_VERIFIED)));
    Instant start = clock.instant();

    assertThat(refusing.runOnce(start)).isOne();

    assertThat(onlyMessage())
        .containsEntry("status", "FAILED")
        .containsEntry("failure_reason", "DELIVERY_FAILED")
        .containsEntry("attempts", 1)
        .containsEntry("last_error", NOT_VERIFIED)
        .containsEntry("sent_at", null);
    assertThat(refusing.runOnce(start.plusSeconds(3600))).as("never tried again").isZero();
    assertThat(tried).containsExactly(confirmation);
    assertThat(output.getAll())
        .contains("outbox.failed messageId=" + confirmation + " attempts=1 code=" + NOT_VERIFIED)
        .doesNotContain("outbox.retry messageId=")
        .doesNotContainPattern(anyFormOf("0181"));
  }

  /**
   * BR-14, BR-17: a cancellation the gateway refuses for good is final on that attempt, so it is
   * deleted at once, in the same transaction, rather than tried four more times.
   */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void cancellationRefusedPermanentlyIsDeletedOnItsFirstAttempt(CapturedOutput output) {
    String reference =
        reference(book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0182")));
    assertThat(cancel(mvc, reference, "0182")).hasStatus(HttpStatus.OK);
    UUID cancellation = messageId("CANCELLATION");
    ConcurrentLinkedQueue<UUID> tried = new ConcurrentLinkedQueue<>();

    int handled =
        dispatcherWith(refusingInto(tried, MessageGateway.Refused.permanent(NOT_VERIFIED)))
            .runOnce(clock.instant());

    assertThat(handled).isOne();
    assertThat(tried).containsExactly(cancellation);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_messages", Integer.class))
        .as("a cancellation refused for good is not kept")
        .isZero();
    assertThat(output.getAll())
        .contains("outbox.failed messageId=" + cancellation + " attempts=1 code=" + NOT_VERIFIED)
        .contains("outbox.deleted messageId=" + cancellation + " kind=CANCELLATION");
  }

  /**
   * BR-17: a retryable refusal, such as AWS's throttling, keeps the backoff: the message is tried
   * again after 2, 4, 8 and 16 seconds, never early, and the fifth refusal marks it FAILED with
   * DELIVERY_FAILED. Every outbox.retry line and the outbox.failed line give the code beside the
   * message's id, and the log holds the number in no form.
   */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void retryableRefusalBacksOffAndMarksFailedOnTheFifthAttempt(CapturedOutput output) {
    book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0183"));
    UUID confirmation = messageId("CONFIRMATION");
    ConcurrentLinkedQueue<UUID> tried = new ConcurrentLinkedQueue<>();
    NotificationDispatcher throttled =
        dispatcherWith(refusingInto(tried, MessageGateway.Refused.retryable(THROTTLED)));
    Instant start = clock.instant();

    for (int second : new int[] {0, 2, 6, 14}) {
      assertThat(throttled.runOnce(start.plusSeconds(second))).isOne();
      assertThat(onlyMessage())
          .containsEntry("status", "PENDING")
          .containsEntry("last_error", THROTTLED);
    }
    assertThat(throttled.runOnce(start.plusSeconds(30).minusMillis(1)))
        .as("not due a millisecond early")
        .isZero();
    assertThat(throttled.runOnce(start.plusSeconds(30))).isOne();

    assertThat(onlyMessage())
        .containsEntry("status", "FAILED")
        .containsEntry("failure_reason", "DELIVERY_FAILED")
        .containsEntry("attempts", 5)
        .containsEntry("last_error", THROTTLED);
    assertThat(tried).hasSize(5).containsOnly(confirmation);
    String message = "messageId=" + confirmation + " attempts=";
    assertThat(output.getAll())
        .contains("outbox.retry " + message + "1 retryInSeconds=2 code=" + THROTTLED)
        .contains("outbox.retry " + message + "4 retryInSeconds=16 code=" + THROTTLED)
        .contains("outbox.failed " + message + "5 code=" + THROTTLED)
        .doesNotContainPattern(anyFormOf("0183"));
  }

  /**
   * NFR-13: two dispatchers at once never send one message twice. The first holds its message in
   * the gateway; the second skips that locked row and sends the other one.
   */
  @Test
  void twoDispatchersNeverSendTheSameMessageTwice() throws Exception {
    book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0193"));
    book(mvc, body("BEL", "CARD", "2026-10-28T10:00:00+02:00", "082 555 0194"));
    ConcurrentLinkedQueue<UUID> sent = new ConcurrentLinkedQueue<>();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch letGo = new CountDownLatch(1);
    MessageGateway slow =
        message -> {
          sent.add(message.id());
          holding.countDown();
          await(letGo);
          return new MessageGateway.Accepted("slow-" + message.id());
        };
    MessageGateway quick =
        message -> {
          sent.add(message.id());
          return new MessageGateway.Accepted("quick-" + message.id());
        };
    NotificationDispatcher first = dispatcherWith(slow);
    NotificationDispatcher second = dispatcherWith(quick);
    Instant now = clock.instant();

    CompletableFuture<Integer> firstTick = CompletableFuture.supplyAsync(() -> first.runOnce(now));
    assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
    int secondHandled = second.runOnce(now);
    letGo.countDown();

    assertThat(secondHandled).isOne();
    assertThat(firstTick.get(5, TimeUnit.SECONDS)).isOne();
    assertThat(sent).hasSize(2).doesNotHaveDuplicates();
    assertThat(jdbc.queryForList("SELECT status FROM notification_messages", String.class))
        .containsExactly("SENT", "SENT");
  }

  /**
   * BR-17: a message whose outcome cannot be recorded is set FAILED with DISPATCH_ERROR and
   * MARK_FAILED, so it is never sent again, and the rest of the batch is still delivered. A
   * test-only trigger makes the database refuse that one row's SENT mark.
   */
  @Test
  void poisonedRowDoesNotBlockTheBatch() {
    book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0195"));
    book(mvc, body("BEL", "CARD", "2026-10-28T10:00:00+02:00", "082 555 0196"));
    UUID poisoned =
        jdbc.queryForObject(
            "SELECT id FROM notification_messages ORDER BY next_attempt_at, id LIMIT 1",
            UUID.class);
    jdbc.execute(
        """
        CREATE OR REPLACE FUNCTION test_poison_sent_mark() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          RAISE EXCEPTION 'test: this row cannot be marked SENT';
        END $$""");
    jdbc.execute(
        """
        CREATE TRIGGER test_poison_sent_mark BEFORE UPDATE ON notification_messages FOR EACH ROW
        WHEN (NEW.status = 'SENT' AND NEW.id = '%s') EXECUTE FUNCTION test_poison_sent_mark()"""
            .formatted(poisoned));
    int handled;
    try {
      handled = dispatcher.runOnce(clock.instant());
    } finally {
      jdbc.execute("DROP TRIGGER test_poison_sent_mark ON notification_messages");
      jdbc.execute("DROP FUNCTION test_poison_sent_mark()");
    }

    assertThat(handled).isEqualTo(2);
    assertThat(
            jdbc.queryForMap(
                "SELECT status, failure_reason, last_error FROM notification_messages WHERE id = ?",
                poisoned))
        .containsEntry("status", "FAILED")
        .containsEntry("failure_reason", "DISPATCH_ERROR")
        .containsEntry("last_error", "MARK_FAILED");
    assertThat(
            jdbc.queryForList(
                "SELECT status FROM notification_messages WHERE id <> ?", String.class, poisoned))
        .containsExactly("SENT");
    assertThat(dispatcher.runOnce(clock.instant().plusSeconds(60))).as("never sent again").isZero();
  }

  /** A gateway that throws is treated as a refusal: the message is tried again later. */
  @Test
  void gatewayThatThrowsIsTriedAgain() {
    book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0189"));
    MessageGateway broken =
        message -> {
          throw new IllegalStateException("test: the provider is down");
        };

    int handled = dispatcherWith(broken).runOnce(clock.instant());

    assertThat(handled).isOne();
    assertThat(onlyMessage())
        .containsEntry("status", "PENDING")
        .containsEntry("attempts", 1)
        .containsEntry("last_error", "GATEWAY_ERROR");
  }

  /**
   * An Error from the gateway, such as a missing class, marks that message FAILED with
   * DISPATCH_ERROR rather than leaving it to be re-sent on every tick, and the batch carries on.
   */
  @Test
  void gatewayErrorMarksTheMessageAndTheBatchCarriesOn() {
    book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0187"));
    book(mvc, body("BEL", "CARD", "2026-10-28T10:00:00+02:00", "082 555 0188"));
    UUID first =
        jdbc.queryForObject(
            "SELECT id FROM notification_messages ORDER BY next_attempt_at, id LIMIT 1",
            UUID.class);
    MessageGateway failsOnce =
        message -> {
          if (message.id().equals(first)) {
            throw new LinkageError("test: a class the provider client needs is missing");
          }
          return new MessageGateway.Accepted("sim-" + message.id());
        };

    int handled = dispatcherWith(failsOnce).runOnce(clock.instant());

    assertThat(handled).isEqualTo(2);
    assertThat(
            jdbc.queryForMap(
                "SELECT status, failure_reason, last_error FROM notification_messages WHERE id = ?",
                first))
        .containsEntry("status", "FAILED")
        .containsEntry("failure_reason", "DISPATCH_ERROR")
        .containsEntry("last_error", "MARK_FAILED");
    assertThat(
            jdbc.queryForList(
                "SELECT status FROM notification_messages WHERE id <> ?", String.class, first))
        .containsExactly("SENT");
  }

  /** NFR-13: one tick handles at most twenty messages, so a tick never runs for long. */
  @Test
  void oneTickHandlesAtMostTwentyMessages() {
    for (int customer = 0; customer < 21; customer++) {
      String time = "%02d:%02d".formatted(9 + customer / 4, (customer % 4) * 15);
      book(
          mvc,
          body("UMH", "CARD", "2026-10-27T" + time + ":00+02:00", "082 555 01" + (60 + customer)));
    }

    assertThat(dispatcher.runOnce(clock.instant())).isEqualTo(20);
    assertThat(dispatcher.runOnce(clock.instant())).isOne();
  }

  /**
   * BR-24, BR-14: once an appointment is cancelled, the next tick sends only its cancellation: the
   * cancel deleted the PENDING confirmation, so there is nothing else to claim. Once SENT, the
   * cancellation is deleted in the same transaction, and the log names it by id only.
   */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void cancelDeletesThePendingConfirmationAndTheSentCancellationIsDeleted(CapturedOutput output) {
    String reference =
        reference(book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0103")));
    assertThat(cancel(mvc, reference, "0103")).hasStatus(HttpStatus.OK);
    UUID cancellation = messageId("CANCELLATION");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_messages", Integer.class))
        .as("only the cancellation is left")
        .isOne();
    ConcurrentLinkedQueue<UUID> sent = new ConcurrentLinkedQueue<>();

    int handled = dispatcherWith(recordingInto(sent)).runOnce(clock.instant());

    assertThat(handled).as("the cancellation, and nothing else").isOne();
    assertThat(sent).containsExactly(cancellation);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_messages", Integer.class))
        .as("BR-14: a sent cancellation is not kept")
        .isZero();
    assertThat(output.getOut())
        .contains("outbox.deleted messageId=" + cancellation + " kind=CANCELLATION");
  }

  /**
   * BR-24: a confirmation already in the gateway when the cancel arrives is delivered before its
   * cancellation, never after it. The cancel waits for the message's row lock; once the send is
   * marked SENT, it deletes that message anyway and queues the cancellation behind it.
   */
  @Test
  void confirmationInFlightWhenTheCancelArrivesIsSentBeforeTheCancellation() throws Exception {
    String reference =
        reference(book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0104")));
    UUID confirmation = messageId("CONFIRMATION");
    ConcurrentLinkedQueue<UUID> sent = new ConcurrentLinkedQueue<>();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch letGo = new CountDownLatch(1);
    MessageGateway held =
        message -> {
          sent.add(message.id());
          holding.countDown();
          await(letGo);
          return new MessageGateway.Accepted("held-" + message.id());
        };
    NotificationDispatcher holder = dispatcherWith(held);
    Instant now = clock.instant();

    CompletableFuture<Integer> tick = CompletableFuture.supplyAsync(() -> holder.runOnce(now));
    CompletableFuture<MvcTestResult> cancelling;
    try {
      assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
      cancelling = CompletableFuture.supplyAsync(() -> cancel(mvc, reference, "0104"));
      LockWaits.await(jdbc, 0, 1);
    } finally {
      // Released even if the test fails, so no held transaction outlives it.
      letGo.countDown();
    }

    assertThat(tick.get(5, TimeUnit.SECONDS)).isPositive();
    assertThat(cancelling.get(5, TimeUnit.SECONDS)).hasStatus(HttpStatus.OK);
    assertThat(jdbc.queryForList("SELECT kind FROM notification_messages", String.class))
        .as("the confirmation the cancel waited for is deleted once it was sent")
        .containsExactly("CANCELLATION");
    UUID cancellation = messageId("CANCELLATION");
    holder.runOnce(now);
    assertThat(sent).containsExactly(confirmation, cancellation);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_messages", Integer.class))
        .isZero();
  }

  /**
   * BR-14, BR-17: a cancellation whose fifth attempt is refused is final, so it is deleted rather
   * than kept FAILED; until then it waits PENDING, with no appointment, like any other message.
   */
  @Test
  void cancellationIsDeletedAfterItsFifthFailedAttempt() {
    String reference =
        reference(book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0000")));
    assertThat(cancel(mvc, reference, "0000")).hasStatus(HttpStatus.OK);
    Instant start = clock.instant();

    for (int second : new int[] {0, 2, 6, 14}) {
      assertThat(dispatcher.runOnce(start.plusSeconds(second))).isOne();
    }
    assertThat(onlyMessage())
        .containsEntry("status", "PENDING")
        .containsEntry("attempts", 4)
        .containsEntry("last_error", "SIMULATED_FAILURE");
    assertThat(dispatcher.runOnce(start.plusSeconds(30))).isOne();

    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_messages", Integer.class))
        .as("a cancellation that failed for good is not kept")
        .isZero();
  }

  /**
   * BR-14, BR-17: a cancellation whose outcome cannot be recorded, here because the gateway threw
   * an Error, would be marked FAILED with DISPATCH_ERROR in a transaction of its own; being final
   * then, it is deleted there instead, and the rest of the batch carries on.
   */
  @Test
  void cancellationWhoseOutcomeCannotBeRecordedIsDeleted() {
    String reference =
        reference(book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0185")));
    assertThat(cancel(mvc, reference, "0185")).hasStatus(HttpStatus.OK);
    book(mvc, body("BEL", "CARD", "2026-10-28T10:00:00+02:00", "082 555 0184"));
    UUID cancellation = messageId("CANCELLATION");
    MessageGateway failsOnTheCancellation =
        message -> {
          if (message.id().equals(cancellation)) {
            throw new LinkageError("test: a class the provider client needs is missing");
          }
          return new MessageGateway.Accepted("sim-" + message.id());
        };

    int handled = dispatcherWith(failsOnTheCancellation).runOnce(clock.instant());

    assertThat(handled).isEqualTo(2);
    assertThat(
            jdbc.queryForList(
                "SELECT kind || ' ' || status FROM notification_messages", String.class))
        .as("the cancellation is deleted, and the other booking's confirmation is sent")
        .containsExactly("CONFIRMATION SENT");
  }

  /**
   * BR-24, defence in depth: the confirmation of an appointment cancelled without a cancel, as one
   * cancelled before cancels deleted their bookings was left, or by hand, is marked superseded when
   * the dispatcher claims it, and never reaches the gateway.
   */
  @Test
  void confirmationOfACancelledAppointmentResetByHandIsSupersededAtTheClaimNotSent() {
    book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0105"));
    jdbc.update(
        "UPDATE appointments SET status = 'CANCELLED', cancelled_at = updated_at,"
            + " version = version + 1");
    ConcurrentLinkedQueue<UUID> sent = new ConcurrentLinkedQueue<>();

    int handled = dispatcherWith(recordingInto(sent)).runOnce(clock.instant());

    assertThat(handled).isOne();
    assertThat(sent).isEmpty();
    assertThat(statusOf("CONFIRMATION")).isEqualTo("FAILED SUPERSEDED_BY_CANCELLATION");
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM notification_messages WHERE kind = 'CONFIRMATION'",
                Integer.class))
        .as("no attempt was made")
        .isZero();
  }

  /**
   * BR-24, BR-17: when the dispatcher cannot ask whether a confirmation's appointment is cancelled,
   * it has sent nothing, so it marks nothing: the claim rolls back, the message stays PENDING with
   * no attempt and no error, and the next tick sends it. Marking it FAILED would record a failure
   * that never happened and leave the customer without a confirmation.
   */
  @Test
  void confirmationWhoseCancellationCheckFailsStaysPendingForTheNextTick() {
    book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0186"));
    AppointmentDirectory unanswering = mock(AppointmentDirectory.class);
    when(unanswering.isCancelled(any()))
        .thenThrow(new QueryTimeoutException("test: the appointments table is locked"));
    ConcurrentLinkedQueue<UUID> sent = new ConcurrentLinkedQueue<>();

    int handled =
        dispatcherWith(recordingInto(sent), unanswering, circuit(defaultCircuit()), settings)
            .runOnce(clock.instant());

    assertThat(handled).as("the tick ends at the failed check").isZero();
    assertThat(sent).isEmpty();
    assertThat(onlyMessage())
        .containsEntry("status", "PENDING")
        .containsEntry("attempts", 0)
        .containsEntry("last_error", null)
        .containsEntry("failure_reason", null);
    assertThat(dispatcher.runOnce(clock.instant())).isOne();
    assertThat(onlyMessage()).containsEntry("status", "SENT");
  }

  /**
   * FR-18: a retry never races the dispatcher. While the fifth, failing attempt of a message is in
   * the gateway, a retry of it waits for the message's lock; once the attempt marks it FAILED, the
   * retry sees that and resets it, rather than answering from the PENDING row it would otherwise
   * have read. The retry runs a second after the tick's instant, so the message it makes due is not
   * yet due for that tick, however the two threads are scheduled once the lock is let go.
   */
  @Test
  void retryWaitsForTheSendInFlightThenResetsTheFailedMessage() throws Exception {
    book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0000"));
    Instant start = clock.instant();
    for (int second : new int[] {0, 2, 6, 14}) {
      assertThat(dispatcher.runOnce(start.plusSeconds(second))).isOne();
    }
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch letGo = new CountDownLatch(1);
    MessageGateway refusesSlowly =
        message -> {
          holding.countDown();
          await(letGo);
          return MessageGateway.Refused.retryable("SIMULATED_FAILURE");
        };
    Instant fifth = start.plusSeconds(30);
    String messageId = messageId("CONFIRMATION").toString();

    CompletableFuture<Integer> tick =
        CompletableFuture.supplyAsync(() -> dispatcherWith(refusesSlowly).runOnce(fifth));
    CompletableFuture<MvcTestResult> retrying;
    try {
      assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
      clock.setInstant(fifth.plusSeconds(1));
      retrying = CompletableFuture.supplyAsync(() -> retry(mvc, messageId));
      LockWaits.await(jdbc, 0, 1);
    } finally {
      // Released even if the test fails, so no held transaction outlives it.
      letGo.countDown();
    }

    assertThat(tick.get(5, TimeUnit.SECONDS)).isOne();
    MvcTestResult retried = retrying.get(5, TimeUnit.SECONDS);
    assertThat(retried).hasStatus(HttpStatus.OK);
    assertThat(retried)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": "PENDING", "attempts": 0, "failureReason": null, "lastError": null,
             "retryable": false, "nextAttemptAt": "2026-10-26T08:00:31+02:00"}""");
  }

  /** A gateway that accepts every message and records the order it was handed them in. */
  private static MessageGateway recordingInto(ConcurrentLinkedQueue<UUID> sent) {
    return message -> {
      sent.add(message.id());
      return new MessageGateway.Accepted("rec-" + message.id());
    };
  }

  /** A gateway that refuses every message the same way and records the order it was tried in. */
  private static MessageGateway refusingInto(
      ConcurrentLinkedQueue<UUID> tried, MessageGateway.Refused refusal) {
    return message -> {
      tried.add(message.id());
      return refusal;
    };
  }

  /**
   * A pattern for a demo number in any of its forms, national or international, with or without
   * separators, by its last four digits.
   */
  private static String anyFormOf(String lastFour) {
    return "555\\D{0,3}" + lastFour;
  }

  /** The id of the one message of a kind. */
  private UUID messageId(String kind) {
    return jdbc.queryForObject(
        "SELECT id FROM notification_messages WHERE kind = ?", UUID.class, kind);
  }

  /** The status and failure reason of the one message of a kind, such as "SENT null". */
  private String statusOf(String kind) {
    return jdbc.queryForObject(
        "SELECT status || ' ' || coalesce(failure_reason, 'null') FROM notification_messages"
            + " WHERE kind = ?",
        String.class,
        kind);
  }

  /**
   * NFR-13, BR-17: once enough calls to the provider fail, the circuit opens and the tick ends at
   * once. While it is open, however often the dispatcher ticks and however long the messages have
   * been due, none is claimed and none spends an attempt: the provider's outage does not use up the
   * customers' five attempts.
   */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void whileTheCircuitIsOpenNoMessageIsClaimedAndNoAttemptIsSpent(CapturedOutput output) {
    bookSix();
    ConcurrentLinkedQueue<UUID> tried = new ConcurrentLinkedQueue<>();
    DeliveryCircuit circuit = circuit(smallCircuit());
    NotificationDispatcher guarded =
        dispatcherWith(refusingInto(tried, MessageGateway.Refused.retryable(THROTTLED)), circuit);
    Instant start = clock.instant();

    assertThat(guarded.runOnce(start)).as("the fourth failure opens it").isEqualTo(4);
    assertThat(circuit.state()).isEqualTo(State.OPEN);
    for (int minute = 1; minute <= 10; minute++) {
      assertThat(guarded.runOnce(start.plusSeconds(60L * minute))).isZero();
    }

    assertThat(tried).hasSize(4).doesNotHaveDuplicates();
    assertThat(attemptsByStatus())
        .as("four tried once, two never tried, all still PENDING")
        .containsExactlyInAnyOrder(
            "PENDING 0", "PENDING 0", "PENDING 1", "PENDING 1", "PENDING 1", "PENDING 1");
    assertThat(output.getAll())
        .containsOnlyOnce("outbox.circuit from=CLOSED to=OPEN")
        .doesNotContainPattern(anyFormOf("01[0-9][0-9]"));
  }

  /**
   * NFR-13: once the open state's wait is over, the next tick sends two messages as probes; when
   * the provider accepts them the circuit closes and the same tick delivers everything else due.
   */
  @Test
  void theCircuitRecoversThroughHalfOpenWhenTheProviderIsBack() {
    bookSix();
    AtomicReference<MessageGateway.Delivery> answer =
        new AtomicReference<>(MessageGateway.Refused.retryable(THROTTLED));
    ConcurrentLinkedQueue<UUID> tried = new ConcurrentLinkedQueue<>();
    MessageGateway provider =
        message -> {
          tried.add(message.id());
          return answer.get();
        };
    DeliveryCircuit circuit = circuit(smallCircuit());
    NotificationDispatcher guarded = dispatcherWith(provider, circuit);
    Instant start = clock.instant();
    assertThat(guarded.runOnce(start)).isEqualTo(4);
    answer.set(new MessageGateway.Accepted("back"));

    circuitClock.advance(Duration.ofSeconds(30));
    assertThat(guarded.runOnce(start.plusSeconds(60))).as("still open").isZero();
    circuitClock.advance(Duration.ofMillis(1));
    int handled = guarded.runOnce(start.plusSeconds(60));

    assertThat(circuit.state()).isEqualTo(State.CLOSED);
    assertThat(handled).as("two probes, then the other four").isEqualTo(6);
    assertThat(tried).hasSize(10);
    assertThat(attemptsByStatus())
        .containsExactlyInAnyOrder("SENT 1", "SENT 1", "SENT 2", "SENT 2", "SENT 2", "SENT 2");
  }

  /**
   * NFR-13: a permanent refusal is a working provider's answer, so however many there are, the
   * circuit stays closed and the tick carries on through the batch.
   */
  @Test
  void permanentRefusalsNeverOpenTheCircuit() {
    bookSix();
    DeliveryCircuit circuit = circuit(smallCircuit());
    ConcurrentLinkedQueue<UUID> tried = new ConcurrentLinkedQueue<>();

    int handled =
        dispatcherWith(refusingInto(tried, MessageGateway.Refused.permanent(NOT_VERIFIED)), circuit)
            .runOnce(clock.instant());

    assertThat(handled).isEqualTo(6);
    assertThat(circuit.state()).isEqualTo(State.CLOSED);
    assertThat(attemptsByStatus()).containsOnly("FAILED 1");
  }

  /**
   * BR-17: the batch size and the attempt that fails a message for good come from {@code
   * branchbook.dispatcher}, here three a tick and two attempts.
   */
  @Test
  void theBatchSizeAndTheLastAttemptComeFromTheSettings() {
    bookSix();
    DispatcherProperties tuned =
        new DispatcherProperties(
            false,
            settings.interval(),
            settings.simulatedLatency(),
            3,
            2,
            noJitter(),
            settings.circuitBreaker());
    NotificationDispatcher guarded =
        dispatcherWith(
            refusingInto(new ConcurrentLinkedQueue<>(), MessageGateway.Refused.retryable("X")),
            appointments,
            circuit(switchedOff()),
            tuned);
    Instant start = clock.instant();

    assertThat(guarded.runOnce(start)).isEqualTo(3);
    assertThat(guarded.runOnce(start)).isEqualTo(3);
    assertThat(guarded.runOnce(start.plusSeconds(2))).isEqualTo(3);
    assertThat(guarded.runOnce(start.plusSeconds(2))).isEqualTo(3);

    assertThat(attemptsByStatus()).containsOnly("FAILED 2");
  }

  /** Six bookings, at three branches, for six demo numbers that the simulated gateway accepts. */
  private void bookSix() {
    String[][] bookings = {
      {"SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0171"},
      {"SAN", "FICA", "2026-10-27T10:00:00+02:00", "082 555 0172"},
      {"BEL", "CARD", "2026-10-28T10:00:00+02:00", "082 555 0173"},
      {"BEL", "CARD", "2026-10-28T10:30:00+02:00", "082 555 0174"},
      {"UMH", "CARD", "2026-10-27T09:00:00+02:00", "082 555 0175"},
      {"UMH", "CARD", "2026-10-27T09:30:00+02:00", "082 555 0176"}
    };
    for (String[] booking : bookings) {
      assertThat(book(mvc, body(booking[0], booking[1], booking[2], booking[3])))
          .hasStatus(HttpStatus.CREATED);
    }
  }

  /** Every message's status and attempts, such as "PENDING 1". */
  private List<String> attemptsByStatus() {
    return jdbc.queryForList(
        "SELECT status || ' ' || attempts FROM notification_messages", String.class);
  }

  /** A circuit that judges the last four calls, once four are made, and opens at half failed. */
  private static DispatcherProperties.CircuitBreaker smallCircuit() {
    return new DispatcherProperties.CircuitBreaker(
        true, 50, 100, Duration.ofSeconds(8), 4, 4, Duration.ofSeconds(30), 2);
  }

  /** A circuit that never opens, for a test about something else that fails every call. */
  private static DispatcherProperties.CircuitBreaker switchedOff() {
    return new DispatcherProperties.CircuitBreaker(
        false, 50, 100, Duration.ofSeconds(8), 10, 5, Duration.ofSeconds(30), 2);
  }

  private DispatcherProperties.CircuitBreaker defaultCircuit() {
    return settings.circuitBreaker();
  }

  /** A circuit of the test's own, on the tests' circuit clock, uncounting simulated refusals. */
  private DeliveryCircuit circuit(DispatcherProperties.CircuitBreaker breaker) {
    return new DeliveryCircuit(breaker, circuitClock, Set.of("SIMULATED_FAILURE"));
  }

  /** The default backoff without jitter: exactly 2, 4, 8 and 16 seconds. */
  private DispatcherProperties.Backoff noJitter() {
    DispatcherProperties.Backoff backoff = settings.backoff();
    return new DispatcherProperties.Backoff(backoff.base(), backoff.multiplier(), backoff.cap(), 0);
  }

  /**
   * A dispatcher like the application's, with a gateway of the test's own, a closed circuit of its
   * own with the default settings, and no jitter.
   */
  private NotificationDispatcher dispatcherWith(MessageGateway gateway) {
    return dispatcherWith(gateway, circuit(defaultCircuit()));
  }

  /** A dispatcher like the application's, with a gateway and a circuit of the test's own. */
  private NotificationDispatcher dispatcherWith(MessageGateway gateway, DeliveryCircuit circuit) {
    return dispatcherWith(gateway, appointments, circuit, settings);
  }

  private NotificationDispatcher dispatcherWith(
      MessageGateway gateway,
      AppointmentDirectory directory,
      DeliveryCircuit circuit,
      DispatcherProperties dispatcherSettings) {
    return new NotificationDispatcher(
        transactionManager,
        messages,
        gateway,
        directory,
        pending,
        health,
        circuit,
        new RetryBackoff(noJitter(), () -> 0.0),
        dispatcherSettings);
  }

  private Map<String, Object> onlyMessage() {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT status, attempts, next_attempt_at, last_error, failure_reason, sent_at,"
                + " provider_message_id FROM notification_messages");
    assertThat(rows).hasSize(1);
    return rows.getFirst();
  }

  private static Instant instant(Map<String, Object> row, String column) {
    Object value = row.get(column);
    if (value instanceof OffsetDateTime offset) {
      return offset.toInstant();
    }
    return ((Timestamp) value).toInstant();
  }

  private static String reference(MvcTestResult booked) {
    return JsonPath.read(
        new String(booked.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8),
        "$.reference");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("not released in time");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }
}
