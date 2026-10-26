package za.co.fynbos.branchbook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;
import static za.co.fynbos.branchbook.support.Bookings.cancel;
import static za.co.fynbos.branchbook.support.Bookings.lookup;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import za.co.fynbos.branchbook.booking.application.RetentionPurge.Purged;
import za.co.fynbos.branchbook.notification.application.NotificationDispatcher;
import za.co.fynbos.branchbook.support.BookingPause;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.LockWaits;
import za.co.fynbos.branchbook.support.MutableClock;

/**
 * BR-13, BR-14, BR-16, BR-24, BR-25: {@code POST /api/v1/appointments/{reference}/cancel} through
 * the whole application on a real database. A cancel frees the seat, changes the appointment and
 * queues its cancellation SMS in one transaction, supersedes a PENDING or FAILED confirmation,
 * answers a repeat without writing anything, refuses once the appointment has started, and is
 * guarded and counted exactly as a lookup is. The clock stands at 08:00 on Monday 26 October 2026
 * in Johannesburg unless a test moves it; the numbers are synthetic.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class CancelAppointmentIT {

  private static final String TUESDAY_0930 = "2026-10-27T09:30:00+02:00";

  /** Tuesday 27 October 2026, 09:30 in Johannesburg. */
  private static final Instant TUESDAY_0930_UTC = Instant.parse("2026-10-27T07:30:00Z");

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private MutableClock clock;

  @Autowired private DataSource dataSource;

  @Autowired private NotificationDispatcher dispatcher;

  @Autowired private RetentionPurge retention;

  /**
   * BR-14, BR-8: Polokwane has one FICA seat. Once its holder cancels, another customer books the
   * same slot at once.
   */
  @Test
  void cancelFreesSeatForNewBooking() {
    String startsAt = "2026-10-27T09:00:00+02:00";
    String reference = reference(book(mvc, body("POL", "FICA", startsAt, "082 555 0111")));
    assertThat(book(mvc, body("POL", "FICA", startsAt, "082 555 0112")))
        .as("the only seat is taken")
        .hasStatus(HttpStatus.CONFLICT);

    assertThat(cancel(mvc, reference, "0111")).hasStatus(HttpStatus.OK);
    MvcTestResult rebooked = book(mvc, body("POL", "FICA", startsAt, "082 555 0112"));

    assertThat(rebooked).hasStatus(HttpStatus.CREATED);
    assertThat(
            jdbc.queryForList(
                "SELECT status || ' ' || seat_no FROM appointments ORDER BY created_at, status",
                String.class))
        .containsExactlyInAnyOrder("CANCELLED 1", "CONFIRMED 1");
  }

  /**
   * BR-14, BR-16, BR-24: the response is the cancelled appointment as a lookup now shows it: no
   * longer cancellable, its confirmation superseded and its cancellation SMS PENDING, word for
   * word. The change is stamped with the clock and increments the version once.
   */
  @Test
  void cancelReturnsTheCancelledAppointmentWithItsSupersededConfirmationAndPendingCancellation()
      throws Exception {
    String reference = reference(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0113")));
    clock.advance(Duration.ofMinutes(10));

    MvcTestResult cancelled = cancel(mvc, reference, "0113");

    assertThat(cancelled).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
    String sms =
        "Appt cancelled: FICA update, Sandton Summit, Tue 27 Oct 09:30 SAST. Ref "
            + reference
            + ". Rebook: http://localhost:8080/book";
    assertThat(cancelled)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {
              "reference": "%s", "status": "CANCELLED", "canCancel": false,
              "cancelledAt": "2026-10-26T08:10:00+02:00",
              "startsAt": "2026-10-27T09:30:00+02:00",
              "customer": {"mobileMasked": "082 *** 0113"},
              "notifications": [
                {"kind": "CONFIRMATION", "channel": "SMS", "status": "FAILED",
                 "failureReason": "SUPERSEDED_BY_CANCELLATION", "attempts": 0, "sentAt": null},
                {"kind": "CANCELLATION", "channel": "SMS", "status": "PENDING",
                 "failureReason": null, "attempts": 0, "sentAt": null,
                 "recipientMasked": "082 *** 0113", "body": "%s"}
              ]
            }"""
                .formatted(reference, sms));
    assertThat(cancelled)
        .bodyJson()
        .extractingPath("$.notifications[*].kind")
        .asArray()
        .containsExactly("CONFIRMATION", "CANCELLATION");
    assertThat(lookup(mvc, reference, "0113"))
        .bodyJson()
        .isStrictlyEqualTo(cancelled.getResponse().getContentAsString());
    Map<String, Object> row =
        jdbc.queryForMap("SELECT status, cancelled_at, updated_at, version FROM appointments");
    assertThat(row).containsEntry("status", "CANCELLED").containsEntry("version", 1);
    assertThat(instant(row, "cancelled_at")).isEqualTo(clock.instant());
    assertThat(instant(row, "updated_at")).isEqualTo(clock.instant());
    assertThat(
            jdbc.queryForObject(
                "SELECT correlation_id FROM notification_messages WHERE kind = 'CANCELLATION'",
                String.class))
        .as("FR-25: the cancellation is logged under the cancel request's correlation id")
        .isEqualTo("test-cancel");
  }

  /** BR-14: cancelling again answers as the first time did, and writes nothing. */
  @Test
  void repeatCancelIsA200NoOpThatQueuesNothing() throws Exception {
    String reference = reference(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0114")));
    MvcTestResult first = cancel(mvc, reference, "0114");
    Instant cancelledAt = clock.instant();
    clock.advance(Duration.ofMinutes(1));

    MvcTestResult again = cancel(mvc, reference, "0114");

    assertThat(again).hasStatus(HttpStatus.OK);
    assertThat(again).bodyJson().isStrictlyEqualTo(first.getResponse().getContentAsString());
    assertThat(cancellations()).isOne();
    Map<String, Object> row = jdbc.queryForMap("SELECT updated_at, version FROM appointments");
    assertThat(row).containsEntry("version", 1);
    assertThat(instant(row, "updated_at")).isEqualTo(cancelledAt);
  }

  /**
   * BR-14, BR-25: at the start, and after it, a cancel is 409 TOO_LATE_TO_CANCEL and leaves the
   * appointment and its messages as they were. One second before the start, it still works.
   */
  @Test
  void cancelAtOrAfterTheStartReturns409TooLateToCancelAndChangesNothing() throws Exception {
    String reference = reference(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0115")));

    List<MvcTestResult> late = new ArrayList<>();
    clock.setInstant(TUESDAY_0930_UTC);
    late.add(cancel(mvc, reference, "0115"));
    clock.advance(Duration.ofMinutes(1));
    late.add(cancel(mvc, reference, "0115"));

    for (MvcTestResult refused : late) {
      assertThat(refused)
          .hasStatus(HttpStatus.CONFLICT)
          .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
      assertThat(refused)
          .bodyJson()
          .isLenientlyEqualTo(
              """
              {"status": 409, "code": "TOO_LATE_TO_CANCEL",
               "title": "This appointment has already started", "correlationId": "test-cancel"}""");
      assertThat(refused.getResponse().getContentAsString()).doesNotContain(reference);
    }
    assertThat(jdbc.queryForMap("SELECT status, cancelled_at, version FROM appointments"))
        .containsEntry("status", "CONFIRMED")
        .containsEntry("cancelled_at", null)
        .containsEntry("version", 0);
    assertThat(
            jdbc.queryForList(
                "SELECT kind || ' ' || status FROM notification_messages", String.class))
        .containsExactly("CONFIRMATION PENDING");
    clock.setInstant(TUESDAY_0930_UTC.minusSeconds(1));
    assertThat(cancel(mvc, reference, "0115")).hasStatus(HttpStatus.OK);
  }

  /**
   * BR-13: a cancel needs the same proof as a lookup and is counted the same way. Wrong digits are
   * a 404 and count; right digits are released, never counted; five wrong tries block the
   * reference, for cancels and lookups alike and even with the right digits.
   */
  @Test
  void wrongDigitsReturn404AreCountedAndFiveBlockTheReferenceEvenForTheRightDigits() {
    String reference = reference(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0116")));
    for (int wrong = 1; wrong <= 4; wrong++) {
      MvcTestResult miss = cancel(mvc, reference, "9999");
      assertThat(miss).hasStatus(HttpStatus.NOT_FOUND);
      assertThat(miss).bodyJson().extractingPath("$.code").isEqualTo("APPOINTMENT_NOT_FOUND");
    }

    assertThat(cancel(mvc, reference, "0116"))
        .as("the right digits cancel, and are not counted")
        .hasStatus(HttpStatus.OK);
    assertThat(cancel(mvc, reference, "9999"))
        .as("so the fifth wrong try is still a 404")
        .hasStatus(HttpStatus.NOT_FOUND);
    MvcTestResult blocked = cancel(mvc, reference, "0116");

    assertThat(blocked)
        .hasStatus(HttpStatus.TOO_MANY_REQUESTS)
        .hasHeader("Retry-After", "3600")
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("LOOKUP_RATE_LIMITED");
    assertThat(lookup(mvc, reference, "0116")).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(cancellations()).isOne();
  }

  /**
   * BR-13: digits that are missing or not four digits are a 400 naming the header, checked before
   * the reference is touched, so they never count towards blocking it.
   */
  @Test
  void malformedOrMissingLast4Returns400AndIsNotCounted() {
    String reference = reference(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0117")));

    List<MvcTestResult> malformed = new ArrayList<>();
    for (String last4 : List.of("011", "01170", "o117", "0 17", "+117", "٠١١٧")) {
      malformed.add(cancel(mvc, reference, last4));
    }
    MvcTestResult missing = cancel(mvc, reference, null);

    for (MvcTestResult result : malformed) {
      assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
      assertThat(result)
          .bodyJson()
          .extractingPath("$.errors[*]['field','code']")
          .asArray()
          .containsExactly(Map.of("field", "X-Mobile-Last4", "code", "PATTERN"));
    }
    assertThat(missing).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(missing)
        .bodyJson()
        .extractingPath("$.errors[*]['field','code']")
        .asArray()
        .containsExactly(Map.of("field", "X-Mobile-Last4", "code", "REQUIRED"));
    assertThat(jdbc.queryForObject("SELECT status FROM appointments", String.class))
        .isEqualTo("CONFIRMED");
    assertThat(cancel(mvc, reference, "0117")).hasStatus(HttpStatus.OK);
  }

  /**
   * BR-14: two cancels of one appointment at once both answer 200 CANCELLED, and only one of them
   * writes. The first is held at its UPDATE, with the row locked, until the second is seen waiting
   * for that lock; the second's version check then fails, it reads the appointment again, finds it
   * cancelled and writes nothing.
   */
  @Test
  void twoConcurrentCancelsBothReturn200AndQueueOneCancellation() throws Exception {
    String reference = reference(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0118")));
    MvcTestResult first;
    MvcTestResult second;
    try (BookingPause pause =
        BookingPause.before(
            "UPDATE", "appointments", "NEW.status = 'CANCELLED'", dataSource, jdbc)) {
      CompletableFuture<MvcTestResult> firstCancel =
          CompletableFuture.supplyAsync(() -> cancel(mvc, reference, "0118"));
      pause.awaitWaiting(1, 0);
      CompletableFuture<MvcTestResult> secondCancel =
          CompletableFuture.supplyAsync(() -> cancel(mvc, reference, "0118"));
      pause.awaitWaiting(1, 1);

      pause.release();
      first = firstCancel.get(10, TimeUnit.SECONDS);
      second = secondCancel.get(10, TimeUnit.SECONDS);
    }

    assertThat(first).hasStatus(HttpStatus.OK);
    assertThat(second).hasStatus(HttpStatus.OK);
    assertThat(second).bodyJson().isStrictlyEqualTo(first.getResponse().getContentAsString());
    assertThat(second).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");
    assertThat(cancellations()).isOne();
    assertThat(jdbc.queryForObject("SELECT version FROM appointments", Integer.class))
        .as("written once")
        .isOne();
  }

  /**
   * BR-24: the cancel supersedes a confirmation still PENDING and one that FAILED, and leaves one
   * that was SENT alone: that customer was told, and is now told of the cancellation.
   */
  @Test
  void cancellationSupersedesPendingAndFailedConfirmationsAndLeavesASentOneAlone() {
    String sent = reference(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0119")));
    assertThat(dispatcher.runOnce(clock.instant())).isOne();
    String failed =
        reference(book(mvc, body("BEL", "CARD", "2026-10-27T10:00:00+02:00", "082 555 0000")));
    Instant start = clock.instant();
    for (int second : new int[] {0, 2, 6, 14, 30}) {
      assertThat(dispatcher.runOnce(start.plusSeconds(second))).isOne();
    }
    String pending =
        reference(book(mvc, body("UMH", "CARD", "2026-10-27T11:00:00+02:00", "082 555 0120")));
    assertThat(confirmationOf(sent)).isEqualTo("SENT null");
    assertThat(confirmationOf(failed)).isEqualTo("FAILED DELIVERY_FAILED");
    assertThat(confirmationOf(pending)).isEqualTo("PENDING null");

    assertThat(cancel(mvc, sent, "0119")).hasStatus(HttpStatus.OK);
    assertThat(cancel(mvc, failed, "0000")).hasStatus(HttpStatus.OK);
    assertThat(cancel(mvc, pending, "0120")).hasStatus(HttpStatus.OK);

    assertThat(confirmationOf(sent)).isEqualTo("SENT null");
    assertThat(confirmationOf(failed)).isEqualTo("FAILED SUPERSEDED_BY_CANCELLATION");
    assertThat(confirmationOf(pending)).isEqualTo("FAILED SUPERSEDED_BY_CANCELLATION");
    assertThat(
            jdbc.queryForList(
                "SELECT status FROM notification_messages WHERE kind = 'CANCELLATION'",
                String.class))
        .containsExactly("PENDING", "PENDING", "PENDING");
  }

  /**
   * BR-16, ADR-0003: the cancellation and its message become visible together or not at all. The
   * cancel is held as it inserts its SMS, after it has flushed the appointment's UPDATE and
   * superseded the confirmation: from outside, nothing has changed, though the row is locked. Once
   * it commits, all three are there, written by one transaction.
   */
  @Test
  void statusChangeAndCancellationCommitTogether() throws Exception {
    String reference = reference(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0121")));
    try (BookingPause pause =
        BookingPause.before(
            "INSERT", "notification_messages", "NEW.kind = 'CANCELLATION'", dataSource, jdbc)) {
      CompletableFuture<MvcTestResult> cancelling =
          CompletableFuture.supplyAsync(() -> cancel(mvc, reference, "0121"));
      pause.awaitWaiting(1, 0);

      assertThat(jdbc.queryForObject("SELECT status FROM appointments", String.class))
          .as("the appointment is not cancelled yet")
          .isEqualTo("CONFIRMED");
      assertThat(confirmationOf(reference))
          .as("nor its confirmation superseded")
          .isEqualTo("PENDING null");
      assertThat(cancellations()).as("nor its cancellation queued").isZero();
      assertThat(appointmentRowIsLocked())
          .as("the version-checked UPDATE ran, and holds the row, before the SMS is written")
          .isTrue();

      pause.release();
      assertThat(cancelling.get(10, TimeUnit.SECONDS)).hasStatus(HttpStatus.OK);
    }

    assertThat(jdbc.queryForObject("SELECT status FROM appointments", String.class))
        .isEqualTo("CANCELLED");
    assertThat(confirmationOf(reference)).isEqualTo("FAILED SUPERSEDED_BY_CANCELLATION");
    assertThat(cancellations()).isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(DISTINCT x) FROM (SELECT xmin::text AS x FROM appointments"
                    + " UNION ALL SELECT xmin::text FROM notification_messages"
                    + " WHERE kind = 'CANCELLATION' OR failure_reason IS NOT NULL) AS writers",
                Integer.class))
        .as("written by one transaction")
        .isOne();
  }

  /**
   * The retention purge deletes the appointment while the cancel is held before its UPDATE, after
   * it has loaded the appointment and matched the digits. The UPDATE finds nothing to change, and
   * the re-run finds no appointment: 404 APPOINTMENT_NOT_FOUND, never 500. The digits matched, so
   * the 404 is not counted: four more misses at the reference leave a fifth still a 404, not a 429.
   */
  @Test
  void cancelWhoseAppointmentThePurgeDeletesBeforeItsUpdateReturns404AndIsNotCounted()
      throws Exception {
    String reference = reference(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0122")));
    MvcTestResult cancelled;
    try (BookingPause pause =
        BookingPause.beforeFirstStatement("UPDATE", "appointments", dataSource, jdbc)) {
      CompletableFuture<MvcTestResult> cancelling =
          CompletableFuture.supplyAsync(() -> cancel(mvc, reference, "0122"));
      pause.awaitWaiting(1, 0);

      assertThat(retention.purge(clock.instant().plus(Duration.ofHours(48))))
          .as("the purge takes the appointment, its message and its key without waiting")
          .isEqualTo(new Purged(1, 1, 1));

      pause.release();
      cancelled = cancelling.get(10, TimeUnit.SECONDS);
    }

    assertThatAppointmentNotFound(cancelled, reference);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_messages", Integer.class))
        .as("no cancellation queued for an appointment that is gone")
        .isZero();
    for (int miss = 1; miss <= 4; miss++) {
      assertThat(lookup(mvc, reference, "0122")).hasStatus(HttpStatus.NOT_FOUND);
    }
    assertThat(lookup(mvc, reference, "0122"))
        .as("the cancel's 404 counted towards no block")
        .hasStatus(HttpStatus.NOT_FOUND);
  }

  /**
   * A cancel that waits on the appointment's row while the purge holds it answers 404 once the
   * purge commits. The purge is held at its key delete by another transaction, after it has locked
   * the appointment and deleted its message; the cancel's UPDATE then waits on the purge's lock.
   */
  @Test
  void cancelWaitingOnTheRowThePurgeHoldsReturns404OnceThePurgeCommits() throws Exception {
    String reference = reference(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0123")));
    Purged purged;
    MvcTestResult cancelled;
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement key =
          holder.prepareStatement(
              "SELECT 1 FROM idempotency_keys WHERE appointment_id ="
                  + " (SELECT id FROM appointments WHERE reference = ?) FOR UPDATE")) {
        key.setString(1, reference);
        key.executeQuery().close();
      }
      try {
        CompletableFuture<Purged> purging =
            CompletableFuture.supplyAsync(
                () -> retention.purge(clock.instant().plus(Duration.ofHours(48))));
        LockWaits.await(jdbc, 0, 1);
        CompletableFuture<MvcTestResult> cancelling =
            CompletableFuture.supplyAsync(() -> cancel(mvc, reference, "0123"));
        LockWaits.await(jdbc, 0, 2);

        holder.rollback();
        purged = purging.get(10, TimeUnit.SECONDS);
        cancelled = cancelling.get(10, TimeUnit.SECONDS);
      } finally {
        holder.rollback();
      }
    }

    assertThat(purged).isEqualTo(new Purged(1, 1, 1));
    assertThatAppointmentNotFound(cancelled, reference);
  }

  private static void assertThatAppointmentNotFound(MvcTestResult result, String reference)
      throws Exception {
    assertThat(result)
        .hasStatus(HttpStatus.NOT_FOUND)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("APPOINTMENT_NOT_FOUND");
    assertThat(result.getResponse().getContentAsString()).doesNotContain(reference);
  }

  /**
   * Whether another transaction holds the appointment's row lock, asked without waiting: NOWAIT
   * fails at once with SQLSTATE 55P03 instead.
   */
  private boolean appointmentRowIsLocked() {
    try {
      jdbc.queryForList("SELECT id FROM appointments FOR UPDATE NOWAIT");
      return false;
    } catch (DataAccessException failure) {
      if (failure.getMostSpecificCause() instanceof SQLException sql
          && "55P03".equals(sql.getSQLState())) {
        return true;
      }
      throw failure;
    }
  }

  private int cancellations() {
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM notification_messages WHERE kind = 'CANCELLATION'",
            Integer.class);
    return rows == null ? 0 : rows;
  }

  /** The status and failure reason of an appointment's confirmation, such as "SENT null". */
  private String confirmationOf(String reference) {
    return jdbc.queryForObject(
        "SELECT m.status || ' ' || coalesce(m.failure_reason, 'null') FROM notification_messages m"
            + " JOIN appointments a ON a.id = m.appointment_id"
            + " WHERE a.reference = ? AND m.kind = 'CONFIRMATION'",
        String.class,
        reference);
  }

  private static Instant instant(Map<String, Object> row, String column) {
    Object value = row.get(column);
    if (value instanceof OffsetDateTime offset) {
      return offset.toInstant();
    }
    return ((Timestamp) value).toInstant();
  }

  private static String reference(MvcTestResult booked) {
    assertThat(booked).hasStatus(HttpStatus.CREATED);
    return JsonPath.read(
        new String(booked.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8),
        "$.reference");
  }
}
