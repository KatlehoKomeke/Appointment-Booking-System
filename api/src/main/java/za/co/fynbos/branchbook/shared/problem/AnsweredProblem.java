package za.co.fynbos.branchbook.shared.problem;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Remembers, on the request, which coded problem it was answered with, so code that runs once the
 * response is written can tell how the request ended without reading the body.
 *
 * <p>{@link Problems} notes the code as it builds each problem that has one, so every way a request
 * can fail is covered by the one decision {@link ProblemDetailHandler} already makes: a {@link
 * ProblemException}'s own code, {@code VALIDATION_FAILED} for Spring's 400s, {@code TRY_AGAIN} for
 * an unreachable or busy database, and {@code INTERNAL_ERROR} for anything unexpected. The booking
 * meter reads it to count each booking request by its outcome (FR-30), so it never has to repeat
 * that classification.
 */
public final class AnsweredProblem {

  /** The request attribute that holds the code; named after this class, so nothing else uses it. */
  private static final String ATTRIBUTE = AnsweredProblem.class.getName() + ".code";

  private AnsweredProblem() {}

  /**
   * Notes the code on the request being handled on this thread; does nothing outside a request.
   *
   * @param code the code of the problem about to be written
   */
  static void note(ProblemCode code) {
    RequestAttributes request = RequestContextHolder.getRequestAttributes();
    if (request != null) {
      request.setAttribute(ATTRIBUTE, code, RequestAttributes.SCOPE_REQUEST);
    }
  }

  /**
   * Returns the code of the problem a request was answered with.
   *
   * @param request the request, once its response has been written
   * @return the code, or empty when the request was not answered with a coded problem: it
   *     succeeded, or failed with a status the API never returns on purpose, such as 405
   */
  public static Optional<ProblemCode> of(HttpServletRequest request) {
    return request.getAttribute(ATTRIBUTE) instanceof ProblemCode code
        ? Optional.of(code)
        : Optional.empty();
  }
}
