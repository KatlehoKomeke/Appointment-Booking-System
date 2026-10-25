package za.co.fynbos.branchbook.scheduling.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;
import za.co.fynbos.branchbook.support.SeedData;

/**
 * The availability endpoint, through the whole application and a real database: the slot grid of
 * the seeded branches, the seats that appointments take, closed days, and the error contract.
 */
@IntegrationTest
class AvailabilityApiIT {

  /** 06:00 on Monday 2 November 2026 in Johannesburg, two hours before the branches open. */
  private static final Instant MONDAY_2_NOVEMBER_06_00 = Instant.parse("2026-11-02T04:00:00Z");

  private static final ZoneId JOHANNESBURG = ZoneId.of("Africa/Johannesburg");

  /**
   * Every appointment this class inserts has a reference with this prefix, so it can remove them.
   */
  private static final String TEST_REFERENCE_PREFIX = "APT-TEST-";

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private MutableClock clock;

  /** JUnit creates an instance per test, so every test starts again at 082 555 0111. */
  private int nextMobileSuffix = 11;

  @BeforeEach
  void setTheClock() {
    clock.setInstant(MONDAY_2_NOVEMBER_06_00);
  }

  @AfterEach
  void removeTheTestAppointments() {
    jdbc.update("DELETE FROM appointments WHERE reference LIKE ?", TEST_REFERENCE_PREFIX + "%");
  }

  /**
   * BR-20 and the whole response shape, on Rustenburg's short Saturday: five 45-minute slots, each
   * instant written with the branch's +02:00 offset whatever the JVM's default zone.
   */
  @Test
  void rendersStartsAtWithJohannesburgOffset() {
    MvcTestResult result =
        mvc.get().uri("/api/v1/branches/RUS/availability?service=HOME&date=2026-11-07").exchange();

    assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
    assertThat(result)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {
              "branchCode": "RUS",
              "serviceCode": "HOME",
              "date": "2026-11-07",
              "timezone": "Africa/Johannesburg",
              "generatedAt": "2026-11-02T06:00:00+02:00",
              "closedReason": null,
              "holidayName": null,
              "slots": [
                {"startsAt": "2026-11-07T08:00:00+02:00", "endsAt": "2026-11-07T08:45:00+02:00",
                 "seatsAvailable": 2},
                {"startsAt": "2026-11-07T08:45:00+02:00", "endsAt": "2026-11-07T09:30:00+02:00",
                 "seatsAvailable": 2},
                {"startsAt": "2026-11-07T09:30:00+02:00", "endsAt": "2026-11-07T10:15:00+02:00",
                 "seatsAvailable": 2},
                {"startsAt": "2026-11-07T10:15:00+02:00", "endsAt": "2026-11-07T11:00:00+02:00",
                 "seatsAvailable": 2},
                {"startsAt": "2026-11-07T11:00:00+02:00", "endsAt": "2026-11-07T11:45:00+02:00",
                 "seatsAvailable": 2}
              ]
            }""");
  }

  /** BR-1 and BR-2 on the seeded data: a weekday of 15-minute slots from 08:00 to 16:45. */
  @Test
  void weekdayHasThirtySixFifteenMinuteSlotsWithTheBranchsCapacity() {
    MvcTestResult result =
        mvc.get().uri("/api/v1/branches/SAN/availability?service=FICA&date=2026-11-03").exchange();

    assertThat(result).bodyJson().extractingPath("$.slots.length()").isEqualTo(36);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.slots[0].startsAt")
        .isEqualTo("2026-11-03T08:00:00+02:00");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.slots[35].startsAt")
        .isEqualTo("2026-11-03T16:45:00+02:00");
    // Sandton has three seats for FICA updates.
    assertThat(result)
        .bodyJson()
        .extractingPath("$.slots[*].seatsAvailable")
        .asArray()
        .containsOnly(3);
  }

  /** BR-4: at 09:10 the earliest start is 11:10, so today's first 15-minute slot is 11:15. */
  @Test
  void todaysSlotsStartAfterTheLeadTime() {
    clock.setInstant(LocalDateTime.parse("2026-11-02T09:10").atZone(JOHANNESBURG).toInstant());

    MvcTestResult result =
        mvc.get().uri("/api/v1/branches/SAN/availability?service=FICA&date=2026-11-02").exchange();

    assertThat(result)
        .bodyJson()
        .extractingPath("$.slots[0].startsAt")
        .isEqualTo("2026-11-02T11:15:00+02:00");
    assertThat(result).bodyJson().extractingPath("$.closedReason").isNull();
  }

  /**
   * Only active appointments of the same service at the same branch take a seat. The cancelled
   * appointment, the other branch's and the other service's leave Sandton's FICA seats alone.
   */
  @Test
  void seatsAvailableCountsActiveAppointmentsOfThatServiceAtThatBranch() {
    insertAppointment("APT-TEST-AAA2", SeedData.SANDTON, SeedData.FICA, 1, "2026-11-03T09:00", 15);
    insertAppointment("APT-TEST-AAA3", SeedData.SANDTON, SeedData.FICA, 2, "2026-11-03T09:00", 15);
    cancel("APT-TEST-AAA3");
    insertAppointment(
        "APT-TEST-AAA4", SeedData.BELLVILLE, SeedData.FICA, 1, "2026-11-03T09:00", 15);
    insertAppointment("APT-TEST-AAA5", SeedData.SANDTON, SeedData.CARD, 1, "2026-11-03T09:00", 15);

    MvcTestResult result =
        mvc.get().uri("/api/v1/branches/SAN/availability?service=FICA&date=2026-11-03").exchange();

    assertSeats(result, "2026-11-03T08:45:00+02:00", 3);
    assertSeats(result, "2026-11-03T09:00:00+02:00", 2);
    assertSeats(result, "2026-11-03T09:15:00+02:00", 3);
  }

  /** A full slot stays in the list with no seats, so the grid can show it as taken. */
  @Test
  void fullSlotIsListedWithNoSeatsAvailable() {
    UUID polokwane = branchId("POL");
    insertAppointment("APT-TEST-AAB2", polokwane, SeedData.FICA, 1, "2026-11-03T10:00", 15);

    MvcTestResult result =
        mvc.get().uri("/api/v1/branches/POL/availability?service=FICA&date=2026-11-03").exchange();

    assertThat(result).bodyJson().extractingPath("$.slots.length()").isEqualTo(36);
    assertSeats(result, "2026-11-03T10:00:00+02:00", 0);
  }

  /** BR-3: a day of the week without trading hours. */
  @Test
  void dayWithoutTradingHoursReturnsClosedDayAndNoSlots() {
    assertThat(mvc.get().uri("/api/v1/branches/POL/availability?service=FICA&date=2026-11-08"))
        .hasStatusOk()
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"date": "2026-11-08", "closedReason": "CLOSED_DAY", "holidayName": null,
             "slots": []}""");
  }

  /** BR-3: the proclaimed election day closes every branch that does not trade on holidays. */
  @Test
  void publicHolidayReturnsTheHolidaysNameAndNoSlots() {
    assertThat(mvc.get().uri("/api/v1/branches/SAN/availability?service=FICA&date=2026-11-04"))
        .hasStatusOk()
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"date": "2026-11-04", "closedReason": "PUBLIC_HOLIDAY",
             "holidayName": "Local Government Elections", "slots": []}""");
  }

  /** BR-5: the window is today through 1 December; the day after it and yesterday are refused. */
  @Test
  void dateOutsideWindowReturns422() {
    assertThat(mvc.get().uri("/api/v1/branches/SAN/availability?service=FICA&date=2026-12-01"))
        .hasStatusOk();

    MvcTestResult afterTheWindow =
        mvc.get()
            .uri("/api/v1/branches/SAN/availability?service=FICA&date=2026-12-02")
            .header("X-Correlation-Id", "test-availability-1")
            .exchange();

    assertThat(afterTheWindow)
        .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(afterTheWindow)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {
              "type": "urn:branchbook:problem:DATE_OUTSIDE_BOOKING_WINDOW",
              "title": "Date outside the booking window",
              "status": 422,
              "detail": "That date can't be booked. Choose a date from bookableFrom to bookableTo.",
              "instance": "urn:branchbook:request:test-availability-1",
              "code": "DATE_OUTSIDE_BOOKING_WINDOW",
              "correlationId": "test-availability-1"
            }""");
    assertThat(mvc.get().uri("/api/v1/branches/SAN/availability?service=FICA&date=2026-11-01"))
        .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("DATE_OUTSIDE_BOOKING_WINDOW");
  }

  /**
   * BR-6: Gqeberha offers no home loans. An unknown service code gets the same answer, and the
   * service is checked before the date.
   */
  @Test
  void serviceNotOfferedAtBranchReturns422() {
    MvcTestResult result =
        mvc.get()
            .uri("/api/v1/branches/GQE/availability?service=HOME&date=2026-11-03")
            .header("X-Correlation-Id", "test-availability-2")
            .exchange();

    assertThat(result)
        .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {
              "type": "urn:branchbook:problem:SERVICE_NOT_OFFERED_AT_BRANCH",
              "title": "Service not offered at this branch",
              "status": 422,
              "detail": "This branch doesn't offer that service.",
              "instance": "urn:branchbook:request:test-availability-2",
              "code": "SERVICE_NOT_OFFERED_AT_BRANCH",
              "correlationId": "test-availability-2"
            }""");
    assertThat(mvc.get().uri("/api/v1/branches/GQE/availability?service=NOPE&date=2026-11-03"))
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("SERVICE_NOT_OFFERED_AT_BRANCH");
    assertThat(mvc.get().uri("/api/v1/branches/GQE/availability?service=HOME&date=2027-01-01"))
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("SERVICE_NOT_OFFERED_AT_BRANCH");
  }

  /** The branch is checked first: an unknown branch is 404 whatever else is wrong. */
  @Test
  void unknownBranchReturns404() {
    assertThat(mvc.get().uri("/api/v1/branches/XYZ/availability?service=NOPE&date=2027-01-01"))
        .hasStatus(HttpStatus.NOT_FOUND)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("BRANCH_NOT_FOUND");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          service=FICA                 | date    | REQUIRED      | is required
          date=2026-11-03              | service | REQUIRED      | is required
          service=F1CA&date=2026-11-03 | service | PATTERN       | must be 2 to 12 letters
          service=FICA&date=2026-13-01 | date    | TYPE_MISMATCH | has the wrong format
          service=FICA&date=03-11-2026 | date    | TYPE_MISMATCH | has the wrong format
          """)
  void missingOrMalformedParameterReturns400NamingIt(
      String query, String field, String code, String message) {
    MvcTestResult result = mvc.get().uri("/api/v1/branches/SAN/availability?" + query).exchange();

    assertThat(result)
        .hasStatus(HttpStatus.BAD_REQUEST)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors")
        .isEqualTo(List.of(Map.of("field", field, "code", code, "message", message)));
  }

  @Test
  void lowerCaseCodesAreAccepted() {
    MvcTestResult result =
        mvc.get().uri("/api/v1/branches/san/availability?service=fica&date=2026-11-03").exchange();

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.branchCode").isEqualTo("SAN");
    assertThat(result).bodyJson().extractingPath("$.serviceCode").isEqualTo("FICA");
  }

  /**
   * Inserts a confirmed appointment the way only a test may: raw SQL past the booking flow. Each
   * gets its own synthetic mobile number from the demo range in SECURITY.md (082 555 0111 onwards),
   * so the one-place-at-a-time constraint never links them.
   */
  private void insertAppointment(
      String reference, UUID branch, UUID service, int seat, String localStart, int minutes) {
    OffsetDateTime startsAt =
        LocalDateTime.parse(localStart).atZone(JOHANNESBURG).toOffsetDateTime();
    jdbc.update(
        """
        INSERT INTO appointments (id, reference, branch_id, service_id, seat_no, starts_at, ends_at,
                                  status, customer_first_name, customer_last_name, customer_mobile)
        VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'CONFIRMED', 'Thandi', 'Mokoena', ?)""",
        reference,
        branch,
        service,
        seat,
        startsAt,
        startsAt.plusMinutes(minutes),
        "+278255501" + nextMobileSuffix++);
  }

  private void cancel(String reference) {
    jdbc.update(
        "UPDATE appointments SET status = 'CANCELLED', cancelled_at = ? WHERE reference = ?",
        OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
        reference);
  }

  private UUID branchId(String code) {
    return jdbc.queryForObject("SELECT id FROM branches WHERE code = ?", UUID.class, code);
  }

  /** Asserts that exactly one slot starts at the instant given, with that many seats free. */
  private static void assertSeats(MvcTestResult result, String startsAt, int seatsAvailable) {
    assertThat(result)
        .bodyJson()
        .extractingPath("$.slots[?(@.startsAt == '" + startsAt + "')].seatsAvailable")
        .asArray()
        .containsExactly(seatsAvailable);
  }
}
