package za.co.fynbos.branchbook.shared.problem;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The problems written for requests that failed before Spring MVC, as the servlet container
 * forwards them to {@code /error}. scripts/smoke.sh checks a real rejected request end to end.
 */
class ErrorDispatchControllerTest {

  private static final String MOBILE_DIGITS = "825550101";

  private final MockMvcTester mvc = MockMvcTester.of(new ErrorDispatchController());

  private final Logger logger = (Logger) LoggerFactory.getLogger(ErrorDispatchController.class);

  private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

  @BeforeEach
  void captureTheLog() {
    logged.start();
    logger.addAppender(logged);
  }

  @AfterEach
  void releaseTheLog() {
    logger.detachAppender(logged);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 404, 500, 503})
  void everyForwardedErrorIsAProblemIdentifiedByItsCorrelationId(int status) throws Exception {
    MvcTestResult result = forwardedError(status, null);
    String correlationId = result.getResponse().getHeader("X-Correlation-Id");

    assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(correlationId).isNotBlank();
    assertThat(result).bodyJson().extractingPath("$.correlationId").isEqualTo(correlationId);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.instance")
        .isEqualTo("urn:branchbook:request:" + correlationId);
    assertThat(result).bodyJson().doesNotHavePath("$.path").doesNotHavePath("$.timestamp");
    assertThat(result.getResponse().getContentAsString()).doesNotContain(MOBILE_DIGITS);
  }

  @Test
  void requestRejectedBeforeSpringMvcIsAValidationProblem() {
    assertThat(forwardedError(400, null))
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void failureOutsideSpringMvcIsLoggedWithItsCorrelationIdButWithoutItsMessage() {
    MvcTestResult result =
        forwardedError(500, new ServletException("filter failed for +27" + MOBILE_DIGITS));
    String correlationId = result.getResponse().getHeader("X-Correlation-Id");

    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
    assertThat(logged.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", correlationId);
              assertThat(event.getThrowableProxy().getMessage())
                  .isEqualTo("jakarta.servlet.ServletException (message withheld)");
            });
  }

  @Test
  void unavailableIsTryAgainWithRetryAfterAndOnlyAWarning() {
    MvcTestResult result = forwardedError(503, null);

    assertThat(result).headers().hasValue("Retry-After", "5");
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("TRY_AGAIN");
    assertThat(logged.list)
        .singleElement()
        .extracting(e -> e.getLevel().toString())
        .isEqualTo("WARN");
  }

  @Test
  void otherStatusesKeepTheirTitleWithoutACode() {
    MvcTestResult result = forwardedError(404, null);

    assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Not Found");
    assertThat(result).bodyJson().doesNotHavePath("$.code");
  }

  /** A caller who requests /error directly gets a plain 404 and cannot write to the error log. */
  @Test
  void directRequestIsANotFoundThatIsNotLogged() {
    MvcTestResult result = mvc.get().uri("/error").exchange();

    assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
    assertThat(result).bodyJson().doesNotHavePath("$.code");
    assertThat(logged.list).isEmpty();
  }

  /** What the container does: forward the failed request to /error with these attributes. */
  private MvcTestResult forwardedError(int status, Throwable failure) {
    return mvc.get()
        .uri("/error")
        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)
        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/branches;+27" + MOBILE_DIGITS)
        .with(
            request -> {
              if (failure != null) {
                request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, failure);
              }
              return request;
            })
        .exchange();
  }
}
