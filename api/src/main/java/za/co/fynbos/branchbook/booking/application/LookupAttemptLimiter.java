package za.co.fynbos.branchbook.booking.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;
import za.co.fynbos.branchbook.shared.config.BookingProperties;

/**
 * Stops anyone trying the 10,000 possible last four digits of a reference (BR-13).
 *
 * <p>Each reference gets a window that opens at its first wrong lookup and lasts {@code
 * lookupWindow}; once it holds {@code lookupMaxFailures} failures, every lookup of that reference,
 * right or wrong, is refused with {@code 429} until the window closes. The window never slides, so
 * waiting it out always works. It is measured on the instants the caller passes, which come from
 * the application's clock, so tests control it.
 *
 * <p>The counts live in memory, in a cache bounded to {@value #MAX_REFERENCES} references, each
 * dropped once its window has passed. They are per instance and lost on restart: a deliberate
 * trade-off for one instance, recorded in docs/architecture.md.
 *
 * <p>Blocking a reference logs one warning, the signal that someone may be guessing digits. It
 * names no reference, which is half of what opens an appointment; the request's correlation id ties
 * it to the request.
 */
@Component
public class LookupAttemptLimiter {

  private static final Logger log = LoggerFactory.getLogger(LookupAttemptLimiter.class);

  /** The most references counted at once; beyond it, the least recently used are dropped. */
  static final int MAX_REFERENCES = 100_000;

  private final Cache<String, Window> windows;
  private final int maxFailures;
  private final Duration windowLength;

  LookupAttemptLimiter(BookingProperties booking) {
    this.maxFailures = booking.lookupMaxFailures();
    this.windowLength = booking.lookupWindow();
    this.windows =
        Caffeine.newBuilder()
            .maximumSize(MAX_REFERENCES)
            .expireAfterWrite(booking.lookupWindow())
            .build();
  }

  /**
   * Refuses a lookup of a reference whose window is full.
   *
   * @param reference the reference being looked up
   * @param now the current instant
   * @throws LookupRateLimitedException if the reference has had too many wrong lookups in its
   *     current window
   */
  public void checkNotBlocked(AppointmentReference reference, Instant now) {
    Window window = windows.getIfPresent(reference.value());
    if (window != null && window.isOpenAt(now) && window.failures() >= maxFailures) {
      throw new LookupRateLimitedException(Duration.between(now, window.closesAt()));
    }
  }

  /**
   * Counts a wrong lookup, opening a new window if there is none, and warns when the count blocks
   * the reference.
   *
   * @param reference the reference that was looked up
   * @param now the current instant
   */
  public void recordFailure(AppointmentReference reference, Instant now) {
    Window counted =
        windows
            .asMap()
            .compute(
                reference.value(),
                (key, window) ->
                    window == null || !window.isOpenAt(now)
                        ? new Window(now, now.plus(windowLength), 1)
                        : new Window(window.opensAt(), window.closesAt(), window.failures() + 1));
    if (counted != null && counted.failures() == maxFailures) {
      log.warn("lookup.blocked failures={} until={}", counted.failures(), counted.closesAt());
    }
  }

  /** Forgets every count. For tests, which share one application. */
  public void reset() {
    windows.invalidateAll();
  }

  /**
   * One reference's failures since its window opened.
   *
   * @param opensAt the first wrong lookup
   * @param closesAt when the window closes
   * @param failures the wrong lookups in it
   */
  private record Window(Instant opensAt, Instant closesAt, int failures) {

    boolean isOpenAt(Instant now) {
      return now.isBefore(closesAt);
    }
  }
}
