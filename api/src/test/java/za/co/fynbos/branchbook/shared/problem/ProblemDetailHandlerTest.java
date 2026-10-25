package za.co.fynbos.branchbook.shared.problem;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.constraints.Pattern;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * How each kind of failure becomes a problem response, without starting Spring or a database. The
 * failures and paths carry a mobile number on purpose: it must never reach the response.
 */
@ExtendWith(OutputCaptureExtension.class)
class ProblemDetailHandlerTest {

  /** A synthetic number from the demo range in SECURITY.md. */
  private static final String MOBILE = "+27825550101";

  private final MockMvcTester mvc =
      MockMvcTester.create(
          MockMvcBuilders.standaloneSetup(new FailingController())
              .setControllerAdvice(new ProblemDetailHandler())
              .build());

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/slot-full",
        "/database-down",
        "/bug",
        "/needs-header",
        "/broken-mapping/1",
        "/no-such-path/" + MOBILE
      })
  void everyProblemIsProblemJsonIdentifiedByItsCorrelationIdNeverByItsPath(String path)
      throws Exception {
    MvcTestResult result = mvc.get().uri(path).exchange();
    String body = result.getResponse().getContentAsString();
    String correlationId = body.replaceAll(".*\"correlationId\":\"([^\"]+)\".*", "$1");

    assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.instance")
        .isEqualTo("urn:branchbook:request:" + correlationId);
    assertThat(body).doesNotContain("825550101");
  }

  @Test
  void problemExceptionBecomesAProblemWithItsCodeAndExtensions() {
    MvcTestResult result = mvc.get().uri("/slot-full").exchange();

    assertThat(result).hasStatus(HttpStatus.CONFLICT);
    assertThat(result)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {
              "type": "urn:branchbook:problem:SLOT_FULL",
              "title": "That time was just taken",
              "status": 409,
              "detail": "All seats for this time were booked.",
              "code": "SLOT_FULL",
              "alternatives": []
            }""");
  }

  @Test
  void unreachableDatabaseBecomes503TryAgainWithRetryAfter() {
    MvcTestResult result = mvc.get().uri("/database-down").exchange();

    assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(result).headers().hasValue("Retry-After", "5");
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("TRY_AGAIN");
  }

  /** BR-19: the log shows the exception's type and frames, never its message. */
  @Test
  void unexpectedFailureIsLoggedWithoutItsMessageAndBecomes500(CapturedOutput output)
      throws Exception {
    MvcTestResult result = mvc.get().uri("/bug").exchange();

    assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
    assertThat(result.getResponse().getContentAsString()).doesNotContain("IllegalStateException");
    assertThat(output)
        .contains("Unexpected failure")
        .contains("java.lang.IllegalStateException (message withheld)")
        .contains("FailingController.bug")
        .doesNotContain("825550101");
  }

  /** A failure that Spring itself maps to 500, such as a handler that misnames a path variable. */
  @Test
  void springsOwnServerErrorBecomesInternalErrorAndIsLogged(CapturedOutput output) {
    MvcTestResult result = mvc.get().uri("/broken-mapping/1").exchange();

    assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
    assertThat(output).contains("Unexpected failure").contains("MissingPathVariableException");
  }

  @Test
  void everyTryAgainCarriesRetryAfter() {
    MvcTestResult result = mvc.get().uri("/try-again").exchange();

    assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(result).headers().hasValue("Retry-After", "5");
  }

  @Test
  void missingHeaderBecomes400NamingTheHeader() {
    MvcTestResult result = mvc.get().uri("/needs-header").exchange();

    assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors")
        .isEqualTo(
            List.of(
                Map.of("field", "X-Mobile-Last4", "code", "REQUIRED", "message", "is required")));
  }

  /** A problem that names its own wait, such as a lookup limit, sends it in whole seconds. */
  @Test
  void problemExceptionCarriesItsOwnRetryAfter() {
    MvcTestResult result = mvc.get().uri("/rate-limited").exchange();

    assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(result).headers().hasValue("Retry-After", "2");
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("LOOKUP_RATE_LIMITED");
  }

  /**
   * A lock or statement timeout, a deadlock or a serialization failure, wherever it happens, is a
   * 503 the caller can retry soon. The log names the SQLSTATE and never the message.
   */
  @ParameterizedTest
  @ValueSource(strings = {"55P03", "57014", "40P01", "40001"})
  void transientDatabaseFailureBecomes503WithRetryAfterTwo(String sqlState, CapturedOutput output)
      throws Exception {
    MvcTestResult result = mvc.get().uri("/transient/" + sqlState).exchange();

    assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(result).headers().hasValue("Retry-After", "2");
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("TRY_AGAIN");
    assertThat(result.getResponse().getContentAsString()).doesNotContain("825550101");
    assertThat(output)
        .contains("Temporary database failure (SQLSTATE " + sqlState + ")")
        .doesNotContain("825550101");
  }

  /** Another SQLSTATE, such as a broken constraint, is still a bug: 500, logged without data. */
  @Test
  void otherDatabaseFailureStaysAnInternalError() {
    MvcTestResult result = mvc.get().uri("/transient/23514").exchange();

    assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
  }

  /** A malformed header is named by its header name, the name the caller sent. */
  @Test
  void malformedHeaderIsNamedByTheHeader() {
    MvcTestResult result = mvc.get().uri("/last4").header("X-Mobile-Last4", "12a4").exchange();

    assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors")
        .isEqualTo(
            List.of(
                Map.of(
                    "field", "X-Mobile-Last4", "code", "PATTERN", "message", "must be 4 digits")));
  }

  @Test
  void unknownPathKeepsSpringsStatusButCarriesNoCodeAndNoDetail() {
    MvcTestResult result = mvc.get().uri("/no-such-path/" + MOBILE).exchange();

    assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
    assertThat(result).bodyJson().extractingPath("$.correlationId").asString().isNotBlank();
    assertThat(result).bodyJson().doesNotHavePath("$.code").doesNotHavePath("$.detail");
  }

  @RestController
  static class FailingController {

    @GetMapping("/slot-full")
    void slotFull() {
      throw new ProblemException(
          ProblemCode.SLOT_FULL,
          "All seats for this time were booked.",
          Map.of("alternatives", List.of()));
    }

    @GetMapping("/try-again")
    void tryAgain() {
      throw new ProblemException(ProblemCode.TRY_AGAIN, "Please try again.");
    }

    @GetMapping("/rate-limited")
    void rateLimited() {
      throw new ProblemException(
          ProblemCode.LOOKUP_RATE_LIMITED,
          "Too many attempts. Try again later.",
          Map.of(),
          Duration.ofMillis(1_500));
    }

    @GetMapping("/transient/{sqlState}")
    void transientFailure(@PathVariable("sqlState") String sqlState) {
      throw new IllegalStateException(
          "Booking failed", new SQLException("Timed out waiting for " + MOBILE, sqlState));
    }

    @GetMapping("/last4")
    void last4(
        @RequestHeader("X-Mobile-Last4")
            @Pattern(regexp = "^[0-9]{4}$", message = "must be 4 digits")
            String last4) {
      throw new AssertionError("Not reached: the header is malformed");
    }

    @GetMapping("/database-down")
    void databaseDown() {
      throw new CannotGetJdbcConnectionException("No connection for " + MOBILE);
    }

    @GetMapping("/bug")
    void bug() {
      throw new IllegalStateException("Could not handle " + MOBILE);
    }

    @GetMapping("/needs-header")
    void needsHeader(@RequestHeader("X-Mobile-Last4") String last4) {
      throw new AssertionError("Not reached: the header is missing");
    }

    @GetMapping("/broken-mapping/{id}")
    void brokenMapping(@PathVariable("reference") String reference) {
      throw new AssertionError("Not reached: the path has no variable named reference");
    }
  }
}
