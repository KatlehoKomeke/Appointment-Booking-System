package za.co.fynbos.branchbook.booking.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The lifecycle of an appointment (BR-15).
 *
 * <pre>
 *   CONFIRMED  -&gt; CANCELLED    the customer cancels before the start
 *   CONFIRMED  -&gt; CHECKED_IN   the customer arrives at the branch
 *   CONFIRMED  -&gt; NO_SHOW      15 minutes after the start, nobody checked in
 *   CHECKED_IN -&gt; COMPLETED    the consultation ends
 * </pre>
 *
 * <p>CANCELLED, COMPLETED and NO_SHOW are final. Only cancellation is reachable through the API in
 * this version; the branch transitions are modelled so that branch check-in can be added on a
 * correct foundation. An appointment holds its seat while it is {@linkplain #ACTIVE active}.
 */
public enum AppointmentStatus {
  /** Booked; the seat is held. */
  CONFIRMED,
  /** The customer has arrived at the branch; the seat is still held. */
  CHECKED_IN,
  /** The consultation took place. Final. */
  COMPLETED,
  /** The customer cancelled; the seat is free again. Final. */
  CANCELLED,
  /** The customer did not arrive. Final. */
  NO_SHOW;

  /**
   * The statuses that hold a seat. The schema's partial predicates and exclusion constraints use
   * exactly these statuses; {@code SchemaConstraintIT} proves the two agree.
   */
  public static final Set<AppointmentStatus> ACTIVE =
      Collections.unmodifiableSet(EnumSet.of(CONFIRMED, CHECKED_IN));

  /**
   * {@link #ACTIVE} as an SQL list, for example {@code ('CONFIRMED', 'CHECKED_IN')}. Native SQL
   * spells the active statuses only through this constant.
   */
  public static final String ACTIVE_SQL =
      ACTIVE.stream()
          .map(status -> "'" + status.name() + "'")
          .collect(Collectors.joining(", ", "(", ")"));

  private static final Map<AppointmentStatus, Set<AppointmentStatus>> NEXT =
      Map.of(
          CONFIRMED, EnumSet.of(CANCELLED, CHECKED_IN, NO_SHOW),
          CHECKED_IN, EnumSet.of(COMPLETED),
          COMPLETED, EnumSet.noneOf(AppointmentStatus.class),
          CANCELLED, EnumSet.noneOf(AppointmentStatus.class),
          NO_SHOW, EnumSet.noneOf(AppointmentStatus.class));

  /**
   * Tells whether an appointment in this status may move to another.
   *
   * @param next the status to move to
   * @return {@code true} only for the four transitions in the class description
   */
  public boolean canTransitionTo(AppointmentStatus next) {
    return NEXT.get(this).contains(next);
  }

  /**
   * Tells whether an appointment in this status holds its seat.
   *
   * @return {@code true} for {@link #CONFIRMED} and {@link #CHECKED_IN}
   */
  public boolean isActive() {
    return ACTIVE.contains(this);
  }

  /**
   * Tells whether this status is final.
   *
   * @return {@code true} when no transition leaves this status
   */
  public boolean isFinal() {
    return NEXT.get(this).isEmpty();
  }
}
