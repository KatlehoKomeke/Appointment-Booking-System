package za.co.fynbos.branchbook.notification.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import za.co.fynbos.branchbook.notification.application.RecipientSendLedger;

/**
 * Keeps the daily cap's ledger in {@code sms_recipient_sends} with plain SQL. Every instant is
 * bound from the caller, never taken from the database's {@code now()}, so tests control time
 * (BR-20). The count reads through {@code ix_sms_recipient_sends_fingerprint} and the purge through
 * {@code ix_sms_recipient_sends_written}.
 */
@Repository
class JdbcRecipientSendLedger implements RecipientSendLedger {

  private static final String COUNT_RECENT =
      """
      SELECT count(*) FROM sms_recipient_sends
      WHERE recipient_fingerprint = :fingerprint AND written_at > :since""";

  private static final String RECORD =
      """
      INSERT INTO sms_recipient_sends (recipient_fingerprint, written_at)
      VALUES (:fingerprint, :writtenAt)""";

  private static final String PURGE_EXPIRED =
      "DELETE FROM sms_recipient_sends WHERE written_at <= :since";

  private final JdbcClient jdbc;

  JdbcRecipientSendLedger(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public long countRecent(String fingerprint, Instant now) {
    return jdbc.sql(COUNT_RECENT)
        .param("fingerprint", fingerprint)
        .param("since", windowStart(now))
        .query(Long.class)
        .single();
  }

  @Override
  public void record(String fingerprint, Instant writtenAt) {
    jdbc.sql(RECORD)
        .param("fingerprint", fingerprint)
        .param("writtenAt", OffsetDateTime.ofInstant(writtenAt, ZoneOffset.UTC))
        .update();
  }

  @Override
  public int purgeExpired(Instant now) {
    return jdbc.sql(PURGE_EXPIRED).param("since", windowStart(now)).update();
  }

  /**
   * The instant 24 hours before now: a row written after it counts, one written at it or before
   * does not.
   */
  private static OffsetDateTime windowStart(Instant now) {
    return OffsetDateTime.ofInstant(now.minus(WINDOW), ZoneOffset.UTC);
  }
}
