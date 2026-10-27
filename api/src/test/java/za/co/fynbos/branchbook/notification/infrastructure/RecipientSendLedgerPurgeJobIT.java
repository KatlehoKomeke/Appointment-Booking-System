package za.co.fynbos.branchbook.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fynbos.branchbook.notification.application.RecipientSendLedger;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;

/**
 * BR-17, V7: a ledger row counts for exactly 24 hours after its SMS was written, purged or not, and
 * the hourly purge deletes only rows that no longer count. The rows are written with raw SQL, so
 * the cap can stay off in this shared context; the job is called directly, its schedule is
 * Spring's.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class RecipientSendLedgerPurgeJobIT {

  private static final String FINGERPRINT = "a".repeat(64);

  @Autowired private RecipientSendLedgerPurgeJob job;

  @Autowired private RecipientSendLedger ledger;

  @Autowired private MutableClock clock;

  @Autowired private JdbcTemplate jdbc;

  @Test
  void purgeRemovesRowsTwentyFourHoursOldAndKeepsYoungerOnes() {
    Instant now = clock.instant();
    Instant dayAgo = now.minus(Duration.ofHours(24));
    write(dayAgo.minusSeconds(1));
    write(dayAgo);
    write(dayAgo.plusSeconds(1));
    write(now);
    assertThat(ledger.countRecent(FINGERPRINT, now))
        .as("a row stops counting 24 hours after its SMS, before any purge")
        .isEqualTo(2);

    job.purge();

    assertThat(
            jdbc.queryForList(
                "SELECT written_at FROM sms_recipient_sends ORDER BY written_at",
                OffsetDateTime.class))
        .extracting(OffsetDateTime::toInstant)
        .containsExactly(dayAgo.plusSeconds(1), now);
    assertThat(ledger.countRecent(FINGERPRINT, now)).isEqualTo(2);
  }

  private void write(Instant writtenAt) {
    jdbc.update(
        "INSERT INTO sms_recipient_sends (recipient_fingerprint, written_at) VALUES (?, ?)",
        FINGERPRINT,
        OffsetDateTime.ofInstant(writtenAt, ZoneOffset.UTC));
  }
}
