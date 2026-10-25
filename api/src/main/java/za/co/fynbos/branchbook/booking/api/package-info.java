/**
 * Booking's HTTP API: {@code POST /api/v1/appointments} to book, and {@code GET
 * /api/v1/appointments/{reference}} with {@code X-Mobile-Last4} to open an appointment again. The
 * controller only translates between HTTP and the application layer; the request is validated here,
 * and its values parsed into the booking's command.
 */
package za.co.fynbos.branchbook.booking.api;
