package za.co.fynbos.branchbook.shared.correlation;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * Correlation ids: one id per request, echoed in the {@code X-Correlation-Id} response header,
 * written on every log line and returned in every problem body. A customer can quote it to support,
 * and support can find the request in the logs without either side sharing personal data.
 */
public final class CorrelationIds {

  /** The request and response header that carries the id. */
  public static final String HEADER = "X-Correlation-Id";

  /** The logging context key that holds the id while a request is handled. */
  public static final String MDC_KEY = "correlationId";

  /**
   * The ids accepted from a caller: up to 64 letters, digits and hyphens. Anything else is replaced
   * with a new id, so a caller cannot inject text into the logs.
   */
  private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9-]{1,64}");

  private CorrelationIds() {}

  /**
   * Returns the caller's id if it is acceptable, otherwise a new one.
   *
   * @param requested the {@code X-Correlation-Id} header the caller sent, or {@code null}
   * @return the id to use for the request
   */
  public static String acceptOrCreate(String requested) {
    if (requested != null && ACCEPTED.matcher(requested).matches()) {
      return requested;
    }
    return UUID.randomUUID().toString();
  }

  /**
   * Returns the id of the request being handled on this thread.
   *
   * @return the id, or empty outside a request
   */
  public static Optional<String> current() {
    return Optional.ofNullable(MDC.get(MDC_KEY));
  }

  /**
   * Returns an id for work that starts outside a request, such as the confirmation dispatcher.
   *
   * @return {@code sys-} followed by a random UUID
   */
  public static String systemId() {
    return "sys-" + UUID.randomUUID();
  }
}
