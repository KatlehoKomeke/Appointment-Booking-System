package za.co.fynbos.branchbook.booking.application;

import java.io.Serial;

/**
 * The reference drawn for a new appointment is already taken. With 31<sup>8</sup> references this
 * almost never happens; the booking transaction runs once more with a fresh reference, and a second
 * collision means a broken random source, so it becomes a 500 (BR-11).
 */
public class ReferenceCollisionException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the signal. It carries no message: the reference is never logged. */
  public ReferenceCollisionException() {
    super(null, null, false, false);
  }
}
