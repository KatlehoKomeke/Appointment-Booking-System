package za.co.fynbos.branchbook.catalogue.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * The branch endpoints, through the whole application and a real database: the catalogue the
 * booking flow starts from, the bookable window and closed days, and the error contract.
 */
@IntegrationTest
class BranchApiIT {

  /** 08:00 on Friday 20 November 2026 in Johannesburg: 16 December falls inside the window. */
  private static final Instant FRIDAY_20_NOVEMBER_2026 = Instant.parse("2026-11-20T06:00:00Z");

  private static final ZoneId JOHANNESBURG = ZoneId.of("Africa/Johannesburg");

  private static final UUID ACTIVE_TEST_BRANCH =
      UUID.fromString("c0000000-0000-4000-8000-000000000001");
  private static final UUID INACTIVE_TEST_BRANCH =
      UUID.fromString("c0000000-0000-4000-8000-000000000002");
  private static final UUID RETIRED_SERVICE =
      UUID.fromString("c0000000-0000-4000-8000-000000000003");

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private MutableClock clock;

  @BeforeEach
  void setTheClock() {
    clock.setInstant(FRIDAY_20_NOVEMBER_2026);
  }

  @Test
  void listsTheSixActiveBranchesOrderedByName() {
    assertThat(mvc.get().uri("/api/v1/branches"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.branches[*].code")
        .asArray()
        .containsExactly("BEL", "GQE", "POL", "RUS", "SAN", "UMH");
  }

  /**
   * The list is ordered by name, not by code, and inactive branches and services never appear. The
   * seeded branches sort the same way by name and by code, so this test adds an active branch whose
   * code sorts first and whose name sorts last, plus an inactive branch and an inactive service,
   * and removes them afterwards.
   */
  @Test
  void branchesAreOrderedByNameAndInactiveBranchesAndServicesAreHidden() {
    try {
      jdbc.update(
          """
          INSERT INTO branches
              (id, code, name, sms_name, address_line1, suburb, city, province, postal_code, active)
          VALUES (?, 'AAA', 'Zz Test Branch', 'Zz Test Branch', '1 Test Road', 'Testville',
                  'Test City', 'Gauteng', '2000', true),
                 (?, 'ZZZ', 'Closed Test Branch', 'Closed Test', '2 Test Road', 'Testville',
                  'Test City', 'Gauteng', '2000', false)""",
          ACTIVE_TEST_BRANCH,
          INACTIVE_TEST_BRANCH);
      jdbc.update(
          """
          INSERT INTO services (id, code, name, sms_name, description, duration_minutes,
                                documents_needed, sort_order, active)
          VALUES (?, 'RETIRED', 'Retired service', 'Retired service', 'No longer offered.', 15,
                  ARRAY['Valid SA ID'], 0, false)""",
          RETIRED_SERVICE);
      jdbc.update(
          "INSERT INTO branch_services (branch_id, service_id, capacity) VALUES (?, ?, 1)",
          SeedData.SANDTON,
          RETIRED_SERVICE);

      assertThat(mvc.get().uri("/api/v1/branches"))
          .bodyJson()
          .extractingPath("$.branches[*].code")
          .asArray()
          .containsExactly("BEL", "GQE", "POL", "RUS", "SAN", "UMH", "AAA");
      assertThat(mvc.get().uri("/api/v1/branches/ZZZ")).hasStatus(HttpStatus.NOT_FOUND);
      assertThat(mvc.get().uri("/api/v1/branches/SAN"))
          .bodyJson()
          .extractingPath("$.services[*].code")
          .asArray()
          .doesNotContain("RETIRED");
    } finally {
      jdbc.update("DELETE FROM branch_services WHERE service_id = ?", RETIRED_SERVICE);
      jdbc.update("DELETE FROM services WHERE id = ?", RETIRED_SERVICE);
      jdbc.update(
          "DELETE FROM branches WHERE id IN (?, ?)", ACTIVE_TEST_BRANCH, INACTIVE_TEST_BRANCH);
    }
  }

  @Test
  void listShowsWhichBranchesOpenOnSundays() {
    assertThat(mvc.get().uri("/api/v1/branches"))
        .bodyJson()
        .extractingPath("$.branches[?(@.opensSunday == true)].code")
        .asArray()
        .containsExactly("SAN", "UMH");
  }

  /** BR-5: thirty bookable dates, starting today, computed and published by the server. */
  @Test
  void detailCarriesServerBookableWindow() {
    MvcTestResult result = mvc.get().uri("/api/v1/branches/SAN").exchange();

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.bookableFrom").isEqualTo("2026-11-20");
    assertThat(result).bodyJson().extractingPath("$.bookableTo").isEqualTo("2026-12-19");
  }

  /**
   * At 23:30 UTC on 19 November it is already 01:30 on 20 November in Johannesburg, so the window
   * starts on the 20th: "today" is the branch's date, not the server's UTC date.
   */
  @Test
  void windowStartsOnTheBranchsDateNotTheUtcDate() {
    clock.setInstant(Instant.parse("2026-11-19T23:30:00Z"));
    MvcTestResult result = mvc.get().uri("/api/v1/branches/SAN").exchange();

    assertThat(result).bodyJson().extractingPath("$.bookableFrom").isEqualTo("2026-11-20");
    assertThat(result).bodyJson().extractingPath("$.bookableTo").isEqualTo("2026-12-19");
  }

  /** BR-3: a day of the week without trading hours is closed, without a holiday name. */
  @Test
  void dayWithoutTradingHoursIsListedAsAClosedDay() {
    assertThat(mvc.get().uri("/api/v1/branches/POL"))
        .bodyJson()
        .extractingPath("$.closedDates[?(@.date == '2026-11-22')]")
        .asArray()
        .singleElement()
        .isEqualTo(mapOf("date", "2026-11-22", "reason", "CLOSED_DAY", "name", null));
  }

  /** BR-3: public holidays inside the window are listed with their names. */
  @Test
  void detailListsClosedDatesWithHolidayNames() {
    MvcTestResult result = mvc.get().uri("/api/v1/branches/SAN").exchange();

    assertThat(result)
        .bodyJson()
        .extractingPath("$.closedDates[?(@.date == '2026-12-16')]")
        .asArray()
        .singleElement()
        .isEqualTo(
            Map.of(
                "date", "2026-12-16", "reason", "PUBLIC_HOLIDAY", "name", "Day of Reconciliation"));
    // Sandton trades every day of the week, so only holidays close it.
    assertThat(result)
        .bodyJson()
        .extractingPath("$.closedDates[*].reason")
        .asArray()
        .containsOnly("PUBLIC_HOLIDAY");
  }

  /**
   * On the real clock rather than a pinned one: every Sunday in the coming thirty days is closed at
   * a branch without Sunday hours, so the date strip never offers one.
   */
  @Test
  void everySundayInTheWindowIsClosedAtABranchWithoutSundayHours() {
    clock.setInstant(Instant.now());
    LocalDate today = LocalDate.now(clock.withZone(JOHANNESBURG));
    List<String> sundays =
        today
            .datesUntil(today.plusDays(30))
            .filter(date -> date.getDayOfWeek() == DayOfWeek.SUNDAY)
            .map(LocalDate::toString)
            .toList();

    assertThat(mvc.get().uri("/api/v1/branches/POL"))
        .bodyJson()
        .extractingPath("$.closedDates[*].date")
        .asArray()
        .containsAll(sundays);
  }

  @Test
  void detailListsOnlyTheServicesTheBranchOffersInDisplayOrder() {
    assertThat(mvc.get().uri("/api/v1/branches/GQE"))
        .bodyJson()
        .extractingPath("$.services[*].code")
        .asArray()
        .containsExactly("FICA", "CARD", "OPEN", "CREDIT", "ESTATE");
  }

  @Test
  void detailGivesTradingHoursAsWallClockTimes() {
    MvcTestResult result = mvc.get().uri("/api/v1/branches/RUS").exchange();

    assertThat(result).bodyJson().extractingPath("$.timezone").isEqualTo("Africa/Johannesburg");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.operatingHours[?(@.weekday == 6)]")
        .asArray()
        .singleElement()
        .isEqualTo(Map.of("weekday", 6, "opensAt", "08:00", "closesAt", "12:00"));
  }

  @Test
  void lowerCaseBranchCodeIsAccepted() {
    assertThat(mvc.get().uri("/api/v1/branches/umh"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("UMH");
  }

  @Test
  void unknownBranchReturns404ProblemWithCodeAndCorrelationId() {
    MvcTestResult result =
        mvc.get()
            .uri("/api/v1/branches/XYZ")
            .header("X-Correlation-Id", "test-lookup-1")
            .exchange();

    assertThat(result)
        .hasStatus(HttpStatus.NOT_FOUND)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).headers().hasValue("X-Correlation-Id", "test-lookup-1");
    assertThat(result)
        .bodyJson()
        .isEqualTo(
            """
            {
              "type": "urn:branchbook:problem:BRANCH_NOT_FOUND",
              "title": "Branch not found",
              "status": 404,
              "detail": "We couldn't find that branch.",
              "instance": "urn:branchbook:request:test-lookup-1",
              "code": "BRANCH_NOT_FOUND",
              "correlationId": "test-lookup-1"
            }""");
  }

  @Test
  void malformedBranchCodeReturns400NamingTheField() {
    MvcTestResult result =
        mvc.get().uri("/api/v1/branches/12").header("X-Correlation-Id", "test-lookup-2").exchange();

    assertThat(result)
        .hasStatus(HttpStatus.BAD_REQUEST)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    assertThat(result).bodyJson().extractingPath("$.correlationId").isEqualTo("test-lookup-2");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors")
        .isEqualTo(
            List.of(
                Map.of("field", "code", "code", "PATTERN", "message", "must be 2 to 8 letters")));
  }

  @Test
  void unacceptableCorrelationIdIsReplacedWithANewOne() {
    MvcTestResult result =
        mvc.get().uri("/api/v1/branches").header("X-Correlation-Id", "not acceptable!").exchange();

    assertThat(result.getResponse().getHeader("X-Correlation-Id"))
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }

  /** A map that, unlike {@code Map.of}, may hold a JSON null. */
  private static Map<String, Object> mapOf(Object... keysAndValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
  }
}
