package za.co.fynbos.branchbook.booking.infrastructure;

import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import za.co.fynbos.branchbook.booking.application.CustomerOverlapException;
import za.co.fynbos.branchbook.booking.application.ReferenceCollisionException;
import za.co.fynbos.branchbook.booking.application.SlotFullException;
import za.co.fynbos.branchbook.booking.application.TransactionConflictException;
import za.co.fynbos.branchbook.catalogue.application.ServiceNotOfferedAtBranchException;

/**
 * Turns a database failure in the booking transaction into the answer it means, by the constraint
 * it broke and its SQLSTATE (BR-8, BR-9).
 *
 * <p>It reads both from the driver's own exception, never from the message text, which can quote
 * the conflicting values. Its log line holds only the SQLSTATE and the constraint name (BR-19).
 *
 * <table>
 *   <caption>What each failure becomes</caption>
 *   <tr><th>SQLSTATE</th><th>Constraint</th><th>Becomes</th></tr>
 *   <tr><td>23P01</td><td>appointments_seat_no_overlap</td><td>409 SLOT_FULL</td></tr>
 *   <tr><td>23P01</td><td>appointments_customer_no_overlap</td><td>409 CUSTOMER_OVERLAP</td></tr>
 *   <tr><td>23505</td><td>appointments_reference_key</td><td>one re-run</td></tr>
 *   <tr><td>23503</td><td>appointments_branch_service_fkey</td><td>422
 *       SERVICE_NOT_OFFERED_AT_BRANCH</td></tr>
 *   <tr><td>40P01, 40001</td><td>any</td><td>one re-run, then 503 TRY_AGAIN</td></tr>
 *   <tr><td>anything else</td><td></td><td>unchanged: 503 for a lock or statement timeout, 500
 *       otherwise</td></tr>
 * </table>
 */
final class ConstraintViolationTranslator {

  private static final Logger log = LoggerFactory.getLogger(ConstraintViolationTranslator.class);

  /** The constraints the translator maps by name. ConstraintNamesIT checks each exists. */
  static final Set<String> MAPPED_CONSTRAINTS =
      Set.of(
          "appointments_seat_no_overlap",
          "appointments_customer_no_overlap",
          "appointments_reference_key",
          "appointments_branch_service_fkey");

  private static final Set<String> CONFLICTS = Set.of("40P01", "40001");

  /** How deep a cause chain is searched for the driver's exception. */
  private static final int MAX_CAUSE_DEPTH = 32;

  private ConstraintViolationTranslator() {}

  /**
   * Runs a database call and translates its failure.
   *
   * @param <T> what the call returns
   * @param call the call
   * @return what the call returned
   */
  static <T> T translated(Supplier<T> call) {
    try {
      return call.get();
    } catch (RuntimeException failure) {
      throw translate(failure);
    }
  }

  /**
   * Runs a database call that returns nothing, and translates its failure.
   *
   * @param call the call
   */
  static void translated(Runnable call) {
    try {
      call.run();
    } catch (RuntimeException failure) {
      throw translate(failure);
    }
  }

  /**
   * Translates a database failure.
   *
   * @param failure what the driver, Hibernate or Spring threw
   * @return the exception to throw instead, or the original when it has no booking meaning
   */
  static RuntimeException translate(RuntimeException failure) {
    PSQLException database = driverException(failure);
    if (database == null) {
      return failure;
    }
    String sqlState = database.getSQLState();
    ServerErrorMessage server = database.getServerErrorMessage();
    String constraint = server == null ? null : server.getConstraint();
    if (sqlState != null && CONFLICTS.contains(sqlState)) {
      log.info("booking.conflict sqlState={}", sqlState);
      return new TransactionConflictException(sqlState);
    }
    if (constraint == null || !MAPPED_CONSTRAINTS.contains(constraint)) {
      return failure;
    }
    log.info("booking.constraint sqlState={} constraint={}", sqlState, constraint);
    return switch (constraint) {
      case "appointments_seat_no_overlap" -> new SlotFullException();
      case "appointments_customer_no_overlap" -> new CustomerOverlapException();
      case "appointments_reference_key" -> new ReferenceCollisionException();
      default -> new ServiceNotOfferedAtBranchException();
    };
  }

  private static @Nullable PSQLException driverException(Throwable failure) {
    Throwable cause = failure;
    for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (cause instanceof PSQLException database) {
        return database;
      }
      cause = cause.getCause();
    }
    return null;
  }
}
