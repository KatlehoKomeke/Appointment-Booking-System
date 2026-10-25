package za.co.fynbos.branchbook.booking.application;

import java.io.Serial;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * The customer already has an appointment at that time, at any branch: {@code 409 CUSTOMER_OVERLAP}
 * (BR-9). The body names no reference, branch or time (BR-23).
 */
public class CustomerOverlapException extends ProblemException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the problem. */
  public CustomerOverlapException() {
    super(ProblemCode.CUSTOMER_OVERLAP, "You already have an appointment at that time.");
  }
}
