package za.co.fynbos.branchbook.notification.application;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.booking.application.Cancellation;
import za.co.fynbos.branchbook.booking.application.Confirmation;
import za.co.fynbos.branchbook.booking.application.ConfirmationOutbox;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.notification.domain.NotificationKind;
import za.co.fynbos.branchbook.notification.domain.NotificationMessage;
import za.co.fynbos.branchbook.notification.domain.NotificationTemplates;
import za.co.fynbos.branchbook.shared.correlation.CorrelationIds;

/**
 * Writes the confirmation SMS inside the booking transaction, and the cancellation SMS inside the
 * cancel transaction, each as a PENDING row of the outbox (ADR-0003). It refuses to run outside a
 * transaction, so a message can never commit without the change it reports. Delivering it is the
 * dispatcher's job.
 *
 * <p>A cancellation first supersedes the appointment's confirmation if it is still PENDING or
 * FAILED, so the customer is never told about an appointment after its cancellation (BR-24). Its
 * log lines name the message and the appointment, never the recipient or the text (BR-19).
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
    enqueue(
        confirmation.appointmentId(),
        NotificationKind.CONFIRMATION,
        confirmation.recipient(),
        templates.confirmationSms(
            confirmation.serviceSmsName(),
            confirmation.branchSmsName(),
            confirmation.startsAt(),
            confirmation.zone(),
            confirmation.reference()),
        confirmation.now());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueueCancellation(Cancellation cancellation) {
    int superseded = messages.supersedeConfirmations(cancellation.appointmentId());
    if (superseded > 0) {
      log.info(
          "outbox.superseded appointmentId={} count={}", cancellation.appointmentId(), superseded);
    }
    enqueue(
        cancellation.appointmentId(),
        NotificationKind.CANCELLATION,
        cancellation.recipient(),
        templates.cancellationSms(
            cancellation.serviceSmsName(),
            cancellation.branchSmsName(),
            cancellation.startsAt(),
            cancellation.zone(),
            cancellation.reference()),
        cancellation.now());
  }

  /** Writes one PENDING SMS, due at once, under the request's correlation id. */
  private void enqueue(
      UUID appointmentId, NotificationKind kind, MobileNumber recipient, String body, Instant now) {
    NotificationMessage message =
        NotificationMessage.pendingSms(
            appointmentId,
            kind,
            recipient.e164(),
            body,
            CorrelationIds.current().orElseGet(CorrelationIds::systemId),
            now);
    messages.insert(message);
    log.info(
        "outbox.enqueued messageId={} kind={} channel={}",
        message.id(),
        message.kind(),
        message.channel());
  }
}
