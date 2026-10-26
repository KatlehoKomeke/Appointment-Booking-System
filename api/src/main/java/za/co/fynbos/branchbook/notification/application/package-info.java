/**
 * Notification's use cases and settings.
 *
 * <ul>
 *   <li>{@link za.co.fynbos.branchbook.notification.application.NotificationOutbox} writes the
 *       confirmation SMS inside the booking transaction, implementing booking's {@code
 *       ConfirmationOutbox} port.
 *   <li>{@link za.co.fynbos.branchbook.notification.application.MessageQueries} reads an
 *       appointment's messages back with the recipient masked, implementing booking's {@code
 *       AppointmentNotifications} port.
 *   <li>{@link za.co.fynbos.branchbook.notification.application.NotificationDispatcher} delivers
 *       due PENDING messages through the {@code MessageGateway} port, one transaction each, with
 *       backoff and a final FAILED.
 *   <li>{@link za.co.fynbos.branchbook.notification.application.InboxQueries} reads the simulated
 *       inbox: the newest messages of every appointment, recipients redacted.
 *   <li>{@link za.co.fynbos.branchbook.notification.application.MessageEraser} deletes the messages
 *       of appointments booking's retention purge is deleting, in the same transaction,
 *       implementing booking's {@code NotificationEraser} port.
 *   <li>{@link za.co.fynbos.branchbook.notification.application.NotificationProperties} refuses at
 *       start-up a public base URL that could push a confirmation over one SMS segment.
 * </ul>
 */
package za.co.fynbos.branchbook.notification.application;
