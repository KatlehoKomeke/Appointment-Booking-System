package za.co.fynbos.branchbook.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A customer's appointment at a branch: the aggregate the booking module owns, and the only JPA
 * entity in the application (table {@code appointments}).
 *
 * <p>Every other table is read and written with plain SQL, so Hibernate's start-up schema check
 * ({@code ddl-auto=validate}) covers exactly this mapping. The branch and service are kept as ids,
 * never as object graphs, because the catalogue belongs to another module.
 *
 * <p>The rules that protect an appointment live in the database as well as here: no seat is
 * double-booked and no mobile number is in two places at once, whatever code writes the row (BR-8,
 * BR-9; see {@code V2__schema.sql}). The booking transaction creates an appointment with {@link
 * #confirm}, which computes the end from the service's duration, so a caller can never choose it
 * (BR-2). Cancellation arrives with the optional cancel endpoint.
 */
@Entity
@Table(name = "appointments")
public class Appointment {

  @Id private UUID id;

  @Column(name = "reference", nullable = false, updatable = false)
  private String reference;

  @Column(name = "branch_id", nullable = false, updatable = false)
  private UUID branchId;

  @Column(name = "service_id", nullable = false, updatable = false)
  private UUID serviceId;

  /** The seat (1 to the service's capacity) this appointment occupies while it is active. */
  @Column(name = "seat_no", nullable = false, updatable = false)
  private Short seatNo;

  @Column(name = "starts_at", nullable = false, updatable = false)
  private Instant startsAt;

  /** Always computed from the start and the service's duration, never accepted from a caller. */
  @Column(name = "ends_at", nullable = false, updatable = false)
  private Instant endsAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private AppointmentStatus status;

  @Embedded private CustomerDetails customer;

  @Column(name = "cancelled_at")
  private @Nullable Instant cancelledAt;

  @Column(name = "checked_in_at")
  private @Nullable Instant checkedInAt;

  @Column(name = "completed_at")
  private @Nullable Instant completedAt;

  @Column(name = "no_show_at")
  private @Nullable Instant noShowAt;

  /**
   * Optimistic locking: two concurrent changes to one appointment cannot both win. Null until the
   * first insert, which is how a new appointment is told apart from a loaded one.
   */
  @Version
  @Column(name = "version", nullable = false)
  private @Nullable Integer version;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** The highest seat number the database accepts ({@code appointments_seat_no_check}). */
  static final int MAX_SEAT = 20;

  /** For JPA only. Appointments are created by the booking transaction. */
  protected Appointment() {}

  /** Sets every field of a new appointment; see {@link #confirm}. */
  private Appointment(
      UUID id,
      AppointmentReference reference,
      UUID branchId,
      UUID serviceId,
      int seatNo,
      Instant startsAt,
      Instant endsAt,
      CustomerDetails customer,
      Instant now) {
    this.id = id;
    this.reference = reference.value();
    this.branchId = branchId;
    this.serviceId = serviceId;
    this.seatNo = (short) seatNo;
    this.startsAt = startsAt;
    this.endsAt = endsAt;
    this.status = AppointmentStatus.CONFIRMED;
    this.customer = customer;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /**
   * Creates a confirmed appointment, ready to be inserted. Its end is the start plus the service's
   * duration, and its version stays null until the insert, so it is persisted, never merged.
   *
   * @param reference the reference the customer will use to find it
   * @param branchId the branch it is at
   * @param serviceId the service it is for
   * @param duration how long the service takes
   * @param seatNo the seat it occupies, from 1 to the service's capacity
   * @param startsAt when it starts; already checked against the slot rules
   * @param customer the customer's details
   * @param now the current instant, which becomes its creation time
   * @return the new appointment
   * @throws IllegalArgumentException if the seat is outside 1 to 20 or the duration is not positive
   */
  public static Appointment confirm(
      AppointmentReference reference,
      UUID branchId,
      UUID serviceId,
      Duration duration,
      int seatNo,
      Instant startsAt,
      CustomerDetails customer,
      Instant now) {
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(branchId, "branchId");
    Objects.requireNonNull(serviceId, "serviceId");
    Objects.requireNonNull(startsAt, "startsAt");
    Objects.requireNonNull(customer, "customer");
    Objects.requireNonNull(now, "now");
    if (seatNo < 1 || seatNo > MAX_SEAT) {
      throw new IllegalArgumentException("A seat is numbered from 1 to " + MAX_SEAT);
    }
    if (duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException("A service takes some time");
    }
    return new Appointment(
        UUID.randomUUID(),
        reference,
        branchId,
        serviceId,
        seatNo,
        startsAt,
        startsAt.plus(duration),
        customer,
        now);
  }

  /**
   * Tells whether the customer may still cancel: only a confirmed appointment that has not started
   * (BR-25). A past appointment stays confirmed but can no longer be cancelled.
   *
   * @param now the current instant
   * @return {@code true} if the appointment is confirmed and starts after {@code now}
   */
  public boolean canCancel(Instant now) {
    return status == AppointmentStatus.CONFIRMED && now.isBefore(startsAt);
  }

  /**
   * Returns the appointment's id.
   *
   * @return the id
   */
  public UUID id() {
    return id;
  }

  /**
   * Returns the reference the customer uses to find the appointment.
   *
   * @return for example {@code APT-7K3M-9Q2X}
   */
  public String reference() {
    return reference;
  }

  /**
   * Returns the branch the appointment is at.
   *
   * @return the branch's id
   */
  public UUID branchId() {
    return branchId;
  }

  /**
   * Returns the service the appointment is for.
   *
   * @return the service's id
   */
  public UUID serviceId() {
    return serviceId;
  }

  /**
   * Returns when the appointment starts.
   *
   * @return the start instant
   */
  public Instant startsAt() {
    return startsAt;
  }

  /**
   * Returns when the appointment ends.
   *
   * @return the end instant: the start plus the service's duration
   */
  public Instant endsAt() {
    return endsAt;
  }

  /**
   * Returns where the appointment is in its lifecycle.
   *
   * @return the status
   */
  public AppointmentStatus status() {
    return status;
  }

  /**
   * Returns the seat the appointment occupies while it is active.
   *
   * @return the seat, from 1 to the service's capacity
   */
  public int seatNo() {
    return seatNo;
  }

  /**
   * Returns the customer's details. They are personal data: show the name only to a caller who
   * booked or proved the appointment, and the mobile number only masked or redacted.
   *
   * @return the customer's name and mobile number
   */
  public CustomerDetails customer() {
    return customer;
  }

  /**
   * Returns the customer's mobile number. It is personal data: show it masked or redacted.
   *
   * @return the number
   */
  public MobileNumber mobile() {
    return MobileNumber.ofE164(customer.mobile());
  }

  /**
   * Returns when the appointment was cancelled.
   *
   * @return the instant, or {@code null} if it has not been cancelled
   */
  public @Nullable Instant cancelledAt() {
    return cancelledAt;
  }

  /**
   * Returns when the appointment was booked.
   *
   * @return the creation instant
   */
  public Instant createdAt() {
    return createdAt;
  }
}
