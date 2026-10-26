package za.co.fynbos.branchbook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fynbos.branchbook.booking.domain.Appointment;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;
import za.co.fynbos.branchbook.booking.domain.CustomerDetails;
import za.co.fynbos.branchbook.booking.domain.CustomerName;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;
import za.co.fynbos.branchbook.support.ScriptedReferenceGenerator;
import za.co.fynbos.branchbook.support.SeedData;

/**
 * The booking transaction's customer rules, its re-run and its database backstop, on a real
 * database. The clock stands at 08:00 on Monday 26 October 2026 in Johannesburg unless a test moves
 * it; the numbers are synthetic.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class BookAppointmentIT {

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private MutableClock clock;

  @Autowired private ScriptedReferenceGenerator references;

  @Autowired private AppointmentRepository appointments;

  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired private DataSource dataSource;

  /**
   * BR-25: once a FICA appointment has ended, the same customer can book FICA again, and the past
   * appointment stays CONFIRMED.
   */
  @Test
  void pastConfirmedAppointmentDoesNotBlockSameServiceBooking() {
    assertThat(book(mvc, body("SAN", "FICA", "2026-10-27T10:00:00+02:00", "082 555 0161")))
        .hasStatus(HttpStatus.CREATED);
    clock.setInstant(Instant.parse("2026-10-27T08:30:00Z"));

    MvcTestResult later =
        book(mvc, body("SAN", "FICA", "2026-10-27T14:00:00+02:00", "082 555 0161"));

    assertThat(later).hasStatus(HttpStatus.CREATED);
    assertThat(
            jdbc.queryForList("SELECT status FROM appointments ORDER BY starts_at", String.class))
        .containsExactly("CONFIRMED", "CONFIRMED");
  }

  /**
   * BR-10, BR-9, BR-15: only CONFIRMED and CHECKED_IN appointments count. One that was cancelled,
   * completed or missed, at the same time and for the same service, blocks neither a second booking
   * of the service nor one at the same time, and its seat is free again.
   */
  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(strings = {"CANCELLED", "COMPLETED", "NO_SHOW"})
  void cancelledCompletedOrNoShowAppointmentBlocksNothing(String status) {
    assertThat(book(mvc, body("POL", "FICA", "2026-10-27T10:00:00+02:00", "082 555 0169")))
        .hasStatus(HttpStatus.CREATED);
    OffsetDateTime at = clock.instant().atOffset(ZoneOffset.UTC);
    String timestamps =
        switch (status) {
          case "CANCELLED" -> "cancelled_at = ?";
          case "COMPLETED" -> "checked_in_at = ?, completed_at = ?";
          case "NO_SHOW" -> "no_show_at = ?";
          default -> throw new IllegalArgumentException(status);
        };
    Object[] parameters =
        status.equals("COMPLETED") ? new Object[] {status, at, at} : new Object[] {status, at};
    jdbc.update("UPDATE appointments SET status = ?, " + timestamps, parameters);

    MvcTestResult again =
        book(mvc, body("POL", "FICA", "2026-10-27T10:00:00+02:00", "082 555 0169"));

    assertThat(again).hasStatus(HttpStatus.CREATED);
    assertThat(
            jdbc.queryForObject(
                "SELECT seat_no FROM appointments WHERE status = 'CONFIRMED'", Integer.class))
        .as("Polokwane has one FICA seat, so the new appointment took the freed one")
        .isOne();
  }

  /**
   * BR-16, ADR-0003: the confirmation SMS commits with the booking or not at all. Step 10 is made
   * to fail after the SMS was written in step 9, and the whole booking is gone, its SMS included.
   */
  @Test
  void failureAfterTheSmsIsWrittenRollsTheSmsBackWithTheBooking() {
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
      failed = book(mvc, body("SAN", "FICA", "2026-10-27T10:00:00+02:00", "082 555 0174"));
    } finally {
      jdbc.execute("DROP TRIGGER test_fail_key_completion ON idempotency_keys");
      jdbc.execute("DROP FUNCTION test_fail_key_completion()");
    }

    assertThat(failed).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(count("appointments")).isZero();
    assertThat(count("notification_messages")).isZero();
    assertThat(count("idempotency_keys")).isZero();
  }

  /**
   * BR-7: the lowest free seat is taken. Sandton has three FICA seats; once seats 1 and 2 are
   * cancelled, the next booking takes seat 1 and the one after it seat 2, never seat 3 again or a
   * seat past the capacity, and then the slot is full.
   */
  @Test
  void lowestFreeSeatIsTakenFirst() {
    for (String mobile : List.of("082 555 0175", "082 555 0176", "082 555 0177")) {
      assertThat(book(mvc, body("SAN", "FICA", "2026-10-27T10:00:00+02:00", mobile)))
          .hasStatus(HttpStatus.CREATED);
    }
    jdbc.update(
        "UPDATE appointments SET status = 'CANCELLED', cancelled_at = ? WHERE seat_no IN (1, 2)",
        clock.instant().atOffset(ZoneOffset.UTC));

    assertThat(book(mvc, body("SAN", "FICA", "2026-10-27T10:00:00+02:00", "082 555 0178")))
        .hasStatus(HttpStatus.CREATED);
    assertThat(book(mvc, body("SAN", "FICA", "2026-10-27T10:00:00+02:00", "082 555 0179")))
        .hasStatus(HttpStatus.CREATED);
    MvcTestResult full =
        book(mvc, body("SAN", "FICA", "2026-10-27T10:00:00+02:00", "082 555 0180"));

    assertThat(seatOf("+27825550178")).isOne();
    assertThat(seatOf("+27825550179")).isEqualTo(2);
    assertThat(full).hasStatus(HttpStatus.CONFLICT);
    assertThat(full).bodyJson().extractingPath("$.code").isEqualTo("SLOT_FULL");
  }

  /**
   * BR-22: a request that overlaps the customer's own appointment at a slot that is also full is
   * told about the overlap, which the customer can act on, not that the slot is full.
   */
  @Test
  void overlapIsReportedBeforeSlotFull() {
    assertThat(book(mvc, body("SAN", "CARD", "2026-10-27T09:00:00+02:00", "082 555 0181")))
        .hasStatus(HttpStatus.CREATED);
    assertThat(book(mvc, body("POL", "FICA", "2026-10-27T09:00:00+02:00", "082 555 0182")))
        .hasStatus(HttpStatus.CREATED);

    MvcTestResult both =
        book(mvc, body("POL", "FICA", "2026-10-27T09:00:00+02:00", "082 555 0181"));

    assertThat(both).hasStatus(HttpStatus.CONFLICT);
    assertThat(both).bodyJson().extractingPath("$.code").isEqualTo("CUSTOMER_OVERLAP");
  }

  /**
   * NFR-8, BR-22: a booking that cannot get the seat lock within 2 seconds gives up with 503
   * TRY_AGAIN and Retry-After: 2, and writes nothing. Another connection holds Sandton's FICA row.
   */
  @Test
  void lockTimeoutReturns503TryAgainWithRetryAfter() throws Exception {
    MvcTestResult waited;
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement lock =
          holder.prepareStatement(
              "SELECT capacity FROM branch_services WHERE branch_id = ? AND service_id = ? FOR UPDATE")) {
        lock.setObject(1, SeedData.SANDTON);
        lock.setObject(2, SeedData.FICA);
        lock.executeQuery().close();
      }
      try {
        waited = book(mvc, body("SAN", "FICA", "2026-10-27T10:00:00+02:00", "082 555 0198"));
      } finally {
        holder.rollback();
      }
    }

    assertThat(waited).hasStatus(HttpStatus.SERVICE_UNAVAILABLE).hasHeader("Retry-After", "2");
    assertThat(waited).bodyJson().extractingPath("$.code").isEqualTo("TRY_AGAIN");
    assertThat(count("appointments") + count("idempotency_keys") + count("notification_messages"))
        .isZero();
  }

  /**
   * BR-22: a deadlock (40P01), as a path that bypassed the lock order could cause, runs the whole
   * booking once more, which then succeeds. A test-only trigger raises it on the first insert only;
   * a sequence counts the inserts, because a sequence is not rolled back with the transaction.
   */
  @Test
  void deadlockOnceIsRerunAndBooks() {
    Deadlocked booked =
        withDeadlockOnInsert(
            "nextval('test_deadlock_inserts') = 1",
            () -> book(mvc, body("SAN", "FICA", "2026-10-27T11:00:00+02:00", "082 555 0153")));

    assertThat(booked.result()).hasStatus(HttpStatus.CREATED);
    assertThat(booked.inserts()).as("the first insert and the re-run's").isEqualTo(2);
    assertThat(count("appointments")).isOne();
    assertThat(count("notification_messages")).isOne();
  }

  /**
   * BR-22: a second deadlock in the same booking gives up with 503 TRY_AGAIN and Retry-After: 2,
   * and writes nothing.
   */
  @Test
  void deadlockTwiceReturns503TryAgainAndLeavesNothing() {
    Deadlocked refused =
        withDeadlockOnInsert(
            "nextval('test_deadlock_inserts') > 0",
            () -> book(mvc, body("SAN", "FICA", "2026-10-27T11:00:00+02:00", "082 555 0154")));

    assertThat(refused.result())
        .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .hasHeader("Retry-After", "2");
    assertThat(refused.result()).bodyJson().extractingPath("$.code").isEqualTo("TRY_AGAIN");
    assertThat(refused.inserts()).as("one re-run, never more").isEqualTo(2);
    assertThat(count("appointments") + count("idempotency_keys") + count("notification_messages"))
        .isZero();
  }

  /**
   * What a request did while inserting an appointment raised 40P01: its result and the inserts
   * tried.
   */
  private record Deadlocked(MvcTestResult result, long inserts) {}

  /** Runs a request while inserting an appointment raises 40P01 whenever the condition holds. */
  private Deadlocked withDeadlockOnInsert(String condition, Supplier<MvcTestResult> request) {
    try {
      jdbc.execute("CREATE SEQUENCE test_deadlock_inserts");
      jdbc.execute(
          """
          CREATE FUNCTION test_deadlock_on_insert() RETURNS trigger LANGUAGE plpgsql AS $$
          BEGIN
            IF %s THEN
              RAISE EXCEPTION 'test: deadlock' USING ERRCODE = 'deadlock_detected';
            END IF;
            RETURN NEW;
          END $$"""
              .formatted(condition));
      jdbc.execute(
          """
          CREATE TRIGGER test_deadlock_on_insert BEFORE INSERT ON appointments
          FOR EACH ROW EXECUTE FUNCTION test_deadlock_on_insert()""");
      MvcTestResult result = request.get();
      Long inserts =
          jdbc.queryForObject("SELECT last_value FROM test_deadlock_inserts", Long.class);
      return new Deadlocked(result, inserts == null ? 0 : inserts);
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS test_deadlock_on_insert ON appointments");
      jdbc.execute("DROP FUNCTION IF EXISTS test_deadlock_on_insert()");
      jdbc.execute("DROP SEQUENCE IF EXISTS test_deadlock_inserts");
    }
  }

  /** BR-10: one upcoming appointment per service; the refusal says nothing about the first. */
  @Test
  void sameMobileSameServiceSecondBookingReturns409WithoutRevealingReference() {
    assertThat(book(mvc, body("SAN", "FICA", "2026-10-27T10:00:00+02:00", "082 555 0162")))
        .hasStatus(HttpStatus.CREATED);

    MvcTestResult second =
        book(mvc, body("BEL", "FICA", "2026-10-28T10:00:00+02:00", "082 555 0162"));

    assertThat(second).hasStatus(HttpStatus.CONFLICT);
    assertThat(second)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {
              "type": "urn:branchbook:problem:DUPLICATE_APPOINTMENT",
              "title": "You already have this appointment",
              "status": 409,
              "detail": "You already have an upcoming appointment for this service. Use the reference in your SMS to manage it.",
              "instance": "urn:branchbook:request:test-booking",
              "code": "DUPLICATE_APPOINTMENT",
              "correlationId": "test-booking"
            }""");
  }

  /** BR-9: one person, one place, at any branch and for any service. */
  @Test
  void overlappingAppointmentAtAnotherBranchReturns409CustomerOverlap() {
    assertThat(book(mvc, body("SAN", "FICA", "2026-10-27T10:00:00+02:00", "082 555 0163")))
        .hasStatus(HttpStatus.CREATED);

    MvcTestResult overlap =
        book(mvc, body("BEL", "CARD", "2026-10-27T10:00:00+02:00", "082 555 0163"));

    assertThat(overlap).hasStatus(HttpStatus.CONFLICT);
    assertThat(overlap).bodyJson().extractingPath("$.code").isEqualTo("CUSTOMER_OVERLAP");
  }

  /** BR-22, as evidence: a request that breaks both customer rules gets the duplicate. */
  @Test
  void duplicateIsReportedBeforeOverlap() {
    assertThat(book(mvc, body("SAN", "FICA", "2026-10-27T10:00:00+02:00", "082 555 0164")))
        .hasStatus(HttpStatus.CREATED);

    assertThat(book(mvc, body("BEL", "FICA", "2026-10-27T10:00:00+02:00", "082 555 0164")))
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("DUPLICATE_APPOINTMENT");
  }

  /** BR-11: a reference that is already taken makes the transaction run once more. */
  @Test
  void referenceCollisionRerunsTransactionOnce() {
    references.script("APT-TAKN-AAAA");
    assertThat(book(mvc, body("SAN", "FICA", "2026-10-27T10:00:00+02:00", "082 555 0165")))
        .hasStatus(HttpStatus.CREATED);
    references.script("APT-TAKN-AAAA", "APT-FRSH-2222");

    MvcTestResult rerun =
        book(mvc, body("SAN", "CARD", "2026-10-28T10:00:00+02:00", "082 555 0166"));

    assertThat(rerun).hasStatus(HttpStatus.CREATED);
    assertThat(rerun).bodyJson().extractingPath("$.reference").isEqualTo("APT-FRSH-2222");
    assertThat(count("appointments")).isEqualTo(2);
    assertThat(count("notification_messages")).isEqualTo(2);
  }

  /** BR-11: a second collision means a broken random source: a 500, and nothing written. */
  @Test
  void secondReferenceCollisionReturns500AndLeavesNothing() {
    references.script("APT-TAKN-AAAA");
    assertThat(book(mvc, body("SAN", "FICA", "2026-10-27T10:00:00+02:00", "082 555 0167")))
        .hasStatus(HttpStatus.CREATED);
    references.script("APT-TAKN-AAAA", "APT-TAKN-AAAA");

    MvcTestResult broken =
        book(mvc, body("SAN", "CARD", "2026-10-28T10:00:00+02:00", "082 555 0168"));

    assertThat(broken).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(broken).bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
    assertThat(count("appointments")).isOne();
    assertThat(count("idempotency_keys")).isOne();
    assertThat(count("notification_messages")).isOne();
  }

  /**
   * BR-8: a seat taken by a path that skipped the locks is refused at the insert itself, by the
   * exclusion constraint, and reported as SLOT_FULL; a customer in two places, as CUSTOMER_OVERLAP.
   */
  @Test
  void constraintViolationIsRaisedAtInsertNotAtCommit() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    Instant startsAt = Instant.parse("2026-10-27T09:00:00Z");

    assertThatExceptionOfType(SlotFullException.class)
        .isThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      appointments.insert(appointment(SeedData.SANDTON, 1, startsAt, "0169"));
                      appointments.insert(appointment(SeedData.SANDTON, 1, startsAt, "0170"));
                    }));
    assertThatExceptionOfType(CustomerOverlapException.class)
        .isThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      appointments.insert(appointment(SeedData.SANDTON, 1, startsAt, "0169"));
                      appointments.insert(appointment(SeedData.BELLVILLE, 1, startsAt, "0169"));
                    }));
    assertThat(count("appointments")).isZero();
  }

  private static Appointment appointment(UUID branch, int seat, Instant startsAt, String suffix) {
    return Appointment.confirm(
        AppointmentReference.generate(new java.security.SecureRandom()),
        branch,
        SeedData.FICA,
        Duration.ofMinutes(15),
        seat,
        startsAt,
        CustomerDetails.of(
            CustomerName.parse("Nomvula").orElseThrow(),
            CustomerName.parse("Dlamini").orElseThrow(),
            MobileNumber.parse("082 555 " + suffix).orElseThrow()),
        Instant.parse("2026-10-26T06:00:00Z"));
  }

  private int seatOf(String mobileE164) {
    Integer seat =
        jdbc.queryForObject(
            "SELECT seat_no FROM appointments WHERE customer_mobile = ? AND status = 'CONFIRMED'",
            Integer.class,
            mobileE164);
    return seat == null ? 0 : seat;
  }

  private int count(String table) {
    Integer rows = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    return rows == null ? 0 : rows;
  }
}
