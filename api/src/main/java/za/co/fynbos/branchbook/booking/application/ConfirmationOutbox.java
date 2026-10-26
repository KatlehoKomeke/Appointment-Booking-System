package za.co.fynbos.branchbook.booking.application;

/**
 * Where the booking module writes the messages it owes the customer: the confirmation of a booking
 * and the cancellation of an appointment (ADR-0003).
 *
 * <p>Booking declares this port and the notification module implements it, so booking never depends
 * on notification. Each write joins the caller's own transaction: the appointment and its message
 * commit together or not at all, so a message is never lost and never written for a change that
 * rolled back.
 */
public interface ConfirmationOutbox {

  /**
   * Writes the confirmation SMS as a PENDING message, inside the caller's transaction.
   *
   * @param confirmation what the message must say and where it goes
   */
  void enqueueConfirmation(Confirmation confirmation);

  /**
   * Supersedes the appointment's confirmation if it is still PENDING or FAILED, so it is not sent
   * from then on, then writes the cancellation SMS as a PENDING message, inside the caller's
   * transaction (BR-16, BR-24). A confirmation is therefore delivered before its cancellation, or
   * never.
   *
   * @param cancellation what the message must say and where it goes
   */
  void enqueueCancellation(Cancellation cancellation);
}
