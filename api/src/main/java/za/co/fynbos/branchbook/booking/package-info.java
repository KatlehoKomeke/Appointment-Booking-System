/**
 * The {@code booking} module: appointments, the booking transaction, lookup and cancellation.
 *
 * <p>It owns the {@code appointments} and {@code idempotency_keys} tables. It depends on {@code
 * catalogue} (which days and hours a branch trades), {@code scheduling} (which slots exist) and
 * {@code shared}, and never on {@code notification}: it declares the port through which the
 * confirmation is written, and the notification module implements it (docs/architecture.md, section
 * 3).
 *
 * <p>Built so far: the appointment aggregate and its schema. The booking transaction follows in
 * milestone M3.
 */
package za.co.fynbos.branchbook.booking;
