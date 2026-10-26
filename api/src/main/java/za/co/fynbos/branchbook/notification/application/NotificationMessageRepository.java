package za.co.fynbos.branchbook.notification.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import za.co.fynbos.branchbook.notification.domain.NotificationMessage;

/** Where messages are stored: the {@code notification_messages} table. */
public interface NotificationMessageRepository {

  /**
   * Stores a new message in the caller's transaction.
   *
   * @param message the message
   */
  void insert(NotificationMessage message);

  /**
   * Lists an appointment's messages, oldest first.
   *
   * @param appointmentId the appointment's id
   * @return its messages; empty if there are none
   */
  List<NotificationMessage> forAppointment(UUID appointmentId);

  /**
   * Lists the newest messages of every appointment, for the simulated inbox.
   *
   * @param limit the most messages to return
   * @return the messages, newest first
   */
  List<NotificationMessage> newest(int limit);

  /**
   * Locks one message for the caller's transaction, waiting for a transaction that holds it, such
   * as the dispatcher while it sends that message. So a retry acts on the message as it is once the
   * send in flight has been marked, never on what it was before.
   *
   * @param id the message's id
   * @return the message, or empty if no message has that id
   */
  Optional<NotificationMessage> lockById(UUID id);

  /**
   * Counts the messages still waiting to be delivered, whether due now or backing off, for the
   * {@code outbox.pending} gauge.
   *
   * @return how many messages are PENDING
   */
  long countPending();

  /**
   * Locks the next due PENDING message for the caller's transaction, skipping any another
   * transaction has locked.
   *
   * @param now the current instant; a message is due once its next attempt is not after it
   * @return the message, or empty if none is due
   */
  Optional<NotificationMessage> claimNextDue(Instant now);

  /**
   * Marks a message SENT.
   *
   * @param id the message
   * @param attempts the attempts made, this one included
   * @param providerMessageId the gateway's id for it
   * @param sentAt when the gateway accepted it
   */
  void markSent(UUID id, int attempts, String providerMessageId, Instant sentAt);

  /**
   * Records a refused attempt and when to try again.
   *
   * @param id the message
   * @param attempts the attempts made, this one included
   * @param nextAttemptAt when the dispatcher may try it again
   * @param lastError the gateway's code for the refusal
   */
  void markRetry(UUID id, int attempts, Instant nextAttemptAt, String lastError);

  /**
   * Marks a message FAILED.
   *
   * @param id the message
   * @param attempts the attempts made
   * @param failureReason why, such as {@code DELIVERY_FAILED}
   * @param lastError the gateway's code for the last refusal
   */
  void markFailed(UUID id, int attempts, String failureReason, String lastError);

  /**
   * Marks FAILED with {@code SUPERSEDED_BY_CANCELLATION} every CONFIRMATION of an appointment that
   * is still PENDING or FAILED, in the caller's transaction (BR-24). A confirmation the dispatcher
   * is sending is locked, so this waits for that one send to be marked and then leaves the message
   * alone if it went out: a confirmation is delivered before its cancellation, or never.
   *
   * @param appointmentId the appointment being cancelled
   * @return how many confirmations were superseded
   */
  int supersedeConfirmations(UUID appointmentId);

  /**
   * Marks one message FAILED with {@code SUPERSEDED_BY_CANCELLATION}, for a confirmation the
   * dispatcher claimed whose appointment is cancelled (BR-24).
   *
   * @param id the message, locked by the caller
   */
  void markSuperseded(UUID id);

  /**
   * Makes a FAILED message due again: PENDING, no attempts, due at {@code now}, with no error or
   * failure reason (FR-18). The caller has locked the message and decided that a retry may send it.
   *
   * @param id the message
   * @param now the current instant, when the dispatcher may next try it
   * @return the message as stored after the reset
   */
  NotificationMessage resetForRetry(UUID id, Instant now);

  /**
   * Marks a still-PENDING message FAILED with {@code DISPATCH_ERROR}, after marking its outcome
   * failed.
   *
   * @param id the message
   * @param lastError what to record, {@code MARK_FAILED}
   */
  void markDispatchError(UUID id, String lastError);

  /**
   * Deletes every message of the given appointments, in the caller's transaction, for booking's
   * retention purge. A message another transaction has locked is waited for, at most the session's
   * {@code lock_timeout}.
   *
   * @param appointmentIds the appointments; may be empty
   * @return how many messages were deleted
   */
  int deleteForAppointments(Collection<UUID> appointmentIds);
}
