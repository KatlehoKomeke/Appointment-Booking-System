/**
 * Notification's use cases and settings.
 *
 * <ul>
 *   <li>{@link za.co.fynbos.branchbook.notification.application.NotificationOutbox} writes the
 *       confirmation SMS inside the booking transaction, and the cancellation SMS inside the cancel
 *       transaction after superseding a confirmation still PENDING or FAILED, implementing
 *       booking's {@code ConfirmationOutbox} port.
 *   <li>{@link za.co.fynbos.branchbook.notification.application.MessageQueries} reads an
 *       appointment's messages back with the recipient masked, implementing booking's {@code
 *       AppointmentNotifications} port.
 *   <li>{@link za.co.fynbos.branchbook.notification.application.NotificationDispatcher} delivers
 *       due PENDING messages through the {@code MessageGateway} port, one transaction each, with
 *       backoff and a final FAILED, and never sends the confirmation of a cancelled appointment.
 *   <li>{@code OutboxPendingGauge} holds the {@code outbox.pending} gauge, which the dispatcher
 *       refreshes with one count of the PENDING messages at the end of every tick.
 *   <li>{@link za.co.fynbos.branchbook.notification.application.InboxQueries} reads the simulated
 *       inbox: the newest messages of every appointment, recipients redacted.
 *   <li>{@link za.co.fynbos.branchbook.notification.application.RetryMessageHandler} makes a failed
 *       message due again, by the same rule that sets the inbox's {@code retryable} flag.
 *   <li>{@link za.co.fynbos.branchbook.notification.application.MessageEraser} deletes the messages
 *       of appointments booking's retention purge is deleting, in the same transaction,
 *       implementing booking's {@code NotificationEraser} port.
 *   <li>{@link za.co.fynbos.branchbook.notification.application.NotificationProperties} refuses at
 *       start-up a public base URL that could push a confirmation or a cancellation over one SMS
 *       segment.
 *   <li>{@link za.co.fynbos.branchbook.notification.application.SmsProperties} chooses the gateway,
 *       simulated or AWS, and sets the per-recipient daily cap.
 * </ul>
 *
 * <p>Whatever this module needs to know about an appointment it asks booking's {@code
 * AppointmentDirectory}, so it never reads booking's tables.
 */
package za.co.fynbos.branchbook.notification.application;
