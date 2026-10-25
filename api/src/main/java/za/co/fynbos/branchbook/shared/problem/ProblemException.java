package za.co.fynbos.branchbook.shared.problem;

import java.io.Serial;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An expected failure that becomes an RFC 9457 problem response with a stable {@link ProblemCode}.
 *
 * <p>Modules extend it for their own failures, for example {@code BranchNotFoundException}, and
 * {@code ProblemDetailHandler} turns any of them into a response. It has no Spring dependency, so
 * domain code may throw it.
 *
 * <p>The detail is shown to the caller as it is, so it must be plain language and must never
 * contain personal data or echo the request (docs/errors.md).
 */
public class ProblemException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** The code, which decides the status, type and title of the response. */
  private final ProblemCode code;

  /** Extra members of the problem body, such as {@code alternatives}. Never personal data. */
  private final transient Map<String, Object> extensions;

  /** How long the caller should wait before retrying, sent as {@code Retry-After}; or null. */
  private final @Nullable Duration retryAfter;

  /**
   * Creates a problem without extra members.
   *
   * @param code the error code
   * @param detail the plain-language explanation shown to the caller
   */
  public ProblemException(ProblemCode code, String detail) {
    this(code, detail, Map.of());
  }

  /**
   * Creates a problem with extra members in its body.
   *
   * @param code the error code
   * @param detail the plain-language explanation shown to the caller
   * @param extensions extra members of the problem body, by name
   */
  public ProblemException(ProblemCode code, String detail, Map<String, Object> extensions) {
    this(code, detail, extensions, null);
  }

  /**
   * Creates a problem that tells the caller when to retry.
   *
   * @param code the error code
   * @param detail the plain-language explanation shown to the caller
   * @param extensions extra members of the problem body, by name
   * @param retryAfter how long the caller should wait before retrying, sent as {@code Retry-After}
   *     in whole seconds; {@code null} for none, in which case a {@code TRY_AGAIN} still gets the
   *     default
   */
  public ProblemException(
      ProblemCode code,
      String detail,
      Map<String, Object> extensions,
      @Nullable Duration retryAfter) {
    super(detail);
    this.code = Objects.requireNonNull(code, "code");
    this.extensions = Map.copyOf(extensions);
    this.retryAfter = retryAfter;
  }

  /**
   * Returns the error code.
   *
   * @return the code that decides the response's status, type and title
   */
  public ProblemCode code() {
    return code;
  }

  /**
   * Returns the extra members of the problem body.
   *
   * @return an unmodifiable map, empty when there are none
   */
  public Map<String, Object> extensions() {
    return extensions;
  }

  /**
   * Returns how long the caller should wait before retrying.
   *
   * @return the wait, or empty when the problem names none
   */
  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
