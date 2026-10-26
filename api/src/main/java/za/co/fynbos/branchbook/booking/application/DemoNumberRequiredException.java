package za.co.fynbos.branchbook.booking.application;

import java.io.Serial;
import java.util.List;
import java.util.Map;
import za.co.fynbos.branchbook.shared.problem.FieldProblem;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * This deployment takes only the published demo numbers, and the booking's number, a South African
 * mobile, is not one of them: {@code 422 DEMO_NUMBER_REQUIRED} (ADR-0006). The public demo refuses
 * it so that a real person's number is never stored or sent an SMS. Like {@link
 * MobileNumberRequiredException}, it names the field, never the value.
 */
public class DemoNumberRequiredException extends ProblemException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the problem, with one {@code errors[]} entry for {@code customer.mobile}. */
  public DemoNumberRequiredException() {
    super(
        ProblemCode.DEMO_NUMBER_REQUIRED,
        "This demo takes only its published demo numbers, so it never stores a real person's"
            + " number.",
        Map.of(
            "errors",
            List.of(
                new FieldProblem(
                    "customer.mobile",
                    ProblemCode.DEMO_NUMBER_REQUIRED.name(),
                    "Use a demo number from 082 555 0000 to 082 555 0199, for example"
                        + " 082 555 0101"))));
  }
}
