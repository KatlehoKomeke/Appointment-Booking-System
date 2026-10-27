package za.co.fynbos.branchbook.booking.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes every appointment that has ended, {@code branchbook.retention.after-end} ago or earlier
 * (zero by default), with its messages and its idempotency keys, so the hosted demo keeps a
 * booking, and the customer's name and number, until its appointment is over and no longer
 * (SECURITY.md, ADR-0006). A booking for a future date is never touched, however long ago it was
 * made; a cancelled appointment goes by the same rule, once its original end time has passed.
 *
 * <p>It works in batches of up to {@value #BATCH} appointments, one READ COMMITTED transaction
 * each, until a batch comes back short. A batch:
 *
 * <ol>
 *   <li>locks the appointments that ended first with {@code FOR UPDATE SKIP LOCKED}, so one that
 *       another transaction holds right now is left for the next run rather than waited for;
 *   <li>deletes their messages through {@link NotificationEraser}, waiting at most the session's
 *       {@code lock_timeout} for a message the dispatcher is sending;
 *   <li>deletes the idempotency keys that point at them;
 *   <li>deletes the appointments, now that nothing references them.
 * </ol>
 *
 * <p>Small transactions keep the locks few and short, so bookings and the dispatcher are never held
 * up for long. A batch that fails, for a lock timeout or anything else, is rolled back and logged
 * at WARN with the counts so far and the failure's type; the run stops there without throwing, the
 * batches before it stay deleted, and the next run carries on.
 *
 * <p>The service is always present and does nothing until called; only the hourly job, which exists
 * while {@code branchbook.retention.enabled} is true, calls it. The cutoff is counted from the
 * instant the caller passes, never from the database's clock (BR-20). Log lines carry counts and a
 * failure's type only, never a name, a number or a reference (BR-19).
 */
@Service
public class RetentionPurge {

  private static final Logger log = LoggerFactory.getLogger(RetentionPurge.class);

  /** The most appointments one batch deletes, so one transaction never holds many locks. */
  static final int BATCH = 500;

  private final TransactionTemplate transaction;
  private final AppointmentRepository appointments;
  private final IdempotencyKeyStore keys;
  private final NotificationEraser notifications;
  private final Duration afterEnd;

  RetentionPurge(
      PlatformTransactionManager transactionManager,
      AppointmentRepository appointments,
      IdempotencyKeyStore keys,
      NotificationEraser notifications,
      RetentionProperties properties) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setName("RetentionPurge.deleteBatch");
    this.transaction = template;
    this.appointments = appointments;
    this.keys = keys;
    this.notifications = notifications;
    this.afterEnd = properties.afterEnd();
  }

  /**
   * Deletes every appointment that ended {@code after-end} before {@code now} or earlier, with its
   * messages and keys, in batches of {@value #BATCH}.
   *
   * @param now the current instant, from the application's clock; the tests pass their own
   * @return what was deleted; if a batch failed, what the batches before it deleted
   */
  public Purged purge(Instant now) {
    return purge(now, BATCH);
  }

  /**
   * Deletes in batches of the given size; the tests use a small one to cross batch boundaries.
   *
   * @param now the current instant
   * @param batchSize the most appointments one batch deletes, at least 1
   * @return what was deleted
   */
  Purged purge(Instant now, int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be at least 1");
    }
    Instant cutoff = now.minus(afterEnd);
    Purged total = Purged.NONE;
    while (true) {
      Batch batch;
      try {
        batch =
            Objects.requireNonNull(transaction.execute(status -> deleteBatch(cutoff, batchSize)));
      } catch (RuntimeException failure) {
        log.warn(
            "retention.batch_failed appointments={} messages={} keys={} cause={}",
            total.appointments(),
            total.messages(),
            total.keys(),
            failure.getClass().getSimpleName());
        return total;
      }
      total = total.plus(batch.deleted());
      if (batch.locked() < batchSize) {
        return total;
      }
    }
  }

  /** One batch, inside its own transaction: lock, then delete in the order the keys require. */
  private Batch deleteBatch(Instant cutoff, int batchSize) {
    List<UUID> ids = appointments.lockEndedNoLaterThan(cutoff, batchSize);
    if (ids.isEmpty()) {
      return new Batch(0, Purged.NONE);
    }
    int messages = notifications.eraseFor(ids);
    int keyCount = keys.deleteForAppointments(ids);
    int deleted = appointments.deleteAllById(ids);
    return new Batch(ids.size(), new Purged(deleted, messages, keyCount));
  }

  /**
   * What a run deleted. Counts only, so it can be logged as it is.
   *
   * @param appointments the appointments deleted
   * @param messages the notification messages deleted with them
   * @param keys the idempotency keys deleted with them
   */
  public record Purged(int appointments, int messages, int keys) {

    /** Nothing deleted. */
    static final Purged NONE = new Purged(0, 0, 0);

    /** These counts and another's, added. */
    Purged plus(Purged other) {
      return new Purged(
          appointments + other.appointments, messages + other.messages, keys + other.keys);
    }
  }

  /** How many appointments a batch locked, which says whether another batch may follow. */
  private record Batch(int locked, Purged deleted) {}
}
