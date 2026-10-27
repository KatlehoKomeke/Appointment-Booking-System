package za.co.fynbos.branchbook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.customer;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fynbos.branchbook.booking.application.RetentionPurge.Purged;
import za.co.fynbos.branchbook.support.Bookings;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.TestClockConfiguration;

/**
 * The hosted demo's promise (SECURITY.md, ADR-0006): the retention purge keeps every booking until
 * its appointment is over, then deletes it, with its messages and its idempotency keys, and nothing
 * else. A booking for a future date is never touched, however long ago it was made. Appointments
 * are booked through the API, and the purge is called with the instant to purge as of, so no test
 * waits for its schedule. One test waits for real: a key row held by another connection makes a
 * batch wait out the 2-second {@code lock_timeout}.
 */
@IntegrationTest
@ExtendWith({DatabaseReset.class, OutputCaptureExtension.class})
class RetentionPurgeIT {

  /** An instant after every appointment these tests book on 27 October has ended. */
  private static final Instant AFTER_THEY_ALL_END = Instant.parse("2026-10-27T12:00:00Z");

  /** When the earliest appointment, 09:00 SAST on 27 October for 15 minutes, ends. */
  private static final Instant FIRST_ENDS = Instant.parse("2026-10-27T07:15:00Z");

  /** The customer's name, synthetic and unlike anything else the application logs. */
  private static final String FIRST_NAME = "Lindiwe";

  private static final String LAST_NAME = "Mokoena";

  private static final List<String> CATALOGUE =
      List.of(
          "branches", "branch_operating_hours", "services", "branch_services", "public_holidays");

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private DataSource dataSource;

  @Autowired private RetentionPurge retention;

  @Autowired private RetentionProperties properties;

  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired private AppointmentRepository appointments;

  @Autowired private IdempotencyKeyStore keys;

  @Autowired private NotificationEraser notifications;

  /**
   * The settings application.yml ships: off, and at the end of the appointment once switched on.
   */
  @Test
  void isSwitchedOffByDefaultAndDeletesAtTheEnd() {
    assertThat(properties.enabled()).isFalse();
    assertThat(properties.afterEnd()).isEqualTo(Duration.ZERO);
  }

  /**
   * An appointment goes, with its message and key, at the instant it ends and not a second before;
   * one that ends later stays, with its own, until it has ended too. The catalogue is never
   * touched.
   */
  @Test
  void anAppointmentGoesOnceItHasEndedNotBefore() {
    Booked first = book("2026-10-27T09:00:00+02:00", "082 555 0141");
    Booked later = book("2026-10-27T09:15:00+02:00", "082 555 0142");
    Map<String, Integer> catalogue = catalogueCounts();

    assertThat(retention.purge(FIRST_ENDS.minusSeconds(1))).isEqualTo(Purged.NONE);
    assertThat(rowsOf(first)).as("still on").containsExactly(1, 1, 1);

    assertThat(retention.purge(FIRST_ENDS)).isEqualTo(new Purged(1, 1, 1));
    assertThat(rowsOf(first)).as("appointment, messages, keys").containsExactly(0, 0, 0);
    assertThat(rowsOf(later)).as("appointment, messages, keys").containsExactly(1, 1, 1);
    assertThat(catalogueCounts()).isEqualTo(catalogue);

    assertThat(retention.purge(FIRST_ENDS.plus(Duration.ofMinutes(15))))
        .isEqualTo(new Purged(1, 1, 1));
    assertThat(rowsOf(later)).containsExactly(0, 0, 0);
    assertThat(catalogueCounts()).isEqualTo(catalogue);
  }

  /**
   * The rule this purge replaced deleted a booking 48 hours after it was made, so a customer who
   * booked a week ahead lost the booking before the day. A booking for a future date now survives
   * every run until its appointment has ended, however long ago it was made.
   */
  @Test
  void aFutureBookingIsKeptHoweverLongAgoItWasMade() {
    Booked future = book("2026-11-20T10:00:00+02:00", "082 555 0148");
    Instant made = TestClockConfiguration.DEFAULT_INSTANT;

    assertThat(retention.purge(made.plus(Duration.ofHours(48)))).isEqualTo(Purged.NONE);
    assertThat(retention.purge(made.plus(Duration.ofDays(20)))).isEqualTo(Purged.NONE);
    assertThat(rowsOf(future)).as("kept until the day").containsExactly(1, 1, 1);

    Instant ends = Instant.parse("2026-11-20T08:15:00Z");
    assertThat(retention.purge(ends.minusSeconds(1))).isEqualTo(Purged.NONE);
    assertThat(retention.purge(ends)).isEqualTo(new Purged(1, 1, 1));
    assertThat(rowsOf(future)).containsExactly(0, 0, 0);
  }

  /** {@code after-end} keeps a booking for that long after its appointment ends, then it goes. */
  @Test
  void aDelayAfterTheEndIsHonoured() {
    Booked booked = book("2026-10-27T09:00:00+02:00", "082 555 0149");
    RetentionPurge twoHoursLater =
        new RetentionPurge(
            transactionManager,
            appointments,
            keys,
            notifications,
            new RetentionProperties(true, Duration.ofHours(2)));

    assertThat(twoHoursLater.purge(FIRST_ENDS)).isEqualTo(Purged.NONE);
    assertThat(twoHoursLater.purge(FIRST_ENDS.plus(Duration.ofHours(2)).minusSeconds(1)))
        .isEqualTo(Purged.NONE);
    assertThat(rowsOf(booked)).containsExactly(1, 1, 1);

    assertThat(twoHoursLater.purge(FIRST_ENDS.plus(Duration.ofHours(2))))
        .isEqualTo(new Purged(1, 1, 1));
    assertThat(rowsOf(booked)).containsExactly(0, 0, 0);
  }

  /** Five ended appointments in batches of two: three transactions, and every one goes. */
  @Test
  void severalBatchesAllGo() {
    List<Booked> ended = bookFive();

    Purged purged = retention.purge(AFTER_THEY_ALL_END, 2);

    assertThat(purged).isEqualTo(new Purged(5, 5, 5));
    assertThat(ended).allSatisfy(booked -> assertThat(rowsOf(booked)).containsExactly(0, 0, 0));
  }

  /**
   * An appointment another transaction holds, as a booking or a cancellation would, is skipped
   * without waiting and survives with its rows; the next run, once it is released, takes it. The
   * held appointment ends first, so it is the first row the first batch reaches.
   */
  @Test
  void lockedAppointmentIsSkippedAndGoesOnTheNextRunOnceReleased(CapturedOutput output)
      throws SQLException {
    int before = output.getAll().length();
    Booked held = book("2026-10-27T09:00:00+02:00", "082 555 0151");
    Booked second = book("2026-10-27T09:15:00+02:00", "082 555 0152");
    Booked third = book("2026-10-27T09:30:00+02:00", "082 555 0153");

    Purged whileHeld;
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      lock(holder, "SELECT 1 FROM appointments WHERE id = ? FOR UPDATE", held.id());
      try {
        whileHeld = retention.purge(AFTER_THEY_ALL_END, 2);
      } finally {
        holder.rollback();
      }
    }

    assertThat(whileHeld).isEqualTo(new Purged(2, 2, 2));
    assertThat(since(output, before))
        .as("skipped, never waited for")
        .doesNotContain("batch_failed");
    assertThat(rowsOf(second)).containsExactly(0, 0, 0);
    assertThat(rowsOf(third)).containsExactly(0, 0, 0);
    assertThat(rowsOf(held)).as("skipped, not deleted").containsExactly(1, 1, 1);

    assertThat(retention.purge(AFTER_THEY_ALL_END, 2)).isEqualTo(new Purged(1, 1, 1));
    assertThat(rowsOf(held)).containsExactly(0, 0, 0);
  }

  /**
   * A batch that fails is rolled back whole and logged at WARN by counts and type; the run stops
   * without throwing, the batches before it stay deleted, and the next run carries on. Nothing the
   * runs log names the customer, their number or a reference.
   *
   * <p>Another transaction holds the last appointment's key, so its batch fails at the key delete,
   * after its messages were deleted: those messages must come back with the rollback, which they do
   * only while the eraser joins the batch's transaction.
   */
  @Test
  void failedBatchStopsTheRunKeepsEarlierBatchesAndLogsNothingPersonal(CapturedOutput output)
      throws SQLException {
    int before = output.getAll().length();
    List<Booked> booked =
        new ArrayList<>(
            List.of(
                book("2026-10-27T09:00:00+02:00", "082 555 0161"),
                book("2026-10-27T09:15:00+02:00", "082 555 0162"),
                book("2026-10-27T09:30:00+02:00", "082 555 0163"),
                book("2026-10-27T09:45:00+02:00", "082 555 0164")));
    Booked last = book("2026-10-27T10:30:00+02:00", "082 555 0165");
    booked.add(last);

    Purged whileHeld;
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      lock(holder, "SELECT 1 FROM idempotency_keys WHERE appointment_id = ? FOR UPDATE", last.id());
      try {
        whileHeld = retention.purge(AFTER_THEY_ALL_END, 2);
      } finally {
        holder.rollback();
      }
    }

    assertThat(whileHeld).as("the first two batches").isEqualTo(new Purged(4, 4, 4));
    assertThat(rowsOf(last))
        .as("its batch rolled back, the messages it had deleted included")
        .containsExactly(1, 1, 1);
    assertThat(since(output, before))
        .containsPattern(
            "WARN.*retention\\.batch_failed appointments=4 messages=4 keys=4 cause=\\w+\\R");

    assertThat(retention.purge(AFTER_THEY_ALL_END, 2)).isEqualTo(new Purged(1, 1, 1));
    assertThat(booked).allSatisfy(each -> assertThat(rowsOf(each)).containsExactly(0, 0, 0));
    assertHoldsNothingOfTheCustomers(since(output, before), booked);
  }

  /**
   * What this test has written. The capture also holds the class's earlier tests, and Spring prints
   * a failed test's requests, bodies and all, so without this one failure would fail the rest.
   */
  private static String since(CapturedOutput output, int before) {
    return output.getAll().substring(before);
  }

  /** Five appointments on 27 October, by five customers, a quarter-hour apart. */
  private List<Booked> bookFive() {
    return List.of(
        book("2026-10-27T09:00:00+02:00", "082 555 0143"),
        book("2026-10-27T09:15:00+02:00", "082 555 0144"),
        book("2026-10-27T09:30:00+02:00", "082 555 0145"),
        book("2026-10-27T09:45:00+02:00", "082 555 0146"),
        book("2026-10-27T10:00:00+02:00", "082 555 0147"));
  }

  /** Books FICA at Sandton through the API, as the test clock's customer would. */
  private Booked book(String startsAt, String mobile) {
    Map<String, Object> body = body("SAN", "FICA", startsAt, mobile);
    customer(body).put("firstName", FIRST_NAME);
    customer(body).put("lastName", LAST_NAME);
    MvcTestResult result = Bookings.book(mvc, body);
    assertThat(result).hasStatus(HttpStatus.CREATED);
    String reference =
        JsonPath.read(
            new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8),
            "$.reference");
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM appointments WHERE reference = ?", UUID.class, reference);
    assertThat(rowsOf(new Booked(id, reference, mobile))).containsExactly(1, 1, 1);
    return new Booked(id, reference, mobile);
  }

  /** The appointment's own row, its messages and its keys, counted. */
  private List<Integer> rowsOf(Booked booked) {
    return List.of(
        count("SELECT count(*) FROM appointments WHERE id = ?", booked.id()),
        count("SELECT count(*) FROM notification_messages WHERE appointment_id = ?", booked.id()),
        count("SELECT count(*) FROM idempotency_keys WHERE appointment_id = ?", booked.id()));
  }

  private Map<String, Integer> catalogueCounts() {
    return CATALOGUE.stream()
        .collect(Collectors.toMap(table -> table, table -> count("SELECT count(*) FROM " + table)));
  }

  private int count(String sql, Object... args) {
    Integer rows = jdbc.queryForObject(sql, Integer.class, args);
    return rows == null ? 0 : rows;
  }

  /** Takes a row lock on another connection, as another transaction would hold it. */
  private static void lock(Connection holder, String sql, UUID id) throws SQLException {
    try (PreparedStatement statement = holder.prepareStatement(sql)) {
      statement.setObject(1, id);
      statement.executeQuery().close();
    }
  }

  private static void assertHoldsNothingOfTheCustomers(String log, List<Booked> booked) {
    assertThat(log).doesNotContain(FIRST_NAME).doesNotContain(LAST_NAME);
    for (Booked each : booked) {
      String lastFour = each.mobile().substring(each.mobile().length() - 4);
      assertThat(log)
          .as("the log must not hold the customer's number, in any form")
          .doesNotMatch("(?s).*555\\D{0,3}" + lastFour + ".*");
      assertThat(log)
          .as("the log must not hold the appointment's reference")
          .doesNotContain(each.reference())
          .doesNotContain(each.reference().replace("-", ""));
    }
  }

  /**
   * An appointment the test booked.
   *
   * @param id its id
   * @param reference its reference
   * @param mobile the number it was booked with, as typed
   */
  private record Booked(UUID id, String reference, String mobile) {}
}
