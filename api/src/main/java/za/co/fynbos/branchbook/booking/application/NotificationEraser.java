package za.co.fynbos.branchbook.booking.application;

import java.util.Collection;
import java.util.UUID;

/**
 * Erases the messages written about appointments that the retention purge is deleting.
 *
 * <p>It exists because an appointment cannot be deleted before its confirmation: {@code
 * notification_messages.appointment_id} references it {@code ON DELETE SET NULL}, and only a
 * cancellation may be left with no appointment (V6), so no path leaves a confirmation behind by
 * accident. And the messages must go too, because each holds the customer's number and the
 * reference that opens the appointment (SECURITY.md).
 *
 * <p>Booking declares this port and the notification module implements it, as with {@link
 * ConfirmationOutbox}, so booking never depends on notification and never touches its table. The
 * delete joins the caller's transaction: the messages and their appointment go together or not at
 * all.
 */
public interface NotificationEraser {

  /**
   * Deletes every message of the given appointments, inside the caller's transaction. A message the
   * dispatcher is sending at that moment is waited for, at most the session's {@code lock_timeout}.
   *
   * @param appointmentIds the appointments being deleted; may be empty
   * @return how many messages were deleted
   */
  int eraseFor(Collection<UUID> appointmentIds);
}
