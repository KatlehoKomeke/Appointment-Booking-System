package za.co.fynbos.branchbook.notification.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.booking.application.Confirmation;
import za.co.fynbos.branchbook.booking.application.ConfirmationOutbox;
import za.co.fynbos.branchbook.notification.domain.NotificationKind;
import za.co.fynbos.branchbook.notification.domain.NotificationMessage;
import za.co.fynbos.branchbook.notification.domain.NotificationTemplates;
import za.co.fynbos.branchbook.shared.correlation.CorrelationIds;

/**
 * Writes the confirmation SMS as a PENDING row of the outbox, inside the booking transaction
 * (ADR-0003). It refuses to run outside a transaction, so a confirmation can never commit without
 * its booking. Delivering it is the dispatcher's job, from milestone M5.
 *
 * <p>Its log line names the message, never the recipient or the text (BR-19).
 */
@Service
public class NotificationOutbox implements ConfirmationOutbox {

  private static final Logger log = LoggerFactory.getLogger(NotificationOutbox.class);

  private final NotificationMessageRepository messages;
  private final NotificationTemplates templates;

  NotificationOutbox(NotificationMessageRepository messages, NotificationProperties properties) {
    this.messages = messages;
    this.templates = new NotificationTemplates(properties.publicBaseUrl());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueueConfirmation(Confirmation confirmation) {
    String body =
        templates.confirmationSms(
            confirmation.serviceSmsName(),
            confirmation.branchSmsName(),
            confirmation.startsAt(),
            confirmation.zone(),
            confirmation.reference());
    NotificationMessage message =
        NotificationMessage.pendingSms(
            confirmation.appointmentId(),
            NotificationKind.CONFIRMATION,
            confirmation.recipient().e164(),
            body,
            CorrelationIds.current().orElseGet(CorrelationIds::systemId),
            confirmation.now());
    messages.insert(message);
    log.info(
        "outbox.enqueued messageId={} kind={} channel={}",
        message.id(),
        message.kind(),
        message.channel());
  }
}
