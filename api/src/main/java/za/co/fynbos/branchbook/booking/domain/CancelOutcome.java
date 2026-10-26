package za.co.fynbos.branchbook.booking.domain;

/**
 * What {@link Appointment#cancel} did (BR-14). The caller needs all three apart: only a new
 * cancellation writes anything, a repeat is answered as a success that writes nothing, and a late
 * one is refused.
 */
public enum CancelOutcome {
  /** This call cancelled the appointment: its seat is free and a cancellation message is owed. */
  CANCELLED,
  /**
   * The appointment was already cancelled, so nothing changed: a repeat, for example from a second
   * tab, queues no second message.
   */
  ALREADY_CANCELLED,
  /**
   * The appointment has started, or is in a status cancellation cannot leave, so it was left as it
   * was: {@code 409 TOO_LATE_TO_CANCEL}.
   */
  TOO_LATE
}
