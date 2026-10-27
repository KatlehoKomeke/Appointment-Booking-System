package za.co.fynbos.branchbook.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fynbos.branchbook.booking.application.LookupAttemptStore;
import za.co.fynbos.branchbook.booking.application.Window;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;

/**
 * BR-13, V8: the hourly purge deletes the lookup windows that have closed as of the clock, which
 * the limiter already ignores, and keeps the open ones. The windows are written through the store;
 * the job is called directly, its schedule is Spring's.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class LookupAttemptPurgeJobIT {

  @Autowired private LookupAttemptPurgeJob job;

  @Autowired private LookupAttemptStore store;

  @Autowired private MutableClock clock;

  @Autowired private JdbcTemplate jdbc;

  @Test
  void purgeRemovesWindowsThatHaveClosedAndKeepsOpenOnes() {
    Instant now = clock.instant();
    assertThat(store.insert(window("APT-AAAA-AAA2", now.minusSeconds(1)))).isTrue();
    assertThat(store.insert(window("APT-BBBB-BBB2", now))).isTrue();
    assertThat(store.insert(window("APT-CCCC-CCC2", now.plusSeconds(1)))).isTrue();

    job.purge();

    assertThat(jdbc.queryForList("SELECT reference FROM lookup_attempts", String.class))
        .as("a window that closes as the purge runs is closed, as the limiter sees it")
        .containsExactly("APT-CCCC-CCC2");
  }

  /** A window of one wrong lookup that closes at the given instant, an hour after it opened. */
  private static Window window(String reference, Instant closesAt) {
    Instant opensAt = closesAt.minus(Duration.ofHours(1));
    return new Window(reference, opensAt, closesAt, opensAt.plusSeconds(30), 1, 0);
  }
}
