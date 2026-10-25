package za.co.fynbos.branchbook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
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

  private int count(String table) {
    Integer rows = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    return rows == null ? 0 : rows;
  }
}
