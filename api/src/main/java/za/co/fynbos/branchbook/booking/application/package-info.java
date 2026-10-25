/**
 * Booking's use cases and the ports they need.
 *
 * <p>The ports to other modules are declared here and implemented there, so dependencies run
 * towards booking: {@link za.co.fynbos.branchbook.booking.application.ConfirmationOutbox} writes
 * the confirmation inside the booking transaction, and {@link
 * za.co.fynbos.branchbook.booking.application.AppointmentNotifications} reads an appointment's
 * messages back. Both are implemented by the notification module.
 */
package za.co.fynbos.branchbook.booking.application;
