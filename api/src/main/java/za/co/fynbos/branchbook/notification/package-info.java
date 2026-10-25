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
 * <p>Built so far: the message templates and the check that the confirmation fits one SMS. The
 * outbox writer arrives with the booking transaction, and the dispatcher that delivers PENDING
 * messages through a simulated gateway in milestone M5.
 */
package za.co.fynbos.branchbook.notification;
