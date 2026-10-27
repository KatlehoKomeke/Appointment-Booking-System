package za.co.fynbos.branchbook.notification.application;

import java.time.Duration;
import java.time.Instant;

/**
 * The per-recipient daily cap's ledger, the {@code sms_recipient_sends} table (BR-17, V7): one row
 * for each SMS the cap counts, holding the number's keyed fingerprint and when the SMS was written,
 * never the number. A row outlives its message, which a cancel or the dispatcher may delete, so the
 * count covers every SMS of the last 24 hours, whatever became of it.
 */
public interface RecipientSendLedger {

  /** How long a row counts towards the cap, and so how long it is kept. */
  Duration WINDOW = Duration.ofHours(24);

  /**
   * Counts the SMS written to one fingerprint in the {@link #WINDOW} before the given instant.
   *
   * @param fingerprint the number's fingerprint
   * @param now the current instant, from the application's clock; a row written exactly 24 hours
   *     before it no longer counts
   * @return how many SMS count towards the cap
   */
  long countRecent(String fingerprint, Instant now);

  /**
   * Records one SMS written to a fingerprint, in the caller's transaction, so the row commits or
   * rolls back with its message.
   *
   * @param fingerprint the number's fingerprint
   * @param writtenAt when the SMS was written, from the application's clock
   */
  void record(String fingerprint, Instant writtenAt);

  /**
   * Deletes every row that no longer counts: written 24 hours or more before the given instant.
   *
   * @param now the current instant, from the application's clock
   * @return how many rows were deleted
   */
  int purgeExpired(Instant now);
}
