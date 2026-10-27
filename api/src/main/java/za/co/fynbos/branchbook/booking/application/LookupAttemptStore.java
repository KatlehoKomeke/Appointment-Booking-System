package za.co.fynbos.branchbook.booking.application;

import java.time.Instant;
import java.util.Optional;

/**
 * Where the lookup limiter keeps its counts: the {@code lookup_attempts} table, one row per
 * reference with an open window (BR-13, V8). The rows are plain data; the rule that reads and
 * changes them is the {@link LookupAttemptLimiter}'s. It locks a reference's row for the length of
 * each count, so every instance of the application counts against the same window, one at a time.
 */
public interface LookupAttemptStore {

  /**
   * Locks a reference's window for the rest of the caller's transaction, so no other count of the
   * reference can read it until the caller commits: {@code SELECT ... FOR UPDATE}.
   *
   * @param reference the reference's stored form
   * @return its window, open or closed, or empty if it has none
   */
  Optional<Window> lockWindow(String reference);

  /**
   * Inserts a reference's window, unless it has one already.
   *
   * @param window the window
   * @return whether the row was inserted; {@code false} if another transaction inserted one first
   */
  boolean insert(Window window);

  /**
   * Rewrites a reference's window, locked by {@link #lockWindow} in the same transaction.
   *
   * @param window the window, with its new counts or its new span
   */
  void update(Window window);

  /**
   * Forgets a reference's window, locked by {@link #lockWindow} in the same transaction.
   *
   * @param reference the reference's stored form
   */
  void delete(String reference);

  /**
   * Deletes the windows that have closed, which the limiter already ignores.
   *
   * @param now the current instant
   * @return how many windows were deleted
   */
  int purgeClosed(Instant now);
}
