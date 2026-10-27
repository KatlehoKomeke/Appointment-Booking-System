/**
 * Notification's adapters:
 *
 * <ul>
 *   <li>{@link
 *       za.co.fynbos.branchbook.notification.infrastructure.JdbcNotificationMessageRepository}
 *       stores, reads, claims and marks {@code notification_messages} with plain SQL.
 *   <li>{@link za.co.fynbos.branchbook.notification.infrastructure.SmsGatewayConfiguration} builds
 *       the one {@code MessageGateway} from {@code branchbook.sms}.
 *   <li>{@link za.co.fynbos.branchbook.notification.infrastructure.SimulatedSmsGateway} sends
 *       nothing, and refuses numbers ending in 0000: every number where the gateway is {@code
 *       simulated}, the default, and the published demo block where it is {@code aws}.
 *   <li>{@link za.co.fynbos.branchbook.notification.infrastructure.AwsSmsGateway} sends a real SMS
 *       through AWS End User Messaging SMS, and {@link
 *       za.co.fynbos.branchbook.notification.infrastructure.RoutingSmsGateway} hands it every
 *       number outside the demo block.
 *   <li>{@link za.co.fynbos.branchbook.notification.infrastructure.DispatcherLoop} runs the
 *       dispatcher every 2 seconds on a thread of its own.
 * </ul>
 */
package za.co.fynbos.branchbook.notification.infrastructure;
