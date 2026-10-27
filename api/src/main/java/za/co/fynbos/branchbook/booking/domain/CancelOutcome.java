package za.co.fynbos.branchbook.booking.domain;

/**
 * What {@link Appointment#cancel} did (BR-14). The caller needs all three apart: only a new
 * cancellation writes anything, a late one is refused, and one already cancelled is no booking the
 * customer can still act on.
 */
public enum CancelOutcome {
  /** This call cancelled the appointment: its seat is free and a cancellation message is owed. */
  CANCELLED,
  /**
   * The appointment was already cancelled, so nothing changed and no second message is owed. A
   * cancel deletes the appointment it cancels, so a repeat finds no appointment at all; only one
   * cancelled before cancels deleted their bookings (V6), or by hand, can answer this, and the
   * cancel then treats its reference as unknown, as it would after a delete.
   */
  ALREADY_CANCELLED,
  /**
   * The appointment has started, or is in a status cancellation cannot leave, so it was left as it
   * was: {@code 409 TOO_LATE_TO_CANCEL}.
   */
  TOO_LATE
}
