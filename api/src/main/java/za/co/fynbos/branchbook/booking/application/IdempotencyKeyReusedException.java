package za.co.fynbos.branchbook.booking.application;

import java.io.Serial;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * An {@code Idempotency-Key} that cannot book: {@code 422 IDEMPOTENCY_KEY_REUSED} (BR-12). Either
 * it was already used for a booking with different details, compared after normalisation so the
 * same number typed another way is the same booking, or the booking it made has since been
 * cancelled, and so deleted, which a retry must never make again (BR-14).
 */
public class IdempotencyKeyReusedException extends ProblemException {

  @Serial private static final long serialVersionUID = 1L;

  /** The detail of a key whose booking was cancelled. Fixed, so it names no booking. */
  static final String CANCELLED_DETAIL =
      "The booking this Idempotency-Key made has been cancelled. Send a new key to book again.";

  /** Creates the problem for a key already used with different details. */
  public IdempotencyKeyReusedException() {
    this(
        "This Idempotency-Key was already used with different details. Send a new key for a new"
            + " booking.");
  }

  private IdempotencyKeyReusedException(String detail) {
    super(ProblemCode.IDEMPOTENCY_KEY_REUSED, detail);
  }

  /**
   * Creates the problem for a key whose booking has been cancelled since, so a retry of it never
   * books again.
   *
   * @return the problem, with a fixed detail
   */
  public static IdempotencyKeyReusedException bookingCancelled() {
    return new IdempotencyKeyReusedException(CANCELLED_DETAIL);
  }
}
