package za.co.fynbos.branchbook.notification.application;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Reads the two figures of the outbox that the alarm gauges show (FR-30): when the oldest PENDING
 * message was written, and how many messages are FAILED. It is read once per dispatcher tick, by
 * {@link OutboxHealthGauges}, never on a scrape.
 */
public interface OutboxStatistics {

  /**
   * Reads both figures in one query.
   *
   * @return the figures as they stand
   */
  Snapshot read();

  /**
   * The outbox's figures at one moment. Neither names a message, a recipient or a reference.
   *
   * @param oldestPendingCreatedAt when the oldest PENDING message was written, or {@code null} when
   *     none is PENDING
   * @param failed how many messages are FAILED, for any reason
   */
  record Snapshot(@Nullable Instant oldestPendingCreatedAt, long failed) {}
}
