package za.co.fynbos.branchbook.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fynbos.branchbook.booking.application.CustomerOverlapException;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.SeedData;

/**
 * BR-9: the translator finds the constraint a failure broke by its name, which the driver still
 * reports while the server's detail line, which would quote the number, is switched off.
 */
@IntegrationTest
class ConstraintNamesIT {

  @Autowired private JdbcTemplate jdbc;

  @Autowired private DataSource dataSource;

  @Test
  void everyMappedConstraintNameExistsInSchema() {
    List<String> names = jdbc.queryForList("SELECT conname FROM pg_constraint", String.class);

    assertThat(names).containsAll(ConstraintViolationTranslator.MAPPED_CONSTRAINTS);
  }

  @Test
  void translatorReadsConstraintNameWithServerDetailLoggingOff() throws Exception {
    PSQLException failure;
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.execute(insert("APT-CNXT-AAA2", SeedData.SANDTON));
        failure =
            catchThrowableOfType(
                PSQLException.class,
                () -> statement.execute(insert("APT-CNXT-AAA3", SeedData.BELLVILLE)));
      } finally {
        connection.rollback();
      }
    }

    assertThat(failure.getServerErrorMessage().getConstraint())
        .isEqualTo("appointments_customer_no_overlap");
    assertThat(failure.getMessage()).doesNotContain("825550185").doesNotContain("+27");
    assertThat(ConstraintViolationTranslator.translate(new IllegalStateException(failure)))
        .isInstanceOf(CustomerOverlapException.class);
  }

  private static String insert(String reference, java.util.UUID branch) {
    return """
        INSERT INTO appointments (id, reference, branch_id, service_id, seat_no, starts_at, ends_at,
                                  status, customer_first_name, customer_last_name, customer_mobile)
        VALUES (gen_random_uuid(), '%s', '%s', '%s', 1, '2026-10-27 09:00+02', '2026-10-27 09:15+02',
                'CONFIRMED', 'Nomvula', 'Dlamini', '+27825550185')"""
        .formatted(reference, branch, SeedData.FICA);
  }
}
