/**
 * The booking module's adapters over its own tables.
 *
 * <ul>
 *   <li>{@link za.co.fynbos.branchbook.booking.infrastructure.JpaAppointmentRepository}: inserts
 *       appointments through JPA and answers the booking transaction's questions with plain SQL.
 *   <li>{@link za.co.fynbos.branchbook.booking.infrastructure.JdbcBookingLocks}: the customer and
 *       seat locks.
 *   <li>{@link za.co.fynbos.branchbook.booking.infrastructure.JdbcIdempotencyKeyStore}: the {@code
 *       Idempotency-Key} rows, purged hourly by {@link
 *       za.co.fynbos.branchbook.booking.infrastructure.IdempotencyKeyPurgeJob}.
 *   <li>{@link za.co.fynbos.branchbook.booking.infrastructure.ConstraintViolationTranslator}: turns
 *       a broken constraint or a transaction conflict into the answer it means.
 *   <li>{@link za.co.fynbos.branchbook.booking.infrastructure.JdbcBookedIntervals}: the seats
 *       active appointments hold, for scheduling's port.
 *   <li>{@link za.co.fynbos.branchbook.booking.infrastructure.RetentionPurgeJob}: runs the
 *       retention purge every hour, and exists only while {@code branchbook.retention.enabled} is
 *       true.
 * </ul>
 *
 * <p>This is the only package that imports the PostgreSQL driver, to read a failure's SQLSTATE and
 * constraint name.
 */
package za.co.fynbos.branchbook.booking.infrastructure;
