package za.co.fynbos.branchbook.booking.application;

import java.io.Serial;
import java.util.List;
import java.util.Map;
import za.co.fynbos.branchbook.scheduling.domain.SlotProblem;
import za.co.fynbos.branchbook.shared.problem.FieldProblem;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * The requested start is not one of the slots: {@code 422} with the code of the first rule it
 * breaks, by the fixed precedence of {@link SlotProblem} (BR-1 to BR-5, BR-21). One {@code
 * errors[]} entry names {@code startsAt}.
 */
public class SlotNotBookableException extends ProblemException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the problem for the rule the start breaks.
   *
   * @param problem the first rule broken
   */
  public SlotNotBookableException(SlotProblem problem) {
    super(
        ProblemCode.valueOf(problem.name()),
        detail(problem),
        Map.of("errors", List.of(new FieldProblem("startsAt", problem.name(), detail(problem)))));
  }

  private static String detail(SlotProblem problem) {
    return switch (problem) {
      case DATE_OUTSIDE_BOOKING_WINDOW ->
          "That date can't be booked. Choose a date from bookableFrom to bookableTo.";
      case SLOT_ON_CLOSED_DAY -> "The branch is closed that day. Choose another date.";
      case SLOT_OUTSIDE_HOURS ->
          "That time is outside the branch's trading hours. Choose another slot.";
      case SLOT_OFF_GRID -> "That is not a bookable start time. Choose one of the listed slots.";
      case SLOT_WITHIN_LEAD_TIME -> "That time is too soon. Please choose a later slot.";
    };
  }
}
