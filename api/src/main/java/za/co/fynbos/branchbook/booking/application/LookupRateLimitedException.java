package za.co.fynbos.branchbook.booking.application;

import java.io.Serial;
import java.time.Duration;
import java.util.Map;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * Too many wrong lookups of one reference: {@code 429 LOOKUP_RATE_LIMITED}, with {@code
 * Retry-After} until the reference's window closes (BR-13).
 */
public class LookupRateLimitedException extends ProblemException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the problem.
   *
   * @param retryAfter how long until the reference can be looked up again
   */
  public LookupRateLimitedException(Duration retryAfter) {
    super(
        ProblemCode.LOOKUP_RATE_LIMITED,
        "Too many attempts. Please try again later.",
        Map.of(),
        retryAfter);
  }
}
