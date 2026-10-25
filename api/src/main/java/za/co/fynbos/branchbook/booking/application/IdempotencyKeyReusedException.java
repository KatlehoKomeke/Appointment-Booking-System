package za.co.fynbos.branchbook.booking.application;

import java.io.Serial;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * An {@code Idempotency-Key} that was already used for a booking with different details: {@code 422
 * IDEMPOTENCY_KEY_REUSED} (BR-12). The details are compared after normalisation, so the same number
 * typed another way is the same booking.
 */
public class IdempotencyKeyReusedException extends ProblemException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the problem. */
  public IdempotencyKeyReusedException() {
    super(
        ProblemCode.IDEMPOTENCY_KEY_REUSED,
        "This Idempotency-Key was already used with different details. Send a new key for a new"
            + " booking.");
  }
}
