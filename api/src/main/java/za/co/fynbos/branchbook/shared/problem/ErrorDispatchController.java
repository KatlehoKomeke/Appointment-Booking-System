package za.co.fynbos.branchbook.shared.problem;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.fynbos.branchbook.shared.correlation.CorrelationIds;

/**
 * Writes the problem for a request that failed before Spring MVC could handle it, so those
 * responses have the same shape as every other error (docs/errors.md).
 *
 * <p>Examples are a URL that Spring Security's firewall rejects, such as one containing {@code ;},
 * and an exception thrown by a servlet filter. The container forwards such a request to {@code
 * /error}; this controller replaces Spring Boot's default one, whose JSON is not a problem, has no
 * correlation id and repeats the request path.
 *
 * <ul>
 *   <li>400 becomes {@link ProblemCode#VALIDATION_FAILED}.
 *   <li>503 becomes {@link ProblemCode#TRY_AGAIN} with {@code Retry-After}, logged as a warning;
 *       any other 5xx becomes {@link ProblemCode#INTERNAL_ERROR}, logged as an error without the
 *       exception's messages (see {@link LoggableFailure}).
 *   <li>Any other status keeps its standard title and carries no code.
 *   <li>A request for {@code /error} that the container did not forward, which is how a caller
 *       would reach it directly, is a 404 without a code and is not logged.
 * </ul>
 *
 * <p>The correlation id is the one {@code CorrelationIdFilter} already put on the response. The
 * filter does not run again for the forward, so the id is put back into the logging context here,
 * and a new one is set on the response if none is there.
 */
@RestController
class ErrorDispatchController implements ErrorController {

  private static final Logger log = LoggerFactory.getLogger(ErrorDispatchController.class);

  // Every HTTP method: the container forwards a failed request here with the method it arrived
  // with.
  @RequestMapping("${server.error.path:/error}")
  ResponseEntity<ProblemDetail> error(HttpServletRequest request, HttpServletResponse response) {
    String correlationId = response.getHeader(CorrelationIds.HEADER);
    if (correlationId == null) {
      correlationId = Problems.correlationId();
      response.setHeader(CorrelationIds.HEADER, correlationId);
    }
    try (var _ = MDC.putCloseable(CorrelationIds.MDC_KEY, correlationId)) {
      ProblemDetail problem =
          request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer status
              ? problemFor(status, correlationId, request)
              : Problems.withoutCode(HttpStatus.NOT_FOUND, correlationId);
      ResponseEntity.BodyBuilder builder = ResponseEntity.status(problem.getStatus());
      if (problem.getStatus() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
        builder.header(HttpHeaders.RETRY_AFTER, ProblemDetailHandler.RETRY_AFTER_SECONDS);
      }
      return builder.body(problem);
    }
  }

  private static ProblemDetail problemFor(
      int status, String correlationId, HttpServletRequest request) {
    if (status == HttpStatus.BAD_REQUEST.value()) {
      return Problems.of(ProblemCode.VALIDATION_FAILED, "The request is malformed.", correlationId);
    }
    if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
      log.warn("Temporary failure outside Spring MVC");
      return Problems.of(
          ProblemCode.TRY_AGAIN, ProblemDetailHandler.TRY_AGAIN_DETAIL, correlationId);
    }
    if (status >= 500) {
      if (request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable failure) {
        log.error("Unexpected failure outside Spring MVC", LoggableFailure.of(failure));
      } else {
        log.error("Unexpected failure outside Spring MVC (status {})", status);
      }
      return Problems.of(
          ProblemCode.INTERNAL_ERROR, ProblemDetailHandler.INTERNAL_ERROR_DETAIL, correlationId);
    }
    return Problems.withoutCode(HttpStatusCode.valueOf(status), correlationId);
  }
}
