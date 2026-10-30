package za.co.fynbos.branchbook.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;

/**
 * ADR-0006: with {@code branchbook.booking.demo-numbers-only} on, as the hosted demo runs, a
 * booking takes only the published demo numbers, 082 555 0000 to 0199. Any other South African
 * mobile is {@code 422 DEMO_NUMBER_REQUIRED} on {@code customer.mobile}, decided where {@code
 * MOBILE_NUMBER_REQUIRED} is, so nothing is written and the number is repeated nowhere.
 *
 * <p>This test builds an application context of its own, and with it a database container of its
 * own, because the container is a bean of the context. The numbers outside the block are used only
 * to be refused; the clock stands at 08:00 on Monday 26 October 2026 in Johannesburg.
 */
@IntegrationTest
@ExtendWith({DatabaseReset.class, OutputCaptureExtension.class})
@TestPropertySource(properties = "branchbook.booking.demo-numbers-only=true")
class DemoNumbersOnlyIT {

  private static final String TUESDAY_0930 = "2026-10-27T09:30:00+02:00";

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private ConfigurableApplicationContext context;

  @Autowired private MeterRegistry meters;

  /**
   * A number just past the block and an everyday one are refused before the booking transaction: no
   * appointment, no message and no idempotency key. The body repeats no four digits of the number
   * in a row, and the log written while the request ran holds the number in no form.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"082 555 0200", "072 345 6789"})
  void aNumberOutsideTheBlockIsRefusedOnItsFieldAndWritesNothing(
      String typed, CapturedOutput output) throws Exception {
    Map<String, Integer> before = rowCounts();
    int logBefore = output.getAll().length();

    MvcTestResult result = book(mvc, body("SAN", "FICA", TUESDAY_0930, typed));
    String log = output.getAll().substring(logBefore);

    assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("DEMO_NUMBER_REQUIRED");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*]['field','code']")
        .asArray()
        .containsExactly(Map.of("field", "customer.mobile", "code", "DEMO_NUMBER_REQUIRED"));
    assertThat(rowCounts()).isEqualTo(before).isEqualTo(rows(0, 0, 0));
    String withoutIds = bodyWithoutCorrelationIds(result);
    String national = MobileNumber.parse(typed).orElseThrow().display().replace(" ", "");
    assertThat(fourDigitRuns(national))
        .allSatisfy(run -> assertThat(withoutIds).doesNotContain(run));
    assertThat(log)
        .doesNotContain(typed)
        .doesNotContain(national)
        .doesNotContain(national.substring(1))
        .doesNotContain("+27" + national.substring(1));
  }

  /** Both ends of the block book as they would anywhere else. */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"082 555 0000", "+27 82 555 0199"})
  void aNumberInTheBlockBooks(String typed) {
    MvcTestResult result = book(mvc, body("SAN", "FICA", TUESDAY_0930, typed));

    assertThat(result).hasStatus(HttpStatus.CREATED);
    assertThat(rowCounts()).isEqualTo(rows(1, 1, 1));
  }

  /** A number that is not a South African mobile at all is refused for that first. */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"+44 7911 123456", "021 555 0143"})
  void aNumberThatIsNotASouthAfricanMobileIsStillMobileNumberRequired(String typed) {
    MvcTestResult result = book(mvc, body("SAN", "FICA", TUESDAY_0930, typed));

    assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MOBILE_NUMBER_REQUIRED");
    assertThat(rowCounts()).isEqualTo(rows(0, 0, 0));
  }

  /**
   * The refusal keeps MOBILE_NUMBER_REQUIRED's place in docs/errors.md: after a missing {@code
   * Idempotency-Key}, and before the key is compared with what it booked, so a retry of a demo
   * booking with another number is refused for the number, not as a reused key.
   */
  @Test
  void theRefusalComesAfterTheMissingKeyAndBeforeTheReusedKey() {
    MvcTestResult withoutKey = book(mvc, null, body("SAN", "FICA", TUESDAY_0930, "082 555 0200"));
    String key = UUID.randomUUID().toString();
    MvcTestResult booked = book(mvc, key, body("SAN", "FICA", TUESDAY_0930, "082 555 0160"));
    MvcTestResult reused = book(mvc, key, body("SAN", "FICA", TUESDAY_0930, "082 555 0200"));

    assertThat(withoutKey)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    assertThat(booked).hasStatus(HttpStatus.CREATED);
    assertThat(reused).bodyJson().extractingPath("$.code").isEqualTo("DEMO_NUMBER_REQUIRED");
    assertThat(rowCounts()).isEqualTo(rows(1, 1, 1));
  }

  /**
   * The request {@code EXPECT_DEMO_NUMBERS_ONLY=1 scripts/smoke.sh} sends: a number just past the
   * block, with a start long past, is refused for its number, which is checked before the date.
   * With the setting off it is refused for the date instead, and the smoke check fails.
   */
  @Test
  void theSmokeChecksRequestIsRefusedForItsNumberBeforeItsDate() {
    MvcTestResult result =
        book(mvc, body("SAN", "FICA", "2000-01-03T09:00:00+02:00", "082 555 0200"));

    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("DEMO_NUMBER_REQUIRED");
    assertThat(rowCounts()).isEqualTo(rows(0, 0, 0));
  }

  /**
   * Once the application is ready, the log says that bookings take only the published demo numbers,
   * so a deployment's start-up log shows the setting is on. The event is published again here
   * because the context may have started before this test's output was captured.
   */
  @Test
  void theLogSaysOnlyTheDemoNumbersAreTaken(CapturedOutput output) {
    context.publishEvent(
        new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));

    assertThat(output)
        .contains(
            "Bookings take only the published demo numbers, 082 555 0000 to 082 555 0199"
                + " (branchbook.booking.demo-numbers-only is on).");
  }

  /**
   * FR-30: a refusal for the number is counted once, as the {@code validation} outcome that {@code
   * MOBILE_NUMBER_REQUIRED} is counted as, so on the hosted demo a visitor who types another number
   * shows on the booking counter as a refused request rather than not at all.
   */
  @Test
  void aRefusalForTheNumberIsCountedAsAValidationOutcome() {
    double before = booked("validation");

    MvcTestResult result = book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0200"));

    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("DEMO_NUMBER_REQUIRED");
    assertThat(booked("validation") - before).isEqualTo(1.0);
  }

  /** How many booking requests the {@code appointments.booked} counter holds for an outcome. */
  private double booked(String outcome) {
    return meters.get("appointments.booked").tag("outcome", outcome).counter().count();
  }

  /** The rows a booking writes, by table, in a fixed order. */
  private Map<String, Integer> rowCounts() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String table : List.of("appointments", "notification_messages", "idempotency_keys")) {
      Integer rows = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
      counts.put(table, rows == null ? 0 : rows);
    }
    return counts;
  }

  /** The row counts a test expects, by table. */
  private static Map<String, Integer> rows(int appointments, int messages, int keys) {
    return Map.of(
        "appointments", appointments, "notification_messages", messages, "idempotency_keys", keys);
  }

  /**
   * The problem body without its correlation id and instance, which are random and could hold any
   * digits by chance.
   */
  private static String bodyWithoutCorrelationIds(MvcTestResult result) {
    String json = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    Map<String, Object> problem = new LinkedHashMap<>(JsonPath.read(json, "$"));
    assertThat(problem.remove("correlationId")).isNotNull();
    assertThat(problem.remove("instance")).isNotNull();
    return problem.toString();
  }

  /** Every run of four digits in a row in the number, such as its last four. */
  private static List<String> fourDigitRuns(String digits) {
    return IntStream.rangeClosed(0, digits.length() - 4)
        .mapToObj(start -> digits.substring(start, start + 4))
        .toList();
  }
}
