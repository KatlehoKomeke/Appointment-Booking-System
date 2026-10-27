package za.co.fynbos.branchbook.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import za.co.fynbos.branchbook.BranchBookApplication;
import za.co.fynbos.branchbook.booking.application.LookupAttemptStore;

/**
 * The lookup windows' purge job is always there, with no setting to switch it on; it runs every
 * hour from an hour after start-up; and each run purges as of the clock and logs one count.
 *
 * <p>The job is found the way the application finds it, by scanning from {@link
 * BranchBookApplication}'s package with the default filters, and scheduled the way the application
 * schedules it. Everything else the scan would find is filtered out and the store is a stand-in, so
 * no database is needed; {@code LookupAttemptPurgeJobIT} proves the purge itself.
 */
@ExtendWith(OutputCaptureExtension.class)
class LookupAttemptPurgeJobTest {

  private static final Instant NOW = Instant.parse("2026-10-28T06:00:00Z");

  private final LookupAttemptStore store = mock(LookupAttemptStore.class);

  /** The job is the only scheduled bean in this context, so the one task registered is its run. */
  @Test
  void isAlwaysThereAndRunsEveryHourFromAnHourAfterStartUp() {
    new ApplicationContextRunner()
        .withBean(LookupAttemptStore.class, () -> store)
        .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
        .withUserConfiguration(ScannedAndScheduledLikeTheApplication.class)
        .run(
            started -> {
              assertThat(started).hasSingleBean(LookupAttemptPurgeJob.class);
              assertThat(started.getBean(ScheduledTaskHolder.class).getScheduledTasks())
                  .singleElement()
                  .extracting(scheduled -> scheduled.getTask())
                  .isInstanceOfSatisfying(
                      FixedDelayTask.class,
                      task -> {
                        assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofHours(1));
                        assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofHours(1));
                      });
            });
  }

  @Test
  void eachRunPurgesAsOfTheClockAndLogsOnlyItsCount(CapturedOutput output) {
    when(store.purgeClosed(NOW)).thenReturn(3);

    new LookupAttemptPurgeJob(store, Clock.fixed(NOW, ZoneOffset.UTC)).purge();

    verify(store).purgeClosed(NOW);
    assertThat(output.getAll())
        .containsOnlyOnce("lookup_attempts.purged")
        .containsPattern("INFO.*lookup_attempts\\.purged count=3\\R");
  }

  /**
   * The application's own component scan and scheduling, narrowed to the job: every class whose
   * name does not end in {@code .LookupAttemptPurgeJob} is filtered out, and the default filters
   * still want {@code @Component}, so the job is registered here only if the application would find
   * it.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ComponentScan(
      basePackageClasses = BranchBookApplication.class,
      excludeFilters =
          @Filter(type = FilterType.REGEX, pattern = ".*(?<!\\.LookupAttemptPurgeJob)"))
  static class ScannedAndScheduledLikeTheApplication {}
}
