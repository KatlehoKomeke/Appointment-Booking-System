package za.co.fynbos.branchbook.booking.application;

import java.io.Serial;
import java.time.Duration;
import java.util.Map;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * Too many lookups of one reference: {@code 429 LOOKUP_RATE_LIMITED} (BR-13). {@code Retry-After}
 * runs until the reference's window closes when it has had five wrong lookups, or is one second
 * when the wrong lookups and the lookups still being checked together reach five.
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
