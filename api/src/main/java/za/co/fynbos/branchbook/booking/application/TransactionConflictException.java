package za.co.fynbos.branchbook.booking.application;

import java.io.Serial;

/**
 * PostgreSQL broke a deadlock (SQLSTATE 40P01) or a serialization conflict (40001) by aborting the
 * booking transaction. It runs once more, and a second conflict becomes {@code 503 TRY_AGAIN}.
 */
public class TransactionConflictException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** The SQLSTATE that aborted the transaction. */
  private final String sqlState;

  /**
   * Creates the signal.
   *
   * @param sqlState the SQLSTATE PostgreSQL reported
   */
  public TransactionConflictException(String sqlState) {
    super("Transaction aborted with SQLSTATE " + sqlState, null, false, false);
    this.sqlState = sqlState;
  }

  /**
   * Returns why the transaction was aborted.
   *
   * @return the SQLSTATE, {@code 40P01} or {@code 40001}
   */
  public String sqlState() {
    return sqlState;
  }
}
