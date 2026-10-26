/**
 * The {@code booking} module: appointments, the booking transaction, lookup and cancellation.
 *
 * <p>It owns the {@code appointments} and {@code idempotency_keys} tables. It depends on {@code
 * catalogue} (which days and hours a branch trades), {@code scheduling} (which slots exist) and
 * {@code shared}, and never on {@code notification}: it declares the ports through which the
 * confirmation and the cancellation are written and an appointment's messages are erased, and the
 * notification module implements them (docs/architecture.md, section 3).
 *
 * <p>Built: booking an appointment in one transaction that also writes its confirmation SMS ({@code
 * application.BookAppointmentHandler}; docs/architecture.md, section 4), opening it again with its
 * reference and the last four digits of the number, behind a limit on wrong tries ({@code
 * application.LookupAppointmentHandler}), cancelling it the same way before it starts, in one
 * transaction that also supersedes a confirmation still PENDING or FAILED and writes the
 * cancellation SMS ({@code application.CancelAppointmentHandler}, Tier 2), the read that tells
 * scheduling which seats active appointments hold ({@code infrastructure}, implementing
 * scheduling's port), and the retention purge that deletes bookings older than {@code
 * branchbook.retention.max-age} for the hosted demo ({@code application.RetentionPurge}, run hourly
 * by {@code infrastructure.RetentionPurgeJob} only while {@code branchbook.retention.enabled} is
 * true).
 */
package za.co.fynbos.branchbook.booking;
