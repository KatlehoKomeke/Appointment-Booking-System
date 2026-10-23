package za.co.fynbos.branchbook.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
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
 * BR-9; see {@code V2__schema.sql}). The booking transaction that creates appointments, and the
 * cancellation that changes them, arrive with the booking milestone; until then the mapping itself
 * is what is proven, by the application starting against the real schema.
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

  /** For JPA only. Appointments are created by the booking transaction. */
  protected Appointment() {}

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
   * Returns the customer's details. They are personal data: mask them before showing them.
   *
   * @return the customer's name and mobile number
   */
  public CustomerDetails customer() {
    return customer;
  }
}
