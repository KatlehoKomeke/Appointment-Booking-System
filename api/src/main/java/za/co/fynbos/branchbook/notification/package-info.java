/**
 * The {@code notification} module: the simulated confirmation (ADR-0003).
 *
 * <p>It owns the {@code notification_messages} table. The booking transaction writes the
 * confirmation SMS as a PENDING row through the port the booking module declares, which this module
 * implements, so a booking and its confirmation commit or roll back together and a confirmation can
 * never be lost or sent for a booking that did not happen. It depends on {@code booking} for that
 * port and on {@code shared}; {@code booking} never depends on it (docs/architecture.md, section
 * 3).
 *
 * <p>Built: the message templates, the check that the confirmation fits one SMS, the outbox writer
 * and the read of an appointment's messages. The dispatcher that delivers PENDING messages through
 * a simulated gateway arrives in milestone M5.
 */
package za.co.fynbos.branchbook.notification;
