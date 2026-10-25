package za.co.fynbos.branchbook.booking.infrastructure;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.co.fynbos.branchbook.booking.application.IdempotencyKeyStore;

/**
 * Deletes expired idempotency keys every hour, so a key is honoured for at most 25 hours (BR-12).
 * The first run is an hour after start-up, so a short-lived process, such as a test run, never
 * purges.
 */
@Component
class IdempotencyKeyPurgeJob {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyPurgeJob.class);

  private final IdempotencyKeyStore keys;
  private final Clock clock;

  IdempotencyKeyPurgeJob(IdempotencyKeyStore keys, Clock clock) {
    this.keys = keys;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
  void purge() {
    int purged = keys.purgeExpired(clock.instant());
    log.info("idempotency.purged count={}", purged);
  }
}
