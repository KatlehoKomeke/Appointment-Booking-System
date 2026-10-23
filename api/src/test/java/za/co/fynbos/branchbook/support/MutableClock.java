package za.co.fynbos.branchbook.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock that tests set and move, so time-dependent rules are tested at fixed, meaningful moments
 * instead of whenever the build happens to run.
 *
 * <p>It replaces the application's clock in every integration test (see {@link
 * TestClockConfiguration}). Clocks derived with {@link #withZone} share its time, so moving this
 * clock moves them too, as the application expects of a real clock.
 */
public final class MutableClock extends Clock {

  private final AtomicReference<Instant> now;
  private final ZoneId zone;

  /**
   * Creates a clock that stands still at an instant until it is moved.
   *
   * @param start the instant the clock shows
   */
  public MutableClock(Instant start) {
    this(new AtomicReference<>(start), ZoneOffset.UTC);
  }

  private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
    this.now = now;
    this.zone = zone;
  }

  /**
   * Moves the clock to an instant.
   *
   * @param instant the instant the clock shows from now on
   */
  public void setInstant(Instant instant) {
    now.set(instant);
  }

  /**
   * Moves the clock forward.
   *
   * @param duration how far to move it
   */
  public void advance(Duration duration) {
    now.updateAndGet(current -> current.plus(duration));
  }

  @Override
  public Instant instant() {
    return now.get();
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId otherZone) {
    return new MutableClock(now, otherZone);
  }
}
