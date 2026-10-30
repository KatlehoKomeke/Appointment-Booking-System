package za.co.fynbos.branchbook.booking.infrastructure;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.co.fynbos.branchbook.booking.application.IdempotencyKeyStore;

/**
 * Deletes expired idempotency keys every hour, so the table holds only about a day of keys. Expiry
 * itself does not wait for it: a key stops being honoured exactly its time to live after its
 * booking, purged or not (BR-12; {@link JdbcIdempotencyKeyStore}). The first run is an hour after
 * start-up. Both times are {@code branchbook.jobs.idempotency-key-purge}.
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

  @Scheduled(
      fixedDelayString = "${branchbook.jobs.idempotency-key-purge.interval}",
      initialDelayString = "${branchbook.jobs.idempotency-key-purge.initial-delay}")
  void purge() {
    int purged = keys.purgeExpired(clock.instant());
    log.info("idempotency.purged count={}", purged);
  }
}
