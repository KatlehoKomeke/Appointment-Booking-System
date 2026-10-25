/**
 * The {@code booking} module: appointments, the booking transaction, lookup and cancellation.
 *
 * <p>It owns the {@code appointments} and {@code idempotency_keys} tables. It depends on {@code
 * catalogue} (which days and hours a branch trades), {@code scheduling} (which slots exist) and
 * {@code shared}, and never on {@code notification}: it declares the port through which the
 * confirmation is written, and the notification module implements it (docs/architecture.md, section
 * 3).
 *
 * <p>Built: booking an appointment in one transaction that also writes its confirmation SMS ({@code
 * application.BookAppointmentHandler}; docs/architecture.md, section 4), opening it again with its
 * reference and the last four digits of the number, behind a limit on wrong tries ({@code
 * application.LookupAppointmentHandler}), and the read that tells scheduling which seats active
 * appointments hold ({@code infrastructure}, implementing scheduling's port). Cancellation is Tier
 * 2.
 */
package za.co.fynbos.branchbook.booking;
