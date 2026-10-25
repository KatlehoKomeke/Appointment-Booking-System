package za.co.fynbos.branchbook.booking.application;

import java.io.Serial;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * No appointment matches the reference and the last four digits: {@code 404 APPOINTMENT_NOT_FOUND}
 * (BR-13). A wrong reference and wrong digits give the same answer, so the response never confirms
 * that a reference exists.
 */
public class AppointmentNotFoundException extends ProblemException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the problem. */
  public AppointmentNotFoundException() {
    super(ProblemCode.APPOINTMENT_NOT_FOUND, "We couldn't find an appointment with those details.");
  }
}
