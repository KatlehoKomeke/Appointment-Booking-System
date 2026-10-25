package za.co.fynbos.branchbook.booking.application;

import java.io.Serial;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * The customer already has an upcoming appointment for this service: {@code 409
 * DUPLICATE_APPOINTMENT} (BR-10). The body names no reference, branch or time, so a caller who
 * knows only a number learns nothing that opens the appointment (BR-23).
 */
public class DuplicateAppointmentException extends ProblemException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the problem. */
  public DuplicateAppointmentException() {
    super(
        ProblemCode.DUPLICATE_APPOINTMENT,
        "You already have an upcoming appointment for this service. Use the reference in your SMS"
            + " to manage it.");
  }
}
