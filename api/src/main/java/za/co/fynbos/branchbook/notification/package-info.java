/**
 * The {@code notification} module: the confirmation and cancellation SMS, sent through a simulated
 * gateway or, where a deployment asks, through AWS End User Messaging SMS (ADR-0003).
 *
 * <p>It owns the {@code notification_messages} table. The booking transaction writes the
 * confirmation SMS, and the cancel transaction the cancellation SMS, as a PENDING row through the
 * port the booking module declares, which this module implements, so an appointment's change and
 * its message commit or roll back together and a message can never be lost or sent for a change
 * that did not happen. In the same way, booking's retention purge deletes an appointment's messages
 * through a port of booking's that this module implements, in the transaction that deletes the
 * appointment. It depends on {@code booking} for those ports and on {@code shared}; {@code booking}
 * never depends on it (docs/architecture.md, section 3).
 *
 * <p>Built: the message templates, the check that every message fits one SMS, the outbox writer,
 * the read of an appointment's messages, and the simulated inbox ({@code api}), which names each
 * message's appointment through booking's {@code AppointmentDirectory}; from M5, the dispatcher
 * that delivers PENDING messages through the simulated SMS gateway, with retries and a final FAILED
 * (BR-17); and from M6, the cancellation SMS, the retry of a failed message from the inbox (FR-18),
 * and the {@code outbox_pending} gauge the dispatcher refreshes on every tick (FR-30). Since then:
 * the real gateway, AWS End User Messaging SMS, where {@code branchbook.sms.gateway} is {@code
 * aws}, with the published demo block always simulated; the per-recipient daily cap (BR-17); and a
 * cancel that deletes the booking (V6): the cancellation SMS replaces every confirmation, so none
 * is delivered after it (BR-24), and is the only message that outlives its appointment, until the
 * dispatcher deletes it once it is final (BR-14).
 */
package za.co.fynbos.branchbook.notification;
