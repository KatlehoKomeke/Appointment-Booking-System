/**
 * The booking domain: the {@link za.co.fynbos.branchbook.booking.domain.Appointment} aggregate, its
 * {@linkplain za.co.fynbos.branchbook.booking.domain.AppointmentStatus lifecycle} and the {@link
 * za.co.fynbos.branchbook.booking.domain.CancelOutcome outcome} of cancelling it, the customer's
 * details, and the values a booking is made of: {@link
 * za.co.fynbos.branchbook.booking.domain.MobileNumber}, {@link
 * za.co.fynbos.branchbook.booking.domain.CustomerName}, {@link
 * za.co.fynbos.branchbook.booking.domain.AppointmentReference} and {@link
 * za.co.fynbos.branchbook.booking.domain.IdempotencyKey}. Each value parses what a customer or a
 * client sends and refuses anything the database's checks would reject.
 *
 * <p>Plain Java with no Spring dependency. The JPA annotations on {@code Appointment} are the one
 * allowed exception, because it is the only entity (docs/architecture.md, section 3). Mobile
 * numbers are parsed with libphonenumber.
 */
package za.co.fynbos.branchbook.booking.domain;
