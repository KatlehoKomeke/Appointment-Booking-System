package za.co.fynbos.branchbook.booking.application;

/**
 * Where the booking transaction writes the confirmation it owes the customer (ADR-0003).
 *
 * <p>Booking declares this port and the notification module implements it, so booking never depends
 * on notification. The write joins the booking's own transaction: the appointment and its
 * confirmation commit together or not at all, so a confirmation is never lost and never written for
 * a booking that rolled back.
 */
public interface ConfirmationOutbox {

  /**
   * Writes the confirmation SMS as a PENDING message, inside the caller's transaction.
   *
   * @param confirmation what the message must say and where it goes
   */
  void enqueueConfirmation(Confirmation confirmation);
}
