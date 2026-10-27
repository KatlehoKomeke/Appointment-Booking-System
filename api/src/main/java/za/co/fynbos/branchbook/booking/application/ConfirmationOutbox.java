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
   * Writes the confirmation SMS as a PENDING message, inside the caller's transaction; or as a
   * FAILED one, never sent, where the number has reached the notification module's daily cap.
   *
   * @param confirmation what the message must say and where it goes
   */
  void enqueueConfirmation(Confirmation confirmation);

  /**
   * Replaces the appointment's confirmations with its cancellation SMS, inside the caller's
   * transaction, just before the cancel deletes the appointment (BR-14, BR-16, BR-24). It deletes
   * every confirmation, whatever its status, keeping no copy; one the dispatcher is sending is
   * waited for first, so a confirmation is delivered before its cancellation, or never. It then
   * writes the cancellation as a PENDING message against the appointment, which the appointment's
   * delete detaches and the dispatcher deletes once it is final. Where the number has reached the
   * notification module's daily cap, which still counts the confirmations deleted here, the
   * cancellation is final at once, never sent, so it is not stored at all.
   *
   * @param cancellation what the message must say and where it goes
   * @return the cancellation SMS as the cancel's response shows it: PENDING, or FAILED with {@code
   *     RECIPIENT_DAILY_CAP} where the cap stopped it
   */
  NotificationSummary enqueueCancellation(Cancellation cancellation);
}
