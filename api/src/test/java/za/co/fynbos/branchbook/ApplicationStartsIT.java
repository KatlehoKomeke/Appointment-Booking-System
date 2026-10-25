package za.co.fynbos.branchbook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;

/**
 * The day-0 spike, kept as a regression test: the application starts on Java 25, Flyway migrates a
 * real PostgreSQL 17, the probes that Docker and the load balancer rely on answer, and the daily
 * checks are scheduled.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ApplicationStartsIT {

  @Autowired private JdbcTemplate jdbc;

  @Autowired private MockMvcTester mvc;

  @Autowired private ScheduledTaskHolder scheduledTasks;

  @Autowired private ConfigurableApplicationContext context;

  @Autowired private MutableClock clock;

  @Test
  void runsOnPostgresql17() {
    Integer version =
        jdbc.queryForObject("SELECT current_setting('server_version_num')::int", Integer.class);

    assertThat(version).isBetween(170000, 179999);
  }

  @Test
  void migrationsEnableBtreeGistForTheDoubleBookingConstraints() {
    Integer installed =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist'", Integer.class);

    assertThat(installed).isEqualTo(1);
  }

  /**
   * Every database session gives up on a lock after 2 seconds and on a statement after 5, so a
   * booking stuck behind a busy seat answers 503 instead of holding a connection.
   */
  @Test
  void databaseSessionsGiveUpOnSlowLocksAndStatements() {
    assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("2s");
    assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("5s");
  }

  @Test
  void readinessProbeIsUpWhenTheDatabaseAnswers() {
    assertThat(mvc.get().uri("/readyz"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.status")
        .isEqualTo("UP");
  }

  @Test
  void livenessProbeIsUp() {
    assertThat(mvc.get().uri("/livez"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.status")
        .isEqualTo("UP");
  }

  /**
   * BR-3: the holiday-coverage check runs every day at 06:00 in Johannesburg, before the branches
   * open, whatever zone the server runs in. From 07:00 on Sunday 25 October the next run is 06:00
   * on Monday 26 October, which is 04:00 UTC.
   */
  @Test
  void holidayCoverageIsCheckedEveryMorning() {
    TriggerContext sundayMorning =
        new SimpleTriggerContext(
            Clock.fixed(Instant.parse("2026-10-25T05:00:00Z"), ZoneOffset.UTC));

    assertThat(scheduledTasks.getScheduledTasks())
        .map(ScheduledTask::getTask)
        .filteredOn(
            task ->
                task.toString().endsWith("HolidayCoverageCheck.warnIfBookingsOutrunTheHolidays"))
        .singleElement()
        .isInstanceOfSatisfying(
            CronTask.class,
            daily -> {
              assertThat(daily.getExpression()).isEqualTo("0 0 6 * * *");
              assertThat(daily.getTrigger().nextExecution(sundayMorning))
                  .isEqualTo(Instant.parse("2026-10-26T04:00:00Z"));
            });
  }

  /**
   * BR-3: the check also runs when the application is ready, on the application's clock. On 10
   * December 2027 the window reaches 8 January 2028, past the seeded holidays.
   */
  @Test
  void holidayCoverageIsCheckedWhenTheApplicationIsReady(CapturedOutput output) {
    clock.setInstant(Instant.parse("2027-12-10T06:00:00Z"));

    context.publishEvent(
        new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));

    assertThat(output)
        .contains("Public holidays are known up to 2027-12-31 but bookings open up to 2028-01-08");
  }

  @Test
  void healthRevealsNoComponentDetails() {
    assertThat(mvc.get().uri("/actuator/health"))
        .hasStatusOk()
        .bodyJson()
        .doesNotHavePath("$.components");
  }
}
