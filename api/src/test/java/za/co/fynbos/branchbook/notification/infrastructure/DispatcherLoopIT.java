package za.co.fynbos.branchbook.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;

/**
 * BR-17: with the dispatcher switched on, as it is outside the tests, its own loop delivers a new
 * booking's SMS on its own thread, {@code outbox-1}, and Spring Boot's default scheduler, which
 * runs the key purge and the holiday check, is still there. This is the one test that waits for
 * real time: the loop runs every 100 ms here, and the test polls for at most 5 seconds. It builds
 * an application context of its own.
 */
@IntegrationTest
@ExtendWith({DatabaseReset.class, OutputCaptureExtension.class})
@TestPropertySource(
    properties = {"branchbook.dispatcher.enabled=true", "branchbook.dispatcher.interval=PT0.1S"})
class DispatcherLoopIT {

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private ApplicationContext context;

  @Test
  void theLoopDeliversOnItsOwnThreadAndLeavesBootsSchedulerInPlace(CapturedOutput output)
      throws InterruptedException {
    assertThat(book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0190")))
        .hasStatus(HttpStatus.CREATED);

    Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
    String status = "PENDING";
    while (!"SENT".equals(status) && Instant.now().isBefore(deadline)) {
      Thread.sleep(50);
      status = jdbc.queryForObject("SELECT status FROM notification_messages", String.class);
    }

    assertThat(status).isEqualTo("SENT");
    assertThat(output.getOut()).containsPattern("\\[\\s*outbox-1\\].*outbox\\.sent");
    assertThat(context.getBeanNamesForType(TaskScheduler.class))
        .as("Spring Boot's default scheduler is not replaced")
        .contains("taskScheduler");
  }
}
