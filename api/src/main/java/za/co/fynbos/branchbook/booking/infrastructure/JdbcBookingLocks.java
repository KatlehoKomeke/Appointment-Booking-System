package za.co.fynbos.branchbook.booking.infrastructure;

import static za.co.fynbos.branchbook.booking.infrastructure.ConstraintViolationTranslator.translated;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import za.co.fynbos.branchbook.booking.application.BookingLocks;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.catalogue.application.ServiceNotOfferedAtBranchException;

/**
 * The booking transaction's two locks, both released at commit or rollback.
 *
 * <ul>
 *   <li>The customer lock is a transaction-scoped advisory lock on a 64-bit hash of the mobile
 *       number. Two numbers sharing a hash only queue behind each other, which is harmless.
 *   <li>The seat lock is {@code SELECT ... FOR UPDATE} on the {@code branch_services} row, which
 *       also reads the capacity.
 * </ul>
 *
 * <p>A lock not granted within the session's {@code lock_timeout} fails with SQLSTATE 55P03, which
 * the problem handler answers with {@code 503 TRY_AGAIN}.
 */
@Repository
class JdbcBookingLocks implements BookingLocks {

  private final JdbcClient jdbc;

  JdbcBookingLocks(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void lockCustomer(MobileNumber mobile) {
    // pg_advisory_xact_lock returns void, so it runs as a query: an update would refuse a result.
    translated(
        () ->
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:mobile, 0))")
                .param("mobile", mobile.e164())
                .query((rs, row) -> Boolean.TRUE)
                .single());
  }

  @Override
  public int lockSeats(UUID branchId, UUID serviceId) {
    return translated(
        () ->
            jdbc.sql(
                    """
                    SELECT capacity FROM branch_services
                    WHERE branch_id = :branchId AND service_id = :serviceId
                    FOR UPDATE""")
                .param("branchId", branchId)
                .param("serviceId", serviceId)
                .query(Integer.class)
                .optional()
                .orElseThrow(ServiceNotOfferedAtBranchException::new));
  }
}
