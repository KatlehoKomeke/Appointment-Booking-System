package za.co.fynbos.branchbook.notification.application;

import java.time.Duration;
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
import za.co.fynbos.branchbook.booking.application.NotificationSummary;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.notification.domain.NotificationChannel;
import za.co.fynbos.branchbook.notification.domain.NotificationKind;
import za.co.fynbos.branchbook.notification.domain.NotificationMessage;
import za.co.fynbos.branchbook.notification.domain.NotificationTemplates;
import za.co.fynbos.branchbook.shared.correlation.CorrelationIds;

/**
 * Writes the confirmation SMS inside the booking transaction, and the cancellation SMS inside the
 * cancel transaction, each as a row of the outbox, PENDING unless the daily cap below stops it
 * (ADR-0003). It refuses to run outside a transaction, so a message can never commit without the
 * change it reports. Delivering it is the dispatcher's job.
 *
 * <p>A cancellation first deletes every confirmation of its appointment, whatever its status, so
 * the customer is never told about an appointment after its cancellation (BR-24), and the cancel
 * can then delete the appointment itself (BR-14). Its log lines name the message and the
 * appointment, never the recipient or the text (BR-19).
 *
 * <p>Where {@code branchbook.sms.recipient-daily-cap} is above zero, each message first counts the
 * SMS its number was written in the last 24 hours that are still stored, leaving out those the cap
 * itself stopped; at or above the cap the message is FAILED with {@code RECIPIENT_DAILY_CAP} and is
 * never sent, while the booking or the cancel goes through as usual (BR-17). A cancellation counts
 * before its appointment's confirmations are deleted, so the confirmation it replaces still counts
 * against it; a capped cancellation is final at once, so it is reported in the cancel's response
 * but never stored. A sent cancellation and a cancelled booking's confirmations are deleted, so
 * from then on they no longer count. Numbers in the published demo block are never capped: they are
 * always simulated. Bookings by one mobile are serialised under its advisory lock (BR-22), so two
 * bookings never count at once; a cancel takes no such lock, so cancels running at the same moment
 * as each other or as a booking for the same number can each count below the cap, and the number
 * can be sent a message or two more than the cap.
 */
@Service
public class NotificationOutbox implements ConfirmationOutbox {

  private static final Logger log = LoggerFactory.getLogger(NotificationOutbox.class);

  /** How far back the per-recipient daily cap counts. */
  static final Duration CAP_WINDOW = Duration.ofHours(24);

  private final NotificationMessageRepository messages;
  private final NotificationTemplates templates;
  private final int recipientDailyCap;

  NotificationOutbox(
      NotificationMessageRepository messages,
      NotificationProperties properties,
      SmsProperties sms) {
    this.messages = messages;
    this.templates = new NotificationTemplates(properties.publicBaseUrl());
    this.recipientDailyCap = sms.recipientDailyCap();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueueConfirmation(Confirmation confirmation) {
    enqueue(
        confirmation.appointmentId(),
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
  public NotificationSummary enqueueCancellation(Cancellation cancellation) {
    // Counted first, while the confirmation this cancellation replaces is still there to count.
    boolean capped = capReached(cancellation.recipient(), cancellation.now());
    int deleted = messages.deleteConfirmations(cancellation.appointmentId());
    if (deleted > 0) {
      log.info(
          "outbox.confirmations_deleted appointmentId={} count={}",
          cancellation.appointmentId(),
          deleted);
    }
    String body =
        templates.cancellationSms(
            cancellation.serviceSmsName(),
            cancellation.branchSmsName(),
            cancellation.startsAt(),
            cancellation.zone(),
            cancellation.reference());
    String correlationId = CorrelationIds.current().orElseGet(CorrelationIds::systemId);
    if (capped) {
      NotificationMessage refused =
          NotificationMessage.cappedSms(
              cancellation.appointmentId(),
              NotificationKind.CANCELLATION,
              cancellation.recipient().e164(),
              body,
              correlationId,
              cancellation.now());
      log.warn(
          "outbox.capped messageId={} kind={} channel={} cap={} stored=false",
          refused.id(),
          refused.kind(),
          refused.channel(),
          recipientDailyCap);
      return MessageQueries.summary(refused);
    }
    NotificationMessage message =
        NotificationMessage.pendingSms(
            cancellation.appointmentId(),
            NotificationKind.CANCELLATION,
            cancellation.recipient().e164(),
            body,
            correlationId,
            cancellation.now());
    messages.insert(message);
    log.info(
        "outbox.enqueued messageId={} kind={} channel={}",
        message.id(),
        message.kind(),
        message.channel());
    return MessageQueries.summary(message);
  }

  /**
   * Writes one confirmation SMS under the request's correlation id: PENDING and due at once, or
   * FAILED with {@code RECIPIENT_DAILY_CAP} where its number has reached the daily cap.
   */
  private void enqueue(UUID appointmentId, MobileNumber recipient, String body, Instant now) {
    String correlationId = CorrelationIds.current().orElseGet(CorrelationIds::systemId);
    NotificationKind kind = NotificationKind.CONFIRMATION;
    if (capReached(recipient, now)) {
      NotificationMessage capped =
          NotificationMessage.cappedSms(
              appointmentId, kind, recipient.e164(), body, correlationId, now);
      messages.insert(capped);
      log.warn(
          "outbox.capped messageId={} kind={} channel={} cap={}",
          capped.id(),
          capped.kind(),
          capped.channel(),
          recipientDailyCap);
      return;
    }
    NotificationMessage message =
        NotificationMessage.pendingSms(
            appointmentId, kind, recipient.e164(), body, correlationId, now);
    messages.insert(message);
    log.info(
        "outbox.enqueued messageId={} kind={} channel={}",
        message.id(),
        message.kind(),
        message.channel());
  }

  /**
   * Tells whether the number has been written as many SMS in the last 24 hours as the cap allows.
   * Always false while the cap is off, and for a number in the published demo block.
   */
  private boolean capReached(MobileNumber recipient, Instant now) {
    if (recipientDailyCap == 0 || recipient.isPublishedDemoNumber()) {
      return false;
    }
    long recent =
        messages.countTowardsDailyCap(
            recipient.e164(), NotificationChannel.SMS, now.minus(CAP_WINDOW));
    return recent >= recipientDailyCap;
  }
}
