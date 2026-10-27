package za.co.fynbos.branchbook.notification.infrastructure;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.co.fynbos.branchbook.notification.application.RecipientSendLedger;

/**
 * Deletes the daily cap's ledger rows once they are 24 hours old, every hour, so the ledger holds
 * about a day of fingerprints (BR-17, V7). The count does not wait for it: a row stops counting
 * exactly 24 hours after its SMS, purged or not.
 *
 * <p>It always runs, cap or no cap, so rows written before a deployment switched the cap off still
 * go. Like the key purge in booking, it runs on Spring Boot's default scheduler; like the retention
 * job, its first run is a minute after start-up, so a task that restarts often still purges. Each
 * run logs one line with its count and nothing else (BR-19).
 */
@Component
class RecipientSendLedgerPurgeJob {

  private static final Logger log = LoggerFactory.getLogger(RecipientSendLedgerPurgeJob.class);

  private final RecipientSendLedger ledger;
  private final Clock clock;

  RecipientSendLedgerPurgeJob(RecipientSendLedger ledger, Clock clock) {
    this.ledger = ledger;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
  void purge() {
    int purged = ledger.purgeExpired(clock.instant());
    log.info("cap_ledger.purged count={}", purged);
  }
}
