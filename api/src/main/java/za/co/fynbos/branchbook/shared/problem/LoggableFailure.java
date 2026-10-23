package za.co.fynbos.branchbook.shared.problem;

import java.io.Serial;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * A copy of an exception that is safe to log: the same exception types and stack frames, with every
 * message withheld.
 *
 * <p>An exception's message can quote what failed: a request value, or the row a database
 * constraint rejected, such as a customer's name or mobile number. Logs must never hold personal
 * data (BR-19), so unexpected failures are logged through this class. The types and frames are
 * enough to find the cause, and the correlation id on the log line ties it to the request.
 */
final class LoggableFailure extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private LoggableFailure(Throwable original, Throwable cause) {
    super(original.getClass().getName() + " (message withheld)", cause, false, true);
    setStackTrace(original.getStackTrace());
  }

  /**
   * Copies an exception and its chain of causes without their messages.
   *
   * @param failure the exception to log
   * @return the copy to pass to the logger
   */
  static Throwable of(Throwable failure) {
    return copy(failure, Collections.newSetFromMap(new IdentityHashMap<>()));
  }

  private static Throwable copy(Throwable failure, Set<Throwable> seen) {
    if (!seen.add(failure)) {
      return null;
    }
    Throwable cause = failure.getCause() == null ? null : copy(failure.getCause(), seen);
    return new LoggableFailure(failure, cause);
  }
}
