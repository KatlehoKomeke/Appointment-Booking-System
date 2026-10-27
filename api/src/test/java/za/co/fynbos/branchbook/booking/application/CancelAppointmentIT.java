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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * the whole application on a real database. A cancel deletes the booking at once: in one
 * transaction it deletes every confirmation, queues the cancellation SMS and deletes the
 * appointment, which frees the seat and leaves the SMS and the idempotency key with no appointment.
 * From then on the reference is unknown, so a lookup or a repeat cancel is a counted 404. A cancel
 * is refused once the appointment has started, and is guarded and counted exactly as a lookup is.
 * The clock stands at 08:00 on Monday 26 October 2026 in Johannesburg unless a test moves it; the
 * numbers are synthetic.
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
    assertThat(jdbc.queryForList("SELECT status || ' ' || seat_no FROM appointments", String.class))
        .as("the cancelled appointment is gone; only the new one holds the seat")
        .containsExactly("CONFIRMED 1");
  }

  /**
   * BR-14, BR-16, BR-24: the response is the appointment as the cancel left it, exactly: the
   * booking as it was, now CANCELLED, no longer cancellable, stamped with the clock, and with only
   * its cancellation SMS, PENDING, word for word. The confirmation is not listed: it is deleted.
   * After the commit the appointment is gone, the SMS is kept with no appointment under the
   * cancel's correlation id, and the idempotency key is kept with no appointment until it expires.
   */
  @Test
  void cancelReturnsTheCancelledAppointmentWithOnlyItsCancellationAndDeletesIt() throws Exception {
    MvcTestResult booked = book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0113"));
    String reference = reference(booked);
    clock.advance(Duration.ofMinutes(10));

    MvcTestResult cancelled = cancel(mvc, reference, "0113");

    assertThat(cancelled).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
    Map<String, Object> sms =
        jdbc.queryForMap(
            "SELECT id, kind, status, appointment_id, correlation_id FROM notification_messages");
    assertThat(sms)
        .containsEntry("kind", "CANCELLATION")
        .containsEntry("status", "PENDING")
        .containsEntry("appointment_id", null)
        .as("FR-25: the cancellation is logged under the cancel request's correlation id")
        .containsEntry("correlation_id", "test-cancel");
    Map<String, Object> cancellation = new LinkedHashMap<>();
    cancellation.put("id", sms.get("id").toString());
    cancellation.put("channel", "SMS");
    cancellation.put("kind", "CANCELLATION");
    cancellation.put("status", "PENDING");
    cancellation.put("attempts", 0);
    cancellation.put("sentAt", null);
    cancellation.put("failureReason", null);
    cancellation.put("recipientMasked", "082 *** 0113");
    cancellation.put(
        "body",
        "Appt cancelled: FICA update, Sandton Summit, Tue 27 Oct 09:30 SAST. Ref "
            + reference
            + ". Rebook: http://localhost:8080/book");
    String expected =
        JsonPath.parse(booked.getResponse().getContentAsString(StandardCharsets.UTF_8))
            .set("$.status", "CANCELLED")
            .set("$.canCancel", false)
            .set("$.cancelledAt", "2026-10-26T08:10:00+02:00")
            .set("$.notifications", List.of(cancellation))
            .jsonString();
    assertThat(cancelled).bodyJson().isStrictlyEqualTo(expected);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class))
        .as("the appointment is deleted")
        .isZero();
    assertThat(
            jdbc.queryForMap(
                "SELECT response_status, appointment_id, expires_at > created_at AS live"
                    + " FROM idempotency_keys"))
        .as("BR-12: the key is kept, with no appointment, until it expires")
        .containsEntry("response_status", 201)
        .containsEntry("appointment_id", null)
        .containsEntry("live", true);
  }

  /**
   * BR-13, BR-14: once cancelled, the reference is unknown. Opening it, or cancelling it again, is
   * the same 404 an unknown reference gets, and counts as a wrong try: five of them block the
   * reference. Nothing is written again.
   */
  @Test
  void lookupAndRepeatCancelAfterACancelReturn404AndAreCounted() throws Exception {
    String reference = reference(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0114")));
    assertThat(cancel(mvc, reference, "0114")).hasStatus(HttpStatus.OK);
    clock.advance(Duration.ofMinutes(1));

    List<MvcTestResult> unknown = new ArrayList<>();
    unknown.add(lookup(mvc, reference, "0114"));
    unknown.add(cancel(mvc, reference, "0114"));
    unknown.add(lookup(mvc, reference, "0114"));
    unknown.add(cancel(mvc, reference, "0114"));
    unknown.add(cancel(mvc, reference, "0114"));

    for (MvcTestResult result : unknown) {
      assertThatAppointmentNotFound(result, reference);
    }
    assertThat(lookup(mvc, reference, "0114"))
        .as("all five counted, so the reference is now blocked")
        .hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(cancellations()).as("no second cancellation").isOne();
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
   * BR-14: of two cancels of one appointment at once, one answers 200 and the other 404, counted.
   * The first is held as it inserts its SMS, with the appointment's row locked and its confirmation
   * deleted, until the second has loaded the appointment and is seen waiting for that lock; once
   * the first commits, the second finds the row gone, and writes nothing.
   */
  @Test
  void twoConcurrentCancelsGiveOne200AndOne404ThatIsCounted() throws Exception {
    String reference = reference(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0118")));
    MvcTestResult first;
    MvcTestResult second;
    try (BookingPause pause =
        BookingPause.before(
            "INSERT", "notification_messages", "NEW.kind = 'CANCELLATION'", dataSource, jdbc)) {
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
    assertThat(first).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");
    assertThatAppointmentNotFound(second, reference);
    assertThat(cancellations()).isOne();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class)).isZero();
    for (int miss = 1; miss <= 4; miss++) {
      assertThat(lookup(mvc, reference, "0118")).hasStatus(HttpStatus.NOT_FOUND);
    }
    assertThat(lookup(mvc, reference, "0118"))
        .as("the second cancel's 404 counted, so four more block the reference")
        .hasStatus(HttpStatus.TOO_MANY_REQUESTS);
  }

  /**
   * BR-24: the cancel deletes its appointment's confirmation whatever its status: still PENDING,
   * FAILED, or SENT. Nothing of the booking is left but its cancellation SMS, PENDING with no
   * appointment.
   */
  @Test
  void cancelDeletesTheConfirmationWhetherPendingFailedOrSent() {
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

    assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class)).isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT kind || ' ' || status || ' ' || coalesce(appointment_id::text, 'none')"
                    + " FROM notification_messages",
                String.class))
        .containsExactly(
            "CANCELLATION PENDING none", "CANCELLATION PENDING none", "CANCELLATION PENDING none");
  }

  /**
   * BR-14, BR-16, ADR-0003: the delete and the cancellation become visible together or not at all.
   * The cancel is held inside its delete of the appointment, as the foreign key detaches the
   * idempotency key, after it has deleted the confirmation and written its SMS: from outside,
   * nothing has changed, though the appointment's row is locked. Once it commits, the appointment
   * and its confirmation are gone and the SMS and the key are kept, all written by one transaction.
   */
  @Test
  void deleteAndCancellationCommitTogether() throws Exception {
    String reference = reference(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0121")));
    try (BookingPause pause =
        BookingPause.before(
            "UPDATE", "idempotency_keys", "NEW.appointment_id IS NULL", dataSource, jdbc)) {
      CompletableFuture<MvcTestResult> cancelling =
          CompletableFuture.supplyAsync(() -> cancel(mvc, reference, "0121"));
      pause.awaitWaiting(1, 0);

      assertThat(jdbc.queryForObject("SELECT status FROM appointments", String.class))
          .as("the appointment is still there")
          .isEqualTo("CONFIRMED");
      assertThat(confirmationOf(reference))
          .as("and so is its confirmation")
          .isEqualTo("PENDING null");
      assertThat(cancellations()).as("its cancellation is not queued yet").isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM idempotency_keys WHERE appointment_id IS NOT NULL",
                  Integer.class))
          .as("nor its key detached")
          .isOne();
      assertThat(appointmentRowIsLocked())
          .as("the version-checked lock was taken before anything was written")
          .isTrue();

      pause.release();
      assertThat(cancelling.get(10, TimeUnit.SECONDS)).hasStatus(HttpStatus.OK);
    }

    assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class)).isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT kind || ' ' || coalesce(appointment_id::text, 'none')"
                    + " FROM notification_messages",
                String.class))
        .containsExactly("CANCELLATION none");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(DISTINCT x) FROM (SELECT xmin::text AS x FROM notification_messages"
                    + " UNION ALL SELECT xmin::text FROM idempotency_keys) AS writers",
                Integer.class))
        .as("written by one transaction")
        .isOne();
  }

  /**
   * A cancel that waits on the appointment's row while the purge holds it answers 404 once the
   * purge commits, counted like any unknown reference. The purge is held at its key delete by
   * another transaction, after it has locked the appointment and deleted its message; the cancel,
   * which has loaded the appointment and matched its digits, then waits on the purge's lock.
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
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_messages", Integer.class))
        .as("no cancellation queued for an appointment that is gone")
        .isZero();
    for (int miss = 1; miss <= 4; miss++) {
      assertThat(lookup(mvc, reference, "0123")).hasStatus(HttpStatus.NOT_FOUND);
    }
    assertThat(lookup(mvc, reference, "0123"))
        .as("the cancel's 404 counted towards the block")
        .hasStatus(HttpStatus.TOO_MANY_REQUESTS);
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

  private static String reference(MvcTestResult booked) {
    assertThat(booked).hasStatus(HttpStatus.CREATED);
    return JsonPath.read(
        new String(booked.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8),
        "$.reference");
  }
}
