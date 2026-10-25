package za.co.fynbos.branchbook.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fynbos.branchbook.scheduling.application.BookedIntervalsPort;
import za.co.fynbos.branchbook.scheduling.domain.BookedInterval;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.SeedData;

/**
 * The read that tells scheduling which seats are taken, at the edges of its period. Its lower bound
 * on the start, which keeps it from reading every past appointment, must never drop an appointment
 * that overlaps the period.
 */
@IntegrationTest
class JdbcBookedIntervalsIT {

  /** Tuesday 27 October 2026, midnight to midnight in Johannesburg. */
  private static final Instant FROM = Instant.parse("2026-10-26T22:00:00Z");

  private static final Instant TO = Instant.parse("2026-10-27T22:00:00Z");

  @Autowired private BookedIntervalsPort bookedIntervals;

  @Autowired private JdbcTemplate jdbc;

  @AfterEach
  void removeTheTestAppointments() {
    jdbc.update("DELETE FROM appointments WHERE reference LIKE 'APT-JDBC-%'");
  }

  /**
   * An appointment of the longest possible length that started just before the period still
   * overlaps it, so it is returned. One that ends as the period begins, or starts as it ends, does
   * not overlap it, because both are half-open.
   */
  @Test
  void findsTheLongestPossibleAppointmentThatStartedBeforeThePeriod() {
    Instant longestThatOverlaps =
        FROM.minus(JdbcBookedIntervals.LONGEST_APPOINTMENT).plusSeconds(60);
    insert(
        "APT-JDBC-AAA2",
        1,
        longestThatOverlaps,
        longestThatOverlaps.plus(JdbcBookedIntervals.LONGEST_APPOINTMENT),
        "+27825550131");
    insert(
        "APT-JDBC-AAA3",
        2,
        FROM.minus(JdbcBookedIntervals.LONGEST_APPOINTMENT),
        FROM,
        "+27825550132");
    insert("APT-JDBC-AAA4", 3, TO, TO.plusSeconds(45 * 60), "+27825550133");

    assertThat(bookedIntervals.activeBetween(SeedData.SANDTON, SeedData.HOME_LOAN, FROM, TO))
        .containsExactly(
            new BookedInterval(
                1,
                longestThatOverlaps,
                longestThatOverlaps.plus(JdbcBookedIntervals.LONGEST_APPOINTMENT)));
  }

  /** The bound is only safe while no service can last longer than the schema allows. */
  @Test
  void longestAppointmentIsTheSchemasLimitOnServiceDurations() {
    String check =
        jdbc.queryForObject(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conname = 'services_duration_grid'",
            String.class);

    assertThat(check)
        .contains(
            "(duration_minutes <= " + JdbcBookedIntervals.LONGEST_APPOINTMENT.toMinutes() + ")");
  }

  /** Inserts a confirmed Sandton home-loan appointment with raw SQL, past the booking flow. */
  private void insert(String reference, int seat, Instant startsAt, Instant endsAt, String mobile) {
    jdbc.update(
        """
        INSERT INTO appointments (id, reference, branch_id, service_id, seat_no, starts_at, ends_at,
                                  status, customer_first_name, customer_last_name, customer_mobile)
        VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'CONFIRMED', 'Thandi', 'Mokoena', ?)""",
        reference,
        SeedData.SANDTON,
        SeedData.HOME_LOAN,
        seat,
        OffsetDateTime.ofInstant(startsAt, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(endsAt, ZoneOffset.UTC),
        mobile);
  }
}
