package za.co.fynbos.branchbook.booking.application;

import java.io.Serial;
import java.util.List;
import java.util.Map;
import za.co.fynbos.branchbook.shared.problem.FieldProblem;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * The booking has no South African mobile number the confirmation SMS could reach: {@code 422
 * MOBILE_NUMBER_REQUIRED} (BR-19). It names the field, never the value.
 */
public class MobileNumberRequiredException extends ProblemException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the problem, with one {@code errors[]} entry for {@code customer.mobile}. */
  public MobileNumberRequiredException() {
    super(
        ProblemCode.MOBILE_NUMBER_REQUIRED,
        "We need a cellphone number to SMS your confirmation.",
        Map.of(
            "errors",
            List.of(
                new FieldProblem(
                    "customer.mobile",
                    ProblemCode.MOBILE_NUMBER_REQUIRED.name(),
                    "Enter a South African cellphone number, for example 082 555 0101"))));
  }
}
