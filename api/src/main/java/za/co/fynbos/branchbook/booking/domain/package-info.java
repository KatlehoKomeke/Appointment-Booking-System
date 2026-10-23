/**
 * The booking domain: the {@link za.co.fynbos.branchbook.booking.domain.Appointment} aggregate, its
 * {@linkplain za.co.fynbos.branchbook.booking.domain.AppointmentStatus lifecycle} and the
 * customer's details.
 *
 * <p>Plain Java with no Spring dependency. The JPA annotations on {@code Appointment} are the one
 * allowed exception, because it is the only entity (docs/architecture.md, section 3).
 */
package za.co.fynbos.branchbook.booking.domain;
