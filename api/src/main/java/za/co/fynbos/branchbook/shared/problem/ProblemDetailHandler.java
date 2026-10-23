package za.co.fynbos.branchbook.shared.problem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure inside Spring MVC into an RFC 9457 {@code application/problem+json} response.
 *
 * <ul>
 *   <li>A {@link ProblemException} becomes a problem with its code, type, title and detail.
 *   <li>Spring's own 400 errors (an invalid parameter, a missing header, an unreadable body) become
 *       {@link ProblemCode#VALIDATION_FAILED} with one {@code errors[]} entry per field.
 *   <li>A database that cannot be reached becomes {@link ProblemCode#TRY_AGAIN}. Every {@code
 *       TRY_AGAIN} carries {@code Retry-After}.
 *   <li>Anything unexpected, including Spring's own 5xx errors, becomes {@link
 *       ProblemCode#INTERNAL_ERROR} (or {@code TRY_AGAIN} for a 503). The exception is logged with
 *       its types and stack frames but not its messages, which can quote personal data (see {@link
 *       LoggableFailure}); the response carries only the correlation id.
 *   <li>Spring's other errors, such as 404 for an unknown path or 405 for a wrong method, keep
 *       their status and title and carry no code.
 * </ul>
 *
 * <p>Every problem carries the request's {@code correlationId} (see {@link Problems}). No detail
 * ever echoes the request, because it could contain personal data: Spring's details, which can
 * quote the request, are replaced with fixed text or dropped. Failures outside Spring MVC are
 * handled by {@link ErrorDispatchController}.
 */
@RestControllerAdvice
class ProblemDetailHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemDetailHandler.class);

  /** Seconds a client should wait before retrying a temporary failure. */
  static final String RETRY_AFTER_SECONDS = "5";

  /** The detail of every internal error: nothing about the cause reaches the caller. */
  static final String INTERNAL_ERROR_DETAIL =
      "Something went wrong on our side. Quote the correlation id if you contact us.";

  /** The detail of every temporary failure. */
  static final String TRY_AGAIN_DETAIL = "We could not complete your request. Please try again.";

  @ExceptionHandler(ProblemException.class)
  ResponseEntity<ProblemDetail> handleProblem(ProblemException ex) {
    ProblemDetail problem = Problems.of(ex.code(), ex.getMessage(), Problems.correlationId());
    ex.extensions().forEach(problem::setProperty);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.code().status());
    if (ex.code() == ProblemCode.TRY_AGAIN) {
      response.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
    }
    return response.body(problem);
  }

  @ExceptionHandler({
    CannotGetJdbcConnectionException.class,
    CannotCreateTransactionException.class
  })
  ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception ex) {
    // The exception type is enough to diagnose this, and its message never reaches a log line.
    log.warn("Database unavailable ({})", ex.getClass().getSimpleName());
    return ResponseEntity.status(ProblemCode.TRY_AGAIN.status())
        .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
        .body(Problems.of(ProblemCode.TRY_AGAIN, TRY_AGAIN_DETAIL, Problems.correlationId()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
    log.error("Unexpected failure", LoggableFailure.of(ex));
    return ResponseEntity.status(ProblemCode.INTERNAL_ERROR.status())
        .body(
            Problems.of(
                ProblemCode.INTERNAL_ERROR, INTERNAL_ERROR_DETAIL, Problems.correlationId()));
  }

  /**
   * Adapts the problems that Spring creates for its own exceptions, before they are written.
   *
   * @param ex the exception Spring handled
   * @param body Spring's problem body, or {@code null}
   * @param headers the response headers Spring chose
   * @param statusCode the response status Spring chose
   * @param request the current request
   * @return the response with the adapted problem body
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      @Nullable Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    String correlationId = Problems.correlationId();
    HttpHeaders responseHeaders = new HttpHeaders();
    responseHeaders.putAll(headers);
    ProblemDetail problem;
    if (statusCode.value() == HttpStatus.BAD_REQUEST.value()) {
      problem =
          Problems.of(
              ProblemCode.VALIDATION_FAILED,
              "One or more fields are missing or invalid.",
              correlationId);
      List<FieldProblem> errors = fieldProblems(ex);
      if (!errors.isEmpty()) {
        problem.setProperty("errors", errors);
      }
    } else if (statusCode.value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
      log.warn("Temporary failure ({})", ex.getClass().getSimpleName());
      responseHeaders.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
      problem = Problems.of(ProblemCode.TRY_AGAIN, TRY_AGAIN_DETAIL, correlationId);
    } else if (statusCode.is5xxServerError()) {
      log.error("Unexpected failure", LoggableFailure.of(ex));
      problem = Problems.of(ProblemCode.INTERNAL_ERROR, INTERNAL_ERROR_DETAIL, correlationId);
    } else {
      problem = Problems.withoutCode(statusCode, correlationId);
    }
    return super.handleExceptionInternal(
        ex, problem, responseHeaders, HttpStatusCode.valueOf(problem.getStatus()), request);
  }

  /** Lists what is wrong with each field, naming fields and rules but never quoting values. */
  private static List<FieldProblem> fieldProblems(Exception ex) {
    List<FieldProblem> errors = new ArrayList<>();
    switch (ex) {
      case MethodArgumentNotValidException invalid -> {
        for (FieldError error : invalid.getBindingResult().getFieldErrors()) {
          errors.add(
              new FieldProblem(error.getField(), ruleCode(error), error.getDefaultMessage()));
        }
      }
      case HandlerMethodValidationException invalid ->
          invalid
              .getParameterValidationResults()
              .forEach(
                  result -> {
                    String field = result.getMethodParameter().getParameterName();
                    for (MessageSourceResolvable error : result.getResolvableErrors()) {
                      errors.add(
                          new FieldProblem(field, ruleCode(error), error.getDefaultMessage()));
                    }
                  });
      case MissingRequestHeaderException missing ->
          errors.add(new FieldProblem(missing.getHeaderName(), "REQUIRED", "is required"));
      case MissingServletRequestParameterException missing ->
          errors.add(new FieldProblem(missing.getParameterName(), "REQUIRED", "is required"));
      case TypeMismatchException mismatch ->
          errors.add(
              new FieldProblem(
                  mismatch.getPropertyName(), "TYPE_MISMATCH", "has the wrong format"));
      default -> {
        // An unreadable body or another binding problem: the detail alone describes it.
      }
    }
    return errors;
  }

  /**
   * Turns a validation rule's name into a code: {@code NotBlank} becomes {@code NOT_BLANK}. Spring
   * lists a rule's codes from the most to the least specific; the last is the rule's name.
   */
  private static String ruleCode(MessageSourceResolvable error) {
    String[] codes = error.getCodes();
    if (codes == null || codes.length == 0) {
      return "INVALID";
    }
    String rule = codes[codes.length - 1];
    return rule.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
  }

  /**
   * One entry of a problem's {@code errors[]}.
   *
   * @param field the request field, parameter or header with the problem
   * @param code the rule it broke, for example {@code PATTERN}
   * @param message a short explanation that never quotes the value
   */
  record FieldProblem(@Nullable String field, String code, @Nullable String message) {}
}
