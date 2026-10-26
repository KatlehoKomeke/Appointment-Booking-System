package za.co.fynbos.branchbook.booking.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;
import za.co.fynbos.branchbook.shared.config.BookingProperties;

/**
 * Stops anyone trying the 10,000 possible last four digits of a reference (BR-13).
 *
 * <p>Each reference gets a window that opens when a lookup of it is admitted and lasts {@code
 * lookupWindow}. A window in which every lookup had the right digits is forgotten at once, so in
 * effect the window opens with the first wrong lookup. Once the window holds {@code
 * lookupMaxFailures} wrong lookups, every lookup of that reference, right or wrong, is refused with
 * {@code 429} until the window closes. The window never slides, so waiting it out always works. It
 * is measured on the instants the caller passes, which come from the application's clock, so tests
 * control it.
 *
 * <p>A lookup is admitted and counted in one atomic step, before the database is read: {@link
 * #begin} counts it as in flight, and {@link #end} turns it into a failure if the digits were wrong
 * or releases it if they were right. A lookup is admitted only while the failures and the lookups
 * in flight together are fewer than the limit, so requests that arrive together cannot all pass a
 * check made before any of them was counted. Lookups with the right digits are released when they
 * end, so a customer who keeps their appointment page open is never blocked for the hour. A lookup
 * refused only because others are still in flight is told to retry after a second.
 *
 * <p>The counts live in memory, in a cache bounded to {@value #MAX_REFERENCES} references, each
 * dropped once its window has passed. They are per instance and lost on restart: a deliberate
 * trade-off for one instance, recorded in docs/architecture.md, section 4.
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

  /** How long a lookup refused only because others are in flight should wait. */
  static final Duration IN_FLIGHT_RETRY_AFTER = Duration.ofSeconds(1);

  private final Cache<String, Window> windows;
  private final AtomicLong windowIds = new AtomicLong();
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
   * Admits a lookup of a reference and counts it as in flight, or refuses it.
   *
   * @param reference the reference being looked up
   * @param now the current instant
   * @return the admitted lookup, to pass to {@link #end} once its digits have been compared
   * @throws LookupRateLimitedException if the reference has had too many wrong lookups in its
   *     current window, or would have if every lookup in flight were wrong
   */
  public Attempt begin(AppointmentReference reference, Instant now) {
    AtomicReference<Window> refused = new AtomicReference<>();
    Window admitted =
        windows
            .asMap()
            .compute(
                reference.value(),
                (key, window) -> {
                  if (window == null || !window.isOpenAt(now)) {
                    return new Window(
                        windowIds.incrementAndGet(), now, now.plus(windowLength), 0, 1);
                  }
                  if (window.failures() + window.inFlight() >= maxFailures) {
                    refused.set(window);
                    return window;
                  }
                  return window.withCounts(window.failures(), window.inFlight() + 1);
                });
    Window full = refused.get();
    if (full != null) {
      throw new LookupRateLimitedException(
          full.failures() >= maxFailures
              ? Duration.between(now, full.closesAt())
              : IN_FLIGHT_RETRY_AFTER);
    }
    return new Attempt(reference.value(), admitted.id());
  }

  /**
   * Ends an admitted lookup: a wrong one becomes a failure, any other is released. Warns when the
   * failure blocks the reference.
   *
   * @param attempt what {@link #begin} returned
   * @param wrongDigits whether the reference and the digits matched no appointment; {@code false}
   *     also for a lookup that failed for another reason, such as the database, which is not the
   *     caller's guess
   */
  public void end(Attempt attempt, boolean wrongDigits) {
    AtomicReference<Instant> blockedUntil = new AtomicReference<>();
    windows
        .asMap()
        .computeIfPresent(
            attempt.key(),
            (key, window) -> {
              if (window.id() != attempt.window()) {
                return window;
              }
              int failures = window.failures() + (wrongDigits ? 1 : 0);
              int inFlight = window.inFlight() - 1;
              if (wrongDigits && failures == maxFailures) {
                blockedUntil.set(window.closesAt());
              }
              return failures == 0 && inFlight == 0 ? null : window.withCounts(failures, inFlight);
            });
    Instant until = blockedUntil.get();
    if (until != null) {
      log.warn("lookup.blocked failures={} until={}", maxFailures, until);
    }
  }

  /** Forgets every count. For tests, which share one application. */
  public void reset() {
    windows.invalidateAll();
  }

  /**
   * A lookup that {@link #begin} admitted. It holds the reference only to find its window again,
   * and never shows it.
   *
   * @param key the reference's stored form
   * @param window the id of the window the lookup was counted in
   */
  public record Attempt(String key, long window) {

    /**
     * Hides the reference, which is half of what opens an appointment.
     *
     * @return a fixed text without the reference
     */
    @Override
    public String toString() {
      return "Attempt[redacted]";
    }
  }

  /**
   * One reference's lookups since its window opened.
   *
   * @param id tells this window from a later one for the same reference
   * @param opensAt the first lookup
   * @param closesAt when the window closes
   * @param failures the wrong lookups in it
   * @param inFlight the admitted lookups whose digits are still being compared
   */
  private record Window(long id, Instant opensAt, Instant closesAt, int failures, int inFlight) {

    boolean isOpenAt(Instant now) {
      return now.isBefore(closesAt);
    }

    Window withCounts(int failures, int inFlight) {
      return new Window(id, opensAt, closesAt, failures, inFlight);
    }
  }
}
