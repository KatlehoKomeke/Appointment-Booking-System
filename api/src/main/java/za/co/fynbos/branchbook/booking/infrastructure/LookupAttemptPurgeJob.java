package za.co.fynbos.branchbook.booking.infrastructure;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.co.fynbos.branchbook.booking.application.LookupAttemptStore;

/**
 * Deletes the lookup windows that have closed, every hour, so {@code lookup_attempts} holds only
 * the references looked up wrongly in about the last hour (BR-13). The limit itself does not wait
 * for it: a closed window is already ignored by the limiter, which replaces it at the next lookup,
 * purged or not ({@link JdbcLookupAttemptStore}). The purge only keeps the table small. The first
 * run is an hour after start-up. Both times are {@code branchbook.jobs.lookup-attempt-purge}.
 */
@Component
class LookupAttemptPurgeJob {

  private static final Logger log = LoggerFactory.getLogger(LookupAttemptPurgeJob.class);

  private final LookupAttemptStore store;
  private final Clock clock;

  LookupAttemptPurgeJob(LookupAttemptStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${branchbook.jobs.lookup-attempt-purge.interval}",
      initialDelayString = "${branchbook.jobs.lookup-attempt-purge.initial-delay}")
  void purge() {
    int purged = store.purgeClosed(clock.instant());
    log.info("lookup_attempts.purged count={}", purged);
  }
}
