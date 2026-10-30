package za.co.fynbos.branchbook.booking.infrastructure;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.co.fynbos.branchbook.booking.application.RetentionPurge;
import za.co.fynbos.branchbook.booking.application.RetentionPurge.Purged;

/**
 * Runs the retention purge every hour, so the hosted demo deletes each booking, with its messages
 * and keys, within the hour after its appointment ends, or after {@code
 * branchbook.retention.after-end} more where that is set (SECURITY.md, ADR-0006).
 *
 * <p>It exists only while {@code branchbook.retention.enabled} is true: on in the hosted demo's
 * task definition, off by default, and so off in docker compose, {@code make dev-api} and the
 * tests, which call {@link RetentionPurge} themselves. Like {@link IdempotencyKeyPurgeJob} it runs
 * on Spring Boot's default scheduler. The first run is a minute after start-up, so a task that
 * restarts often still purges, without slowing start-up itself. Both times are {@code
 * branchbook.jobs.retention-purge}.
 *
 * <p>Each run logs one line with three counts and nothing else (BR-19). A failed batch has already
 * been logged at WARN by the purge, which never throws.
 */
@Component
@ConditionalOnProperty(name = "branchbook.retention.enabled", havingValue = "true")
class RetentionPurgeJob {

  private static final Logger log = LoggerFactory.getLogger(RetentionPurgeJob.class);

  private final RetentionPurge retention;
  private final Clock clock;

  RetentionPurgeJob(RetentionPurge retention, Clock clock) {
    this.retention = retention;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${branchbook.jobs.retention-purge.interval}",
      initialDelayString = "${branchbook.jobs.retention-purge.initial-delay}")
  void purge() {
    Purged purged = retention.purge(clock.instant());
    log.info(
        "retention.purged appointments={} messages={} keys={}",
        purged.appointments(),
        purged.messages(),
        purged.keys());
  }
}
