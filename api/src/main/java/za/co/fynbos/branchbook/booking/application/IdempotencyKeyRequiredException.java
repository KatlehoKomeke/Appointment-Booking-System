package za.co.fynbos.branchbook.booking.application;

import java.io.Serial;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * A booking request without a valid {@code Idempotency-Key} header: {@code 400
 * IDEMPOTENCY_KEY_REQUIRED} (BR-12). The key must be a UUID, sent again unchanged on a retry.
 */
public class IdempotencyKeyRequiredException extends ProblemException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the problem. */
  public IdempotencyKeyRequiredException() {
    super(
        ProblemCode.IDEMPOTENCY_KEY_REQUIRED,
        "Send an Idempotency-Key header holding a UUID, and the same key when you retry.");
  }
}
