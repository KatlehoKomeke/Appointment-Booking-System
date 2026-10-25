package za.co.fynbos.branchbook.booking.application;

import java.io.Serial;
import java.util.List;
import java.util.Map;
import za.co.fynbos.branchbook.scheduling.application.AlternativeSlots.Alternative;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * Every seat of the requested slot was booked while the customer was completing their details:
 * {@code 409 SLOT_FULL} (BR-7). The body lists up to three alternatives, found after the booking
 * transaction rolled back.
 */
public class SlotFullException extends ProblemException {

  @Serial private static final long serialVersionUID = 1L;

  private static final String DETAIL =
      "All seats for this time were booked while you were completing your details. Choose one of"
          + " the alternatives.";

  /** Creates the problem inside the transaction, before the alternatives are known. */
  public SlotFullException() {
    this(List.of());
  }

  /**
   * Creates the problem with the alternatives to offer.
   *
   * @param alternatives up to three later free slots on the same day; may be empty
   */
  public SlotFullException(List<Alternative> alternatives) {
    super(ProblemCode.SLOT_FULL, DETAIL, Map.of("alternatives", List.copyOf(alternatives)));
  }
}
