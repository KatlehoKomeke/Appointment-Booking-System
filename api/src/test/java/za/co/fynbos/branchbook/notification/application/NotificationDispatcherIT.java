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
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * BR-17, FR-15, NFR-13: the dispatcher delivers PENDING messages through the gateway, one
 * transaction each. BR-24, FR-18: it never delivers a confirmation after its appointment's
 * cancellation, and a retry waits for a send in flight. Its own loop is off in the tests, so each
 * test calls {@link NotificationDispatcher#runOnce} with the instant it wants: no test sleeps. The
 * numbers are synthetic; the simulated gateway refuses those ending 0000.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class NotificationDispatcherIT {

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private MutableClock clock;

  @Autowired private NotificationDispatcher dispatcher;

  @Autowired private NotificationMessageRepository messages;

  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired private AppointmentDirectory appointments;

  @Autowired private OutboxPendingGauge pending;

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
   * BR-17: a refused SMS is tried again after 2, 4, 8 and 16 seconds, and the fifth refusal marks
   * it FAILED with DELIVERY_FAILED: 30 seconds of backoff. A message is never tried early.
   */
  @Test
  void retriesWithBackoffThenMarksFailedAfterFiveAttempts() {
    book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0000"));
    Instant start = clock.instant();

    int elapsed = 0;
    for (int wait : new int[] {0, 2, 4, 8}) {
      elapsed += wait;
      assertThat(dispatcher.runOnce(start.plusSeconds(elapsed))).isOne();
      Map<String, Object> row = onlyMessage();
      int attempts = (Integer) row.get("attempts");
      assertThat(row)
          .containsEntry("status", "PENDING")
          .containsEntry("last_error", "SIMULATED_FAILURE");
      Duration backoff = Duration.ofSeconds(1L << attempts);
      assertThat(instant(row, "next_attempt_at"))
          .isEqualTo(start.plusSeconds(elapsed).plus(backoff));
      assertThat(dispatcher.runOnce(start.plusSeconds(elapsed).plus(backoff).minusMillis(1)))
          .as("not due a millisecond early")
          .isZero();
    }
    assertThat(dispatcher.runOnce(start.plusSeconds(30))).isOne();

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
   * BR-24: once an appointment is cancelled, the next tick sends only its cancellation. The cancel
   * already marked the PENDING confirmation superseded, so the tick never even claims it.
   */
  @Test
  void pendingConfirmationIsSupersededByCancellation() {
    String reference =
        reference(book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0103")));
    assertThat(cancel(mvc, reference, "0103")).hasStatus(HttpStatus.OK);
    ConcurrentLinkedQueue<UUID> sent = new ConcurrentLinkedQueue<>();

    int handled = dispatcherWith(recordingInto(sent)).runOnce(clock.instant());

    assertThat(handled).as("the cancellation, and nothing else").isOne();
    assertThat(sent).containsExactly(messageId("CANCELLATION"));
    assertThat(statusOf("CONFIRMATION")).isEqualTo("FAILED SUPERSEDED_BY_CANCELLATION");
    assertThat(statusOf("CANCELLATION")).isEqualTo("SENT null");
  }

  /**
   * BR-24: a confirmation already in the gateway when the cancel arrives is delivered before its
   * cancellation, never after it. The cancel waits for the message's row lock; once the send is
   * marked SENT, it leaves that message alone and queues the cancellation behind it.
   */
  @Test
  void confirmationInFlightWhenTheCancelArrivesIsSentBeforeTheCancellation() throws Exception {
    String reference =
        reference(book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0104")));
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
    holder.runOnce(now);
    assertThat(statusOf("CONFIRMATION")).isEqualTo("SENT null");
    assertThat(statusOf("CANCELLATION")).isEqualTo("SENT null");
    assertThat(sent).containsExactly(messageId("CONFIRMATION"), messageId("CANCELLATION"));
  }

  /**
   * BR-24, defence in depth: a confirmation of a cancelled appointment set PENDING again by hand,
   * as a careless replay would, is marked superseded when the dispatcher claims it, and never
   * reaches the gateway.
   */
  @Test
  void confirmationOfACancelledAppointmentResetByHandIsSupersededAtTheClaimNotSent() {
    String reference =
        reference(book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0105")));
    assertThat(cancel(mvc, reference, "0105")).hasStatus(HttpStatus.OK);
    jdbc.update(
        "UPDATE notification_messages SET status = 'PENDING', failure_reason = NULL"
            + " WHERE kind = 'CONFIRMATION'");
    ConcurrentLinkedQueue<UUID> sent = new ConcurrentLinkedQueue<>();

    int handled = dispatcherWith(recordingInto(sent)).runOnce(clock.instant());

    assertThat(handled).isEqualTo(2);
    assertThat(sent).containsExactly(messageId("CANCELLATION"));
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
        new NotificationDispatcher(
                transactionManager, messages, recordingInto(sent), unanswering, pending)
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
          return new MessageGateway.Refused("SIMULATED_FAILURE");
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

  /** A dispatcher like the application's, with a gateway of the test's own. */
  private NotificationDispatcher dispatcherWith(MessageGateway gateway) {
    return new NotificationDispatcher(transactionManager, messages, gateway, appointments, pending);
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
