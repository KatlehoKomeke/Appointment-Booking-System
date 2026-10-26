/**
 * Notification's adapters:
 *
 * <ul>
 *   <li>{@link
 *       za.co.fynbos.branchbook.notification.infrastructure.JdbcNotificationMessageRepository}
 *       stores, reads, claims and marks {@code notification_messages} with plain SQL.
 *   <li>{@link za.co.fynbos.branchbook.notification.infrastructure.SimulatedSmsGateway} is the only
 *       {@code MessageGateway}: nothing is sent, and numbers ending in 0000 are refused.
 *   <li>{@link za.co.fynbos.branchbook.notification.infrastructure.DispatcherLoop} runs the
 *       dispatcher every 2 seconds on a thread of its own.
 * </ul>
 */
package za.co.fynbos.branchbook.notification.infrastructure;
