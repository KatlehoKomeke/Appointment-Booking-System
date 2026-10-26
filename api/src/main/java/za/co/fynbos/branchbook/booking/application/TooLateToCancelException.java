package za.co.fynbos.branchbook.booking.application;

import java.io.Serial;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * The appointment has started, so it can no longer be cancelled: {@code 409 TOO_LATE_TO_CANCEL}
 * (BR-14, BR-25). It stays as it was; the branch, not the customer, decides what happens to a
 * started appointment. The body names no time, so it reveals nothing the caller did not send.
 */
public class TooLateToCancelException extends ProblemException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the problem. */
  public TooLateToCancelException() {
    super(
        ProblemCode.TOO_LATE_TO_CANCEL,
        "This appointment has already started, so it can no longer be cancelled.");
  }
}
