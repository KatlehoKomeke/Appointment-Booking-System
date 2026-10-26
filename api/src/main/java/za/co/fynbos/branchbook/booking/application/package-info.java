/**
 * Booking's use cases and the ports they need.
 *
 * <p>The ports to other modules are declared here and implemented there, so dependencies run
 * towards booking: {@link za.co.fynbos.branchbook.booking.application.ConfirmationOutbox} writes
 * the confirmation inside the booking transaction, {@link
 * za.co.fynbos.branchbook.booking.application.AppointmentNotifications} reads an appointment's
 * messages back, and {@link za.co.fynbos.branchbook.booking.application.NotificationEraser} deletes
 * them when the appointment itself is deleted. All three are implemented by the notification
 * module. The other way round, {@link
 * za.co.fynbos.branchbook.booking.application.AppointmentDirectory} names appointments for the
 * simulated inbox, so no other module reads booking's tables.
 *
 * <p>{@link za.co.fynbos.branchbook.booking.application.RetentionPurge} deletes appointments older
 * than {@link za.co.fynbos.branchbook.booking.application.RetentionProperties#maxAge()}, with their
 * messages and keys, in small transactions, for the hosted demo's 48-hour promise.
 */
package za.co.fynbos.branchbook.booking.application;
