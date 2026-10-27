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
 *   <li>{@link za.co.fynbos.branchbook.booking.infrastructure.JdbcLookupAttemptStore}: the lookup
 *       limiter's counts in {@code lookup_attempts}, one locked row per reference, whose closed
 *       windows {@link za.co.fynbos.branchbook.booking.infrastructure.LookupAttemptPurgeJob}
 *       deletes hourly.
 *   <li>{@link za.co.fynbos.branchbook.booking.infrastructure.ConstraintViolationTranslator}: turns
 *       a broken constraint or a transaction conflict into the answer it means.
 *   <li>{@link za.co.fynbos.branchbook.booking.infrastructure.JdbcBookedIntervals}: the seats
 *       active appointments hold, for scheduling's port.
 *   <li>{@link za.co.fynbos.branchbook.booking.infrastructure.RetentionPurgeJob}: runs the
 *       retention purge every hour, and exists only while {@code branchbook.retention.enabled} is
 *       true.
 *   <li>{@code DemoDataLoader}: books three demo appointments through the real booking path when
 *       the application starts on an empty database, only while {@code
 *       branchbook.demo-data.enabled} is on, as it is in docker compose (FR-28).
 * </ul>
 *
 * <p>This is the only package that imports the PostgreSQL driver, to read a failure's SQLSTATE and
 * constraint name.
 */
package za.co.fynbos.branchbook.booking.infrastructure;
