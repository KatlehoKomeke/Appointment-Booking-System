/**
 * Booking's use cases and the ports they need: booking an appointment ({@link
 * za.co.fynbos.branchbook.booking.application.BookAppointmentHandler}), opening it again ({@link
 * za.co.fynbos.branchbook.booking.application.LookupAppointmentHandler}) and cancelling it ({@link
 * za.co.fynbos.branchbook.booking.application.CancelAppointmentHandler}). Opening and cancelling
 * both prove the reference and the last four digits through one check, counted by the {@link
 * za.co.fynbos.branchbook.booking.application.LookupAttemptLimiter}.
 *
 * <p>The ports to other modules are declared here and implemented there, so dependencies run
 * towards booking: {@link za.co.fynbos.branchbook.booking.application.ConfirmationOutbox} writes
 * the confirmation inside the booking transaction, and the cancellation, in place of every
 * confirmation, inside the cancel transaction that then deletes the appointment; {@link
 * za.co.fynbos.branchbook.booking.application.AppointmentNotifications} reads an appointment's
 * messages back, and {@link za.co.fynbos.branchbook.booking.application.NotificationEraser} deletes
 * them when the appointment itself is deleted. All three are implemented by the notification
 * module. The other way round, {@link
 * za.co.fynbos.branchbook.booking.application.AppointmentDirectory} names appointments for the
 * simulated inbox and tells the dispatcher and the retry of a message whether an appointment is
 * cancelled or has started, so no other module reads booking's tables.
 *
 * <p>{@link za.co.fynbos.branchbook.booking.application.RetentionPurge} deletes appointments once
 * they have ended, plus {@link
 * za.co.fynbos.branchbook.booking.application.RetentionProperties#afterEnd()}, with their messages
 * and keys, in small transactions, so the hosted demo keeps a booking until it is over.
 *
 * <p>{@link za.co.fynbos.branchbook.booking.application.BookingOutcome} is the one place that
 * decides how a booking request ended, for the {@code appointments_booked_total} metric (FR-30).
 */
package za.co.fynbos.branchbook.booking.application;
