package za.co.fynbos.branchbook.shared.problem;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import za.co.fynbos.branchbook.shared.correlation.CorrelationIds;

/**
 * Builds every problem body the application writes, so all of them have the same shape.
 *
 * <p>Each problem carries the request's {@code correlationId}, and its {@code instance} names this
 * occurrence as {@code urn:branchbook:request:<correlationId>}. It is never the request path,
 * because a path is the caller's input and can hold personal data, such as a mobile number typed
 * into a URL, or a booking reference.
 */
final class Problems {

  private static final String INSTANCE_PREFIX = "urn:branchbook:request:";

  private Problems() {}

  /** A problem with one of the API's codes. */
  static ProblemDetail of(ProblemCode code, String detail, String correlationId) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(code.status()), detail);
    problem.setType(URI.create(code.type()));
    problem.setTitle(code.title());
    problem.setProperty("code", code.name());
    identify(problem, correlationId);
    return problem;
  }

  /**
   * A problem without a code, for a status the API never returns on purpose, such as 404 for a path
   * that does not exist. It keeps the status's standard title and has no detail.
   */
  static ProblemDetail withoutCode(HttpStatusCode status, String correlationId) {
    ProblemDetail problem = ProblemDetail.forStatus(status);
    identify(problem, correlationId);
    return problem;
  }

  /** The correlation id of the request being handled, or a new one outside a request. */
  static String correlationId() {
    return CorrelationIds.current().orElseGet(() -> CorrelationIds.acceptOrCreate(null));
  }

  private static void identify(ProblemDetail problem, String correlationId) {
    problem.setInstance(URI.create(INSTANCE_PREFIX + correlationId));
    problem.setProperty("correlationId", correlationId);
  }
}
