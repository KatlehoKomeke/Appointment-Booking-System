package za.co.fynbos.branchbook.catalogue.application;

import java.io.Serial;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * No active branch has the requested code: {@code 404 BRANCH_NOT_FOUND}. The response does not
 * repeat the code the caller sent.
 */
public class BranchNotFoundException extends ProblemException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the problem. */
  public BranchNotFoundException() {
    super(ProblemCode.BRANCH_NOT_FOUND, "We couldn't find that branch.");
  }
}
