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
import za.co.fynbos.branchbook.booking.application.RetentionPurge;
import za.co.fynbos.branchbook.booking.application.RetentionPurge.Purged;

/**
 * The retention job exists only where {@code branchbook.retention.enabled} is true, which only the
 * hosted demo sets; it runs every hour from a minute after start-up; and each run purges as of the
 * clock and logs one line of three counts.
 *
 * <p>The job is found the way the application finds it, by scanning from {@link
 * BranchBookApplication}'s package with the default filters, and scheduled the way the application
 * schedules it, so a job that scanning would miss, or a schedule that never registers, fails here.
 * Everything else the scan would find is filtered out and the purge is a stand-in, so no database
 * is needed; {@code RetentionPurgeIT} proves the purge itself.
 */
@ExtendWith(OutputCaptureExtension.class)
class RetentionPurgeJobTest {

  private static final Instant NOW = Instant.parse("2026-10-28T06:00:00Z");

  private final RetentionPurge retention = mock(RetentionPurge.class);

  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withBean(RetentionPurge.class, () -> retention)
          .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
          .withUserConfiguration(ScannedAndScheduledLikeTheApplication.class);

  @Test
  void isAbsentByDefault() {
    context.run(
        started -> {
          assertThat(started).doesNotHaveBean(RetentionPurgeJob.class);
          assertThat(started.getBean(ScheduledTaskHolder.class).getScheduledTasks()).isEmpty();
        });
  }

  @Test
  void isAbsentWhenSwitchedOff() {
    context
        .withPropertyValues("branchbook.retention.enabled=false")
        .run(
            started -> {
              assertThat(started).doesNotHaveBean(RetentionPurgeJob.class);
              assertThat(started.getBean(ScheduledTaskHolder.class).getScheduledTasks()).isEmpty();
            });
  }

  @Test
  void isPresentWhenSwitchedOn() {
    context
        .withPropertyValues("branchbook.retention.enabled=true")
        .run(started -> assertThat(started).hasSingleBean(RetentionPurgeJob.class));
  }

  /** The job is the only scheduled bean in this context, so the one task registered is its run. */
  @Test
  void runsEveryHourFromAMinuteAfterStartUp() {
    context
        .withPropertyValues("branchbook.retention.enabled=true")
        .run(
            started ->
                assertThat(started.getBean(ScheduledTaskHolder.class).getScheduledTasks())
                    .singleElement()
                    .extracting(scheduled -> scheduled.getTask())
                    .isInstanceOfSatisfying(
                        FixedDelayTask.class,
                        task -> {
                          assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofHours(1));
                          assertThat(task.getInitialDelayDuration())
                              .isEqualTo(Duration.ofMinutes(1));
                        }));
  }

  @Test
  void eachRunPurgesAsOfTheClockAndLogsOnlyItsCounts(CapturedOutput output) {
    when(retention.purge(NOW)).thenReturn(new Purged(3, 4, 2));

    new RetentionPurgeJob(retention, Clock.fixed(NOW, ZoneOffset.UTC)).purge();

    verify(retention).purge(NOW);
    assertThat(output.getAll())
        .containsOnlyOnce("retention.purged")
        .containsPattern("INFO.*retention\\.purged appointments=3 messages=4 keys=2\\R");
  }

  /**
   * The application's own component scan and scheduling, narrowed to the job: every class whose
   * name does not end in {@code .RetentionPurgeJob} is filtered out, and the default filters still
   * want {@code @Component}, so the job is registered here only if the application would find it.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ComponentScan(
      basePackageClasses = BranchBookApplication.class,
      excludeFilters = @Filter(type = FilterType.REGEX, pattern = ".*(?<!\\.RetentionPurgeJob)"))
  static class ScannedAndScheduledLikeTheApplication {}
}
