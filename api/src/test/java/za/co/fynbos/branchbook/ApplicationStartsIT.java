package za.co.fynbos.branchbook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The day-0 spike, kept as a regression test: the application starts on Java 25, Flyway migrates a
 * real PostgreSQL 17, and the probes that Docker and the load balancer rely on answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApplicationStartsIT {

  @Autowired private JdbcTemplate jdbc;

  @Autowired private MockMvcTester mvc;

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

  @Test
  void healthRevealsNoComponentDetails() {
    assertThat(mvc.get().uri("/actuator/health"))
        .hasStatusOk()
        .bodyJson()
        .doesNotHavePath("$.components");
  }
}
