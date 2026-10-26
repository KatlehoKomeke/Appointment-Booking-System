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
import za.co.fynbos.branchbook.booking.application.RetentionPurge.Purged;
import za.co.fynbos.branchbook.support.Bookings;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;
import za.co.fynbos.branchbook.support.TestClockConfiguration;

/**
 * The hosted demo's promise (SECURITY.md, ADR-0006): the retention purge deletes every appointment
 * booked max-age ago or earlier, 48 hours by default, with its messages and its idempotency keys,
 * and nothing else. Appointments are booked through the API with the clock set, so their {@code
 * created_at} is exact, and the purge is called with the instant to purge as of, so no test waits
 * for its schedule. One test waits for real: a key row held by another connection makes a batch
 * wait out the 2-second {@code lock_timeout}.
 */
@IntegrationTest
@ExtendWith({DatabaseReset.class, OutputCaptureExtension.class})
class RetentionPurgeIT {

  private static final Duration FORTY_EIGHT_HOURS = Duration.ofHours(48);

  /** When the old appointments are booked: the clock's default instant. */
  private static final Instant BOOKED = TestClockConfiguration.DEFAULT_INSTANT;

  /** The customer's name, synthetic and unlike anything else the application logs. */
  private static final String FIRST_NAME = "Lindiwe";

  private static final String LAST_NAME = "Mokoena";

  private static final List<String> CATALOGUE =
      List.of(
          "branches", "branch_operating_hours", "services", "branch_services", "public_holidays");

  @Autowired private MockMvcTester mvc;

  @Autowired private MutableClock clock;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private DataSource dataSource;

  @Autowired private RetentionPurge retention;

  @Autowired private RetentionProperties properties;

  /** The settings application.yml ships: off, and 48 hours once switched on. */
  @Test
  void isSwitchedOffByDefaultAndKeepsBookingsFortyEightHours() {
    assertThat(properties.enabled()).isFalse();
    assertThat(properties.maxAge()).isEqualTo(FORTY_EIGHT_HOURS);
  }

  /**
   * An appointment booked exactly 48 hours ago goes, with its message and key; one booked a second
   * later stays, with its own, until it is 48 hours old too. The catalogue is never touched.
   */
  @Test
  void appointmentBookedExactlyMaxAgeAgoGoesWithItsMessagesAndKeys() {
    Booked old = book("2026-10-27T09:00:00+02:00", "082 555 0141");
    clock.advance(Duration.ofSeconds(1));
    Booked younger = book("2026-10-27T09:15:00+02:00", "082 555 0142");
    Map<String, Integer> catalogue = catalogueCounts();

    Purged purged = retention.purge(BOOKED.plus(FORTY_EIGHT_HOURS));

    assertThat(purged).isEqualTo(new Purged(1, 1, 1));
    assertThat(rowsOf(old)).as("appointment, messages, keys").containsExactly(0, 0, 0);
    assertThat(rowsOf(younger)).as("appointment, messages, keys").containsExactly(1, 1, 1);
    assertThat(catalogueCounts()).isEqualTo(catalogue);

    assertThat(retention.purge(BOOKED.plus(FORTY_EIGHT_HOURS).plusSeconds(1)))
        .isEqualTo(new Purged(1, 1, 1));
    assertThat(rowsOf(younger)).containsExactly(0, 0, 0);
    assertThat(catalogueCounts()).isEqualTo(catalogue);
  }

  /** Five old appointments in batches of two: three transactions, and every one goes. */
  @Test
  void severalBatchesAllGo() {
    List<Booked> old = bookFive();

    Purged purged = retention.purge(BOOKED.plus(FORTY_EIGHT_HOURS), 2);

    assertThat(purged).isEqualTo(new Purged(5, 5, 5));
    assertThat(old).allSatisfy(booked -> assertThat(rowsOf(booked)).containsExactly(0, 0, 0));
  }

  /**
   * An appointment another transaction holds, as a booking or a cancellation would, is skipped
   * without waiting and survives with its rows; the next run, once it is released, takes it. The
   * held appointment is the oldest, so it is the first row the first batch reaches.
   */
  @Test
  void lockedAppointmentIsSkippedAndGoesOnTheNextRunOnceReleased(CapturedOutput output)
      throws SQLException {
    int before = output.getAll().length();
    Booked held = book("2026-10-27T09:00:00+02:00", "082 555 0151");
    clock.advance(Duration.ofSeconds(1));
    Booked second = book("2026-10-27T09:15:00+02:00", "082 555 0152");
    Booked third = book("2026-10-27T09:30:00+02:00", "082 555 0153");
    Instant now = BOOKED.plusSeconds(1).plus(FORTY_EIGHT_HOURS);

    Purged whileHeld;
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      lock(holder, "SELECT 1 FROM appointments WHERE id = ? FOR UPDATE", held.id());
      try {
        whileHeld = retention.purge(now, 2);
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

    assertThat(retention.purge(now, 2)).isEqualTo(new Purged(1, 1, 1));
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
    clock.advance(Duration.ofSeconds(1));
    Booked last = book("2026-10-27T10:30:00+02:00", "082 555 0165");
    booked.add(last);
    Instant now = BOOKED.plusSeconds(1).plus(FORTY_EIGHT_HOURS);

    Purged whileHeld;
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      lock(holder, "SELECT 1 FROM idempotency_keys WHERE appointment_id = ? FOR UPDATE", last.id());
      try {
        whileHeld = retention.purge(now, 2);
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

    assertThat(retention.purge(now, 2)).isEqualTo(new Purged(1, 1, 1));
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

  /** Five appointments booked at {@link #BOOKED}, by five customers, a quarter-hour apart. */
  private List<Booked> bookFive() {
    return List.of(
        book("2026-10-27T09:00:00+02:00", "082 555 0143"),
        book("2026-10-27T09:15:00+02:00", "082 555 0144"),
        book("2026-10-27T09:30:00+02:00", "082 555 0145"),
        book("2026-10-27T09:45:00+02:00", "082 555 0146"),
        book("2026-10-27T10:00:00+02:00", "082 555 0147"));
  }

  /** Books FICA at Sandton through the API, at the clock's current instant. */
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
