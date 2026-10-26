package za.co.fynbos.branchbook.notification.application;

import java.io.Serial;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * No simulated message has the id a retry named: {@code 404 MESSAGE_NOT_FOUND} (FR-18). Only the
 * simulated inbox's operator view can name one, so customers never see it.
 */
public class MessageNotFoundException extends ProblemException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the problem. */
  public MessageNotFoundException() {
    super(ProblemCode.MESSAGE_NOT_FOUND, "We couldn't find a message with that id.");
  }
}
