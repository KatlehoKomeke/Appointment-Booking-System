package za.co.fynbos.branchbook.scheduling.application;

import java.io.Serial;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * The requested date is outside the bookable window: {@code 422 DATE_OUTSIDE_BOOKING_WINDOW}
 * (BR-5). The response does not repeat the date the caller sent.
 */
public class DateOutsideBookingWindowException extends ProblemException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the problem. */
  public DateOutsideBookingWindowException() {
    super(
        ProblemCode.DATE_OUTSIDE_BOOKING_WINDOW,
        "That date can't be booked. Choose a date from bookableFrom to bookableTo.");
  }
}
