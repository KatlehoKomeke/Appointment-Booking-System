package za.co.fynbos.branchbook.booking.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import za.co.fynbos.branchbook.booking.domain.AppointmentStatus;
import za.co.fynbos.branchbook.scheduling.application.BookedIntervalsPort;
import za.co.fynbos.branchbook.scheduling.domain.BookedInterval;

/**
 * Tells scheduling which seats are taken, by reading the active appointments in the booking
 * module's own table. Only the seat and the times leave this class; no customer detail does.
 *
 * <p>The query reads the partial index {@code ix_appointments_slot_lookup} (branch, service, start)
 * as a range of one day. The overlap test alone, {@code starts_at < to AND ends_at > from}, bounds
 * the start only from above, so it would read every past appointment, and past appointments stay
 * active in this version (BR-25). A start more than {@link #LONGEST_APPOINTMENT} before the period
 * cannot overlap it, which gives the lower bound. The query binds the period from the caller and
 * never calls the database's {@code now()}, so tests control time (BR-20).
 */
@Repository
class JdbcBookedIntervals implements BookedIntervalsPort {

  /**
   * The longest an appointment can last: the upper limit of {@code services_duration_grid} in
   * V2__schema.sql. An appointment's end is its start plus its service's duration.
   * JdbcBookedIntervalsIT fails if the schema's limit changes without this.
   */
  static final Duration LONGEST_APPOINTMENT = Duration.ofMinutes(120);

  private static final String ACTIVE_BETWEEN =
      """
      SELECT seat_no, starts_at, ends_at FROM appointments
      WHERE branch_id = :branchId AND service_id = :serviceId
        AND status IN %s
        AND starts_at < :to AND ends_at > :from
        AND starts_at > :earliestStart
      ORDER BY starts_at, seat_no"""
          .formatted(AppointmentStatus.ACTIVE_SQL);

  private final JdbcClient jdbc;

  JdbcBookedIntervals(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<BookedInterval> activeBetween(
      UUID branchId, UUID serviceId, Instant from, Instant to) {
    return jdbc.sql(ACTIVE_BETWEEN)
        .param("branchId", branchId)
        .param("serviceId", serviceId)
        .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
        .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
        .param(
            "earliestStart",
            OffsetDateTime.ofInstant(from.minus(LONGEST_APPOINTMENT), ZoneOffset.UTC))
        .query(
            (rs, row) ->
                new BookedInterval(
                    rs.getInt("seat_no"),
                    rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("ends_at", OffsetDateTime.class).toInstant()))
        .list();
  }
}
