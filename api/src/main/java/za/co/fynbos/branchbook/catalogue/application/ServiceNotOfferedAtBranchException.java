package za.co.fynbos.branchbook.catalogue.application;

import java.io.Serial;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * The branch does not offer the requested service, or no active service has that code: {@code 422
 * SERVICE_NOT_OFFERED_AT_BRANCH} (BR-6). The response does not repeat the code the caller sent.
 */
public class ServiceNotOfferedAtBranchException extends ProblemException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the problem. */
  public ServiceNotOfferedAtBranchException() {
    super(ProblemCode.SERVICE_NOT_OFFERED_AT_BRANCH, "This branch doesn't offer that service.");
  }
}
