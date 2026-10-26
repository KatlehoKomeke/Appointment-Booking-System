/**
 * Booking's HTTP API: {@code POST /api/v1/appointments} to book, {@code GET
 * /api/v1/appointments/{reference}} with {@code X-Mobile-Last4} to open an appointment again, and
 * {@code POST /api/v1/appointments/{reference}/cancel} with the same header to cancel it. The
 * controller only translates between HTTP and the application layer; the request is validated here,
 * and its values parsed into the booking's command.
 *
 * <p>{@link za.co.fynbos.branchbook.booking.api.BookingOutcomeMeter} counts each booking request
 * once its response is written, by the outcome {@link
 * za.co.fynbos.branchbook.booking.application.BookingOutcome} gives it: the {@code
 * appointments_booked_total} metric (FR-30).
 */
package za.co.fynbos.branchbook.booking.api;
