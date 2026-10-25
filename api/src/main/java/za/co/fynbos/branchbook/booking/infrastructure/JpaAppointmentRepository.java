package za.co.fynbos.branchbook.booking.infrastructure;

import static za.co.fynbos.branchbook.booking.infrastructure.ConstraintViolationTranslator.translated;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import za.co.fynbos.branchbook.booking.application.AppointmentRepository;
import za.co.fynbos.branchbook.booking.domain.Appointment;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;
import za.co.fynbos.branchbook.booking.domain.AppointmentStatus;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;

/**
 * Stores appointments: inserts through JPA, the only entity, and the booking transaction's
 * questions as plain SQL.
 *
 * <p>Every query that depends on time binds the instant from the caller and never calls the
 * database's {@code now()} (BR-20). Active means {@link AppointmentStatus#ACTIVE_SQL}, so
 * cancelled, completed and no-show appointments never block a booking. The seat query reads one day
 * of {@code ix_appointments_slot_lookup}, bounded below by the longest possible appointment, like
 * {@link JdbcBookedIntervals}.
 */
@Repository
class JpaAppointmentRepository implements AppointmentRepository {

  private static final String LOWEST_FREE_SEAT =
      """
      SELECT s.seat FROM generate_series(1, :capacity) AS s(seat)
      WHERE NOT EXISTS (
          SELECT 1 FROM appointments a
          WHERE a.branch_id = :branchId AND a.service_id = :serviceId AND a.seat_no = s.seat
            AND a.status IN %s
            AND a.starts_at < :endsAt AND a.ends_at > :startsAt
            AND a.starts_at > :earliestStart)
      ORDER BY s.seat
      LIMIT 1"""
          .formatted(AppointmentStatus.ACTIVE_SQL);

  private static final String UPCOMING_FOR_SERVICE =
      """
      SELECT EXISTS (
          SELECT 1 FROM appointments
          WHERE customer_mobile = :mobile AND service_id = :serviceId
            AND status IN %s AND ends_at > :now)"""
          .formatted(AppointmentStatus.ACTIVE_SQL);

  private static final String OVERLAPPING =
      """
      SELECT EXISTS (
          SELECT 1 FROM appointments
          WHERE customer_mobile = :mobile
            AND status IN %s AND starts_at < :endsAt AND ends_at > :startsAt)"""
          .formatted(AppointmentStatus.ACTIVE_SQL);

  private final EntityManager entityManager;
  private final JdbcClient jdbc;

  JpaAppointmentRepository(EntityManager entityManager, JdbcClient jdbc) {
    this.entityManager = entityManager;
    this.jdbc = jdbc;
  }

  @Override
  public void insert(Appointment appointment) {
    translated(
        () -> {
          entityManager.persist(appointment);
          entityManager.flush();
        });
  }

  @Override
  public Optional<Appointment> findById(UUID id) {
    return Optional.ofNullable(entityManager.find(Appointment.class, id));
  }

  @Override
  public Optional<Appointment> findByReference(AppointmentReference reference) {
    return entityManager
        .createQuery(
            "select a from Appointment a where a.reference = :reference", Appointment.class)
        .setParameter("reference", reference.value())
        .getResultStream()
        .findFirst();
  }

  @Override
  public OptionalInt findLowestFreeSeat(
      UUID branchId, UUID serviceId, Instant startsAt, Instant endsAt, int capacity) {
    Optional<Integer> seat =
        translated(
            () ->
                jdbc.sql(LOWEST_FREE_SEAT)
                    .param("capacity", capacity)
                    .param("branchId", branchId)
                    .param("serviceId", serviceId)
                    .param("startsAt", utc(startsAt))
                    .param("endsAt", utc(endsAt))
                    .param(
                        "earliestStart",
                        utc(startsAt.minus(JdbcBookedIntervals.LONGEST_APPOINTMENT)))
                    .query(Integer.class)
                    .optional());
    return seat.map(OptionalInt::of).orElseGet(OptionalInt::empty);
  }

  @Override
  public boolean hasUpcomingActiveForService(MobileNumber mobile, UUID serviceId, Instant now) {
    return translated(
        () ->
            jdbc.sql(UPCOMING_FOR_SERVICE)
                .param("mobile", mobile.e164())
                .param("serviceId", serviceId)
                .param("now", utc(now))
                .query(Boolean.class)
                .single());
  }

  @Override
  public boolean hasOverlappingActive(MobileNumber mobile, Instant startsAt, Instant endsAt) {
    return translated(
        () ->
            jdbc.sql(OVERLAPPING)
                .param("mobile", mobile.e164())
                .param("startsAt", utc(startsAt))
                .param("endsAt", utc(endsAt))
                .query(Boolean.class)
                .single());
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
