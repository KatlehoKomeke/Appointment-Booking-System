package za.co.fynbos.branchbook.booking.application;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
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
 * control it. The window is opened, measured and closed on each instance's own clock, so two
 * instances disagree only by their clocks' difference: milliseconds on the hosted demo's tasks,
 * which the platform keeps in step.
 *
 * <p>A lookup is admitted and counted in one atomic step, before the database is read: {@link
 * #begin} counts it as in flight, and {@link #end} turns it into a failure if the digits were wrong
 * or releases it if they were right. A lookup is admitted only while the failures and the lookups
 * in flight together are fewer than the limit, so requests that arrive together cannot all pass a
 * check made before any of them was counted, the race the M3 review found. Lookups with the right
 * digits are released when they end, so a customer who keeps their appointment page open is never
 * blocked for the hour. A lookup refused only because others are still in flight is told to retry
 * after a second.
 *
 * <p>The counts live in {@code lookup_attempts} (V8), one row per reference with an open window,
 * shared by every instance and kept across restarts. Each {@link #begin} and {@link #end} is its
 * own short transaction ({@code REQUIRES_NEW}) that locks the reference's row, so the counts of one
 * reference, from any instance, are made one at a time. It is taken outside the lookup's or the
 * cancel's own transaction: a count so survives the rollback of a wrong-digit cancel, a read-only
 * lookup never has to write, and the limiter never waits on a second connection from inside a
 * transaction. A window that has closed is ignored and replaced at the next lookup; an hourly job
 * deletes closed windows only to keep the table small.
 *
 * <p>The lookups in flight hold their place on a lease of {@link #IN_FLIGHT_LEASE}, counted from
 * the newest admission, so a lookup that is never settled does bounded harm: one an instance never
 * ended, because it crashed, stops counting once the lease runs out, and so does one whose settle
 * failed. The customer's answer is decided before {@link #end} runs, so a settle that fails, on a
 * lock, a connection or anything else in the database, is logged and dropped rather than allowed to
 * replace a {@code 200} with a {@code 503}; that lookup stays in flight until its lease runs out,
 * which is why the lease exists. A settle after its own lease changes nothing, since an admission
 * may already have reset the count it was part of.
 *
 * <p>Blocking a reference logs one warning, the signal that someone may be guessing digits. It
 * names no reference, which is half of what opens an appointment; the request's correlation id ties
 * it to the request.
 */
@Component
public class LookupAttemptLimiter {

  private static final Logger log = LoggerFactory.getLogger(LookupAttemptLimiter.class);

  /** How long a lookup refused only because others are in flight should wait. */
  static final Duration IN_FLIGHT_RETRY_AFTER = Duration.ofSeconds(1);

  /**
   * How long an admitted lookup counts as in flight if it is never settled. Far above any lookup:
   * the session's 2 second lock timeout and 5 second statement timeout and the 3 second wait for a
   * connection bound a lookup, read and view included, to well under it.
   */
  static final Duration IN_FLIGHT_LEASE = Duration.ofSeconds(30);

  private final LookupAttemptStore store;
  private final TransactionOperations transactions;
  private final int maxFailures;
  private final Duration windowLength;

  /**
   * The limiter the application uses: each count is a transaction of its own, whatever the caller
   * holds.
   */
  @Autowired
  LookupAttemptLimiter(
      BookingProperties booking,
      LookupAttemptStore store,
      PlatformTransactionManager transactionManager) {
    this(booking, store, countTransaction(transactionManager));
  }

  /**
   * The limiter over any transaction operations; unit tests pass {@link
   * TransactionOperations#withoutTransaction()}.
   */
  LookupAttemptLimiter(
      BookingProperties booking, LookupAttemptStore store, TransactionOperations transactions) {
    this.store = store;
    this.transactions = transactions;
    this.maxFailures = booking.lookupMaxFailures();
    this.windowLength = booking.lookupWindow();
  }

  private static TransactionTemplate countTransaction(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setName("LookupAttemptLimiter");
    return template;
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
    // Cut to the microsecond the database keeps, so the window's opening instant reads back exactly
    // as written and end() can tell the window the lookup was counted in from a later one.
    Instant at = now.truncatedTo(ChronoUnit.MICROS);
    Window counted =
        Objects.requireNonNull(transactions.execute(status -> admit(reference.value(), at)));
    return new Attempt(reference.value(), counted.opensAt(), at);
  }

  /** Inside the count's transaction: the reference's window, locked, with this lookup counted. */
  private Window admit(String reference, Instant now) {
    Instant lease = now.plus(IN_FLIGHT_LEASE);
    Window fresh = new Window(reference, now, now.plus(windowLength), lease, 0, 1);
    Optional<Window> locked = store.lockWindow(reference);
    while (locked.isEmpty()) {
      if (store.insert(fresh)) {
        return fresh;
      }
      // Two first lookups at once: the other's row was committed while this insert waited for it,
      // so it is locked like any other. Only if that lookup has ended and deleted it again is there
      // still none, and the insert is simply tried once more.
      locked = store.lockWindow(reference);
    }
    Window window = locked.get();
    if (!window.isOpenAt(now)) {
      store.update(fresh);
      return fresh;
    }
    // The lookups in flight count only while the newest of them is within its lease: one that was
    // never settled holds its place for the lease and no longer.
    int live = window.heldUntil().isAfter(now) ? window.inFlight() : 0;
    if (window.failures() + live >= maxFailures) {
      throw new LookupRateLimitedException(
          window.failures() >= maxFailures
              ? Duration.between(now, window.closesAt())
              : IN_FLIGHT_RETRY_AFTER);
    }
    Window admitted = window.withCounts(window.failures(), live + 1).withLease(lease);
    store.update(admitted);
    return admitted;
  }

  /**
   * Ends an admitted lookup: a wrong one becomes a failure, any other is released. Warns when the
   * failure blocks the reference. Never throws: the caller's answer is decided by now, so a count
   * that cannot be settled is logged and left in flight until its lease runs out.
   *
   * @param attempt what {@link #begin} returned
   * @param wrongDigits whether the reference and the digits matched no appointment; {@code false}
   *     also for a lookup that failed for another reason, such as the database, which is not the
   *     caller's guess
   * @param now the current instant
   */
  public void end(Attempt attempt, boolean wrongDigits, Instant now) {
    if (!attempt.begunAt().plus(IN_FLIGHT_LEASE).isAfter(now)) {
      // Its lease has run out: an admission since may have reset the count it was part of, so the
      // count is no longer this lookup's to change.
      return;
    }
    Optional<Instant> blockedUntil;
    try {
      blockedUntil =
          Objects.requireNonNull(transactions.execute(status -> settle(attempt, wrongDigits)));
    } catch (RuntimeException failure) {
      // Neither the reference nor the message, which could quote the statement.
      log.warn("lookup.count_not_settled cause={}", failure.getClass().getSimpleName());
      return;
    }
    blockedUntil.ifPresent(
        until -> log.warn("lookup.blocked failures={} until={}", maxFailures, until));
  }

  /**
   * Inside the count's transaction: takes the lookup out of the window it was counted in, if that
   * is still the reference's window.
   *
   * @return when the reference is blocked until, if this failure is the one that blocked it
   */
  private Optional<Instant> settle(Attempt attempt, boolean wrongDigits) {
    Optional<Window> locked = store.lockWindow(attempt.key());
    if (locked.isEmpty() || !locked.get().opensAt().equals(attempt.window())) {
      // Counted in a window that has since closed and been forgotten or replaced: there is nothing
      // to release there, and the new window's counts are not this lookup's to change.
      return Optional.empty();
    }
    Window window = locked.get();
    int failures = window.failures() + (wrongDigits ? 1 : 0);
    int inFlight = Math.max(0, window.inFlight() - 1);
    if (failures == 0 && inFlight == 0) {
      store.delete(attempt.key());
    } else {
      store.update(window.withCounts(failures, inFlight));
    }
    return wrongDigits && failures == maxFailures
        ? Optional.of(window.closesAt())
        : Optional.empty();
  }

  /**
   * A lookup that {@link #begin} admitted. It holds the reference only to find its window again,
   * and never shows it.
   *
   * @param key the reference's stored form
   * @param window when the window the lookup was counted in opened, which tells it from a later one
   *     for the same reference
   * @param begunAt when the lookup was admitted, from which its lease runs
   */
  public record Attempt(String key, Instant window, Instant begunAt) {

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
}
