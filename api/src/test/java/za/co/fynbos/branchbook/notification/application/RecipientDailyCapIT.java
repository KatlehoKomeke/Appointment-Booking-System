package za.co.fynbos.branchbook.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;
import static za.co.fynbos.branchbook.support.Bookings.cancel;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
 * BR-17: with {@code branchbook.sms.recipient-daily-cap} at 2, a number outside the published demo
 * block is written at most two SMS in any 24 hours, confirmations and cancellations together. The
 * next is written FAILED with {@code RECIPIENT_DAILY_CAP}, is never sent, and the booking or the
 * cancel succeeds as usual. Messages the cap stopped do not count, and the demo block, which is
 * always simulated, is never capped.
 *
 * <p>This test builds an application context of its own, and with it a database container of its
 * own. The cap is off everywhere else, which {@code NotificationOutboxIT} shows. The clock stands
 * at 08:00 on Monday 26 October 2026 in Johannesburg.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
@TestPropertySource(properties = "branchbook.sms.recipient-daily-cap=2")
class RecipientDailyCapIT {

  /** A number just past the demo block, which a deployment with the AWS gateway really texts. */
  private static final String OUTSIDE_THE_BLOCK = "082 555 0200";

  private static final String IN_THE_BLOCK = "082 555 0150";

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private NotificationDispatcher dispatcher;

  @Autowired private MutableClock clock;

  @Test
  void theSmsPastTheCapIsWrittenFailedAndTheBookingStillSucceeds() {
    MvcTestResult first = bookLater("FICA", tuesday("09:00"), OUTSIDE_THE_BLOCK);
    MvcTestResult second = bookLater("CARD", tuesday("10:00"), OUTSIDE_THE_BLOCK);
    MvcTestResult third = bookLater("OPEN", tuesday("11:00"), OUTSIDE_THE_BLOCK);

    assertThat(first).hasStatus(HttpStatus.CREATED);
    assertThat(second).hasStatus(HttpStatus.CREATED);
    assertThat(third).hasStatus(HttpStatus.CREATED);
    assertThat(third).bodyJson().extractingPath("$.notifications[0].status").isEqualTo("FAILED");
    assertThat(third)
        .bodyJson()
        .extractingPath("$.notifications[0].failureReason")
        .isEqualTo("RECIPIENT_DAILY_CAP");
    assertThat(messages())
        .containsExactly(
            "CONFIRMATION PENDING null 0", "CONFIRMATION PENDING null 0", capped("CONFIRMATION"));

    dispatcher.runOnce(clock.instant());

    assertThat(messages())
        .as("the dispatcher sends the two, and never the capped one")
        .containsExactly(
            "CONFIRMATION SENT null 1", "CONFIRMATION SENT null 1", capped("CONFIRMATION"));
  }

  /** A cancellation SMS counts like a confirmation, and the cancel itself goes through. */
  @Test
  void cancellationsCountTowardsTheCap() {
    MvcTestResult first = bookLater("FICA", tuesday("09:00"), OUTSIDE_THE_BLOCK);
    bookLater("CARD", tuesday("10:00"), OUTSIDE_THE_BLOCK);

    MvcTestResult cancelled = cancelLater(first);

    assertThat(cancelled).hasStatus(HttpStatus.OK);
    assertThat(messages())
        .containsExactly(
            "CONFIRMATION FAILED SUPERSEDED_BY_CANCELLATION 0",
            "CONFIRMATION PENDING null 0",
            capped("CANCELLATION"));
  }

  /**
   * A confirmation the cap stopped keeps its reason when its appointment is cancelled, so it goes
   * on not counting; the cancellation SMS after it is capped by the two that did count.
   */
  @Test
  void aCappedConfirmationKeepsItsReasonWhenItsAppointmentIsCancelled() {
    bookLater("FICA", tuesday("09:00"), OUTSIDE_THE_BLOCK);
    bookLater("CARD", tuesday("10:00"), OUTSIDE_THE_BLOCK);
    MvcTestResult third = bookLater("OPEN", tuesday("11:00"), OUTSIDE_THE_BLOCK);

    assertThat(cancelLater(third)).hasStatus(HttpStatus.OK);

    assertThat(messages())
        .containsExactly(
            "CONFIRMATION PENDING null 0",
            "CONFIRMATION PENDING null 0",
            capped("CONFIRMATION"),
            capped("CANCELLATION"));
  }

  /**
   * The window is the last 24 hours, and a message the cap stopped never counts: once the two that
   * counted are a day old, the two capped since are not enough to cap the next.
   */
  @Test
  void theWindowIsTwentyFourHoursAndCappedMessagesNeverCount() {
    bookLater("FICA", friday("09:00"), OUTSIDE_THE_BLOCK);
    bookLater("CARD", friday("10:00"), OUTSIDE_THE_BLOCK);
    clock.advance(Duration.ofHours(12));
    bookLater("OPEN", friday("11:00"), OUTSIDE_THE_BLOCK);
    bookLater("CREDIT", friday("12:00"), OUTSIDE_THE_BLOCK);
    clock.advance(Duration.ofHours(12));
    bookLater("ESTATE", friday("13:00"), OUTSIDE_THE_BLOCK);

    assertThat(messages())
        .containsExactly(
            "CONFIRMATION PENDING null 0",
            "CONFIRMATION PENDING null 0",
            capped("CONFIRMATION"),
            capped("CONFIRMATION"),
            "CONFIRMATION PENDING null 0");
  }

  /** The demo block is always simulated, so it is never capped. */
  @Test
  void theDemoBlockIsNeverCapped() {
    bookLater("FICA", tuesday("09:00"), IN_THE_BLOCK);
    bookLater("CARD", tuesday("10:00"), IN_THE_BLOCK);
    MvcTestResult third = bookLater("OPEN", tuesday("11:00"), IN_THE_BLOCK);

    assertThat(third).hasStatus(HttpStatus.CREATED);
    assertThat(messages())
        .containsExactly(
            "CONFIRMATION PENDING null 0",
            "CONFIRMATION PENDING null 0",
            "CONFIRMATION PENDING null 0");
  }

  /** Every message, oldest first: its kind, status, failure reason and attempts. */
  private List<String> messages() {
    return jdbc.queryForList(
        "SELECT kind || ' ' || status || ' ' || coalesce(failure_reason, 'null') || ' ' || attempts"
            + " FROM notification_messages ORDER BY created_at",
        String.class);
  }

  private static String capped(String kind) {
    return kind + " FAILED RECIPIENT_DAILY_CAP 0";
  }

  private static String tuesday(String time) {
    return "2026-10-27T" + time + ":00+02:00";
  }

  private static String friday(String time) {
    return "2026-10-30T" + time + ":00+02:00";
  }

  private static String reference(MvcTestResult booked) {
    return JsonPath.read(
        new String(booked.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8),
        "$.reference");
  }

  /**
   * Books a minute after the last request, so every message has an instant of its own and the rows
   * read back in the order they were written.
   */
  private MvcTestResult bookLater(String serviceCode, String startsAt, String mobile) {
    clock.advance(Duration.ofMinutes(1));
    return book(mvc, body("SAN", serviceCode, startsAt, mobile));
  }

  /** Cancels a minute after the last request, with the last four digits of the number outside. */
  private MvcTestResult cancelLater(MvcTestResult booked) {
    clock.advance(Duration.ofMinutes(1));
    return cancel(mvc, reference(booked), "0200");
  }
}
