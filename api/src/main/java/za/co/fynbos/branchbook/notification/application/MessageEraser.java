package za.co.fynbos.branchbook.notification.application;

import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.booking.application.NotificationEraser;

/**
 * Deletes the messages of appointments that booking's retention purge is deleting, implementing
 * booking's {@code NotificationEraser} port, so the hosted demo keeps no customer's number or
 * reference in a message after their booking has gone (SECURITY.md).
 *
 * <p>It refuses to run outside a transaction, like {@link NotificationOutbox}: a message is deleted
 * only in the same commit as its appointment, so a failed batch leaves both in place. It logs
 * nothing; the purge reports the counts.
 */
@Service
public class MessageEraser implements NotificationEraser {

  private final NotificationMessageRepository messages;

  MessageEraser(NotificationMessageRepository messages) {
    this.messages = messages;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int eraseFor(Collection<UUID> appointmentIds) {
    return messages.deleteForAppointments(appointmentIds);
  }
}
