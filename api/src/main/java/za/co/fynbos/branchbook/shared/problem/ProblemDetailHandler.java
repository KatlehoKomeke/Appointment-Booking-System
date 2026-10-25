package za.co.fynbos.branchbook.shared.problem;

import jakarta.validation.ConstraintViolation;
import java.lang.annotation.Annotation;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Turns every failure inside Spring MVC into an RFC 9457 {@code application/problem+json} response.
 *
 * <ul>
 *   <li>A {@link ProblemException} becomes a problem with its code, type, title and detail.
 *   <li>Spring's own 400 errors (an invalid parameter, a missing header, an unreadable body) become
 *       {@link ProblemCode#VALIDATION_FAILED} with one {@code errors[]} entry per field.
 *   <li>A database that cannot be reached, a lock or statement that timed out, and a deadlock or
 *       serialization failure become {@link ProblemCode#TRY_AGAIN}. Every {@code TRY_AGAIN} carries
 *       {@code Retry-After}, as does any problem that names its own wait.
 *   <li>A JSON property the request type does not have becomes {@code VALIDATION_FAILED} with an
 *       {@code UNKNOWN_FIELD} entry that names the property but never quotes its value.
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

  /**
   * Seconds a client should wait after a lock or statement timeout, a deadlock or a serialization
   * failure: shorter than for an unreachable database, because the contention passes quickly.
   */
  static final String TRANSIENT_RETRY_AFTER_SECONDS = "2";

  /**
   * PostgreSQL's SQLSTATEs for failures that a retry can cure: lock wait timeout (55P03), statement
   * timeout (57014), deadlock (40P01) and serialization failure (40001).
   */
  static final Set<String> TRANSIENT_SQL_STATES = Set.of("55P03", "57014", "40P01", "40001");

  /**
   * The property names an {@code UNKNOWN_FIELD} entry may repeat: letters, digits and dots,
   * starting with a letter. Any other name is reported as {@code (body)}, so the response never
   * echoes arbitrary text a caller put in a property name.
   */
  private static final Pattern SAFE_PROPERTY_PATH = Pattern.compile("[A-Za-z][A-Za-z0-9.]{0,60}");

  /** How deep a cause chain is searched; deeper chains are treated as having no SQL cause. */
  private static final int MAX_CAUSE_DEPTH = 32;

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
    Optional<String> retryAfter = ex.retryAfter().map(ProblemDetailHandler::wholeSeconds);
    if (retryAfter.isEmpty() && ex.code() == ProblemCode.TRY_AGAIN) {
      retryAfter = Optional.of(RETRY_AFTER_SECONDS);
    }
    retryAfter.ifPresent(seconds -> response.header(HttpHeaders.RETRY_AFTER, seconds));
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
    Optional<String> transientState = transientSqlState(ex);
    if (transientState.isPresent()) {
      // The SQLSTATE says what happened; the message, which can quote values, is never logged.
      log.warn("Temporary database failure (SQLSTATE {})", transientState.get());
      return ResponseEntity.status(ProblemCode.TRY_AGAIN.status())
          .header(HttpHeaders.RETRY_AFTER, TRANSIENT_RETRY_AFTER_SECONDS)
          .body(Problems.of(ProblemCode.TRY_AGAIN, TRY_AGAIN_DETAIL, Problems.correlationId()));
    }
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
          String code =
              error.contains(ConstraintViolation.class)
                  ? explicitCode(error.unwrap(ConstraintViolation.class)).orElse(ruleCode(error))
                  : ruleCode(error);
          errors.add(new FieldProblem(error.getField(), code, error.getDefaultMessage()));
        }
      }
      case HandlerMethodValidationException invalid ->
          invalid
              .getParameterValidationResults()
              .forEach(
                  result -> {
                    String field = fieldName(result.getMethodParameter());
                    for (MessageSourceResolvable error : result.getResolvableErrors()) {
                      errors.add(
                          new FieldProblem(field, ruleCode(error), error.getDefaultMessage()));
                    }
                  });
      case HttpMessageNotReadableException unreadable ->
          unknownProperty(unreadable)
              .ifPresent(
                  path ->
                      errors.add(
                          new FieldProblem(
                              path, "UNKNOWN_FIELD", "is not a field of this request")));
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
   * Names a method parameter as the caller sees it: a header by its header name, such as {@code
   * X-Mobile-Last4}, anything else by its parameter name.
   */
  private static @Nullable String fieldName(MethodParameter parameter) {
    RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
    if (header != null) {
      String name = header.name().isEmpty() ? header.value() : header.name();
      if (!name.isEmpty()) {
        return name;
      }
    }
    return parameter.getParameterName();
  }

  /** The code a constraint names with {@link ErrorCode}, if it names one. */
  private static Optional<String> explicitCode(ConstraintViolation<?> violation) {
    Annotation constraint = violation.getConstraintDescriptor().getAnnotation();
    return Optional.ofNullable(constraint.annotationType().getAnnotation(ErrorCode.class))
        .map(ErrorCode::value);
  }

  /**
   * The path of a JSON property the request type does not have, such as {@code customer.email}, or
   * {@code (body)} when the name is not a plain identifier.
   */
  private static Optional<String> unknownProperty(HttpMessageNotReadableException unreadable) {
    for (Throwable cause = unreadable.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof UnrecognizedPropertyException unknown) {
        String path =
            unknown.getPath().stream()
                .map(JacksonException.Reference::getPropertyName)
                .filter(name -> name != null)
                .collect(Collectors.joining("."));
        return Optional.of(SAFE_PROPERTY_PATH.matcher(path).matches() ? path : "(body)");
      }
    }
    return Optional.empty();
  }

  /** The SQLSTATE of a failure that a retry can cure, if one is in the cause chain. */
  static Optional<String> transientSqlState(Throwable failure) {
    Throwable cause = failure;
    for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (cause instanceof SQLException sql && TRANSIENT_SQL_STATES.contains(sql.getSQLState())) {
        return Optional.of(sql.getSQLState());
      }
      cause = cause.getCause();
    }
    return Optional.empty();
  }

  /** A wait in whole seconds, rounded up and never below one, as {@code Retry-After} expects. */
  private static String wholeSeconds(Duration wait) {
    long seconds = Math.ceilDiv(Math.max(wait.toMillis(), 1), 1000L);
    return Long.toString(seconds);
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
}
