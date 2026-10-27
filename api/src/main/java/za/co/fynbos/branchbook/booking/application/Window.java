package za.co.fynbos.branchbook.booking.application;

import java.time.Instant;

/**
 * One reference's lookups since its window opened: a row of {@code lookup_attempts} (BR-13, V8). It
 * holds the reference only to find its row again, and never shows it.
 *
 * @param reference the reference's stored form
 * @param opensAt the first lookup, which also tells this window from a later one for the same
 *     reference
 * @param closesAt when the window closes: {@code opensAt} plus {@code
 *     branchbook.booking.lookup-window}
 * @param heldUntil the lease of the newest admitted lookup: the lookups in flight count towards the
 *     limit only until then, so one that is never settled holds a place for the lease and no longer
 * @param failures the wrong lookups in it
 * @param inFlight the admitted lookups whose digits are still being compared
 */
public record Window(
    String reference,
    Instant opensAt,
    Instant closesAt,
    Instant heldUntil,
    int failures,
    int inFlight) {

  /**
   * Whether the window is still open.
   *
   * @param now the current instant
   * @return {@code true} until the window closes
   */
  public boolean isOpenAt(Instant now) {
    return now.isBefore(closesAt);
  }

  /**
   * The same window with other counts and the same lease.
   *
   * @param failures the wrong lookups in it
   * @param inFlight the admitted lookups whose digits are still being compared
   * @return the window with those counts
   */
  public Window withCounts(int failures, int inFlight) {
    return new Window(reference, opensAt, closesAt, heldUntil, failures, inFlight);
  }

  /**
   * The same window with a new lease, granted by an admission.
   *
   * @param heldUntil when the lease runs out
   * @return the window with that lease
   */
  public Window withLease(Instant heldUntil) {
    return new Window(reference, opensAt, closesAt, heldUntil, failures, inFlight);
  }

  /**
   * Hides the reference, which is half of what opens an appointment.
   *
   * @return a fixed text without the reference
   */
  @Override
  public String toString() {
    return "Window[redacted]";
  }
}
