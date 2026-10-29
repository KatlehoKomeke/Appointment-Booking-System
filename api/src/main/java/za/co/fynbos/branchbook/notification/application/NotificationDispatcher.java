package za.co.fynbos.branchbook.notification.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fynbos.branchbook.booking.application.AppointmentDirectory;
import za.co.fynbos.branchbook.notification.application.MessageGateway.Accepted;
import za.co.fynbos.branchbook.notification.application.MessageGateway.Delivery;
import za.co.fynbos.branchbook.notification.application.MessageGateway.Refused;
import za.co.fynbos.branchbook.notification.domain.NotificationKind;
import za.co.fynbos.branchbook.notification.domain.NotificationMessage;
import za.co.fynbos.branchbook.shared.correlation.CorrelationIds;

/**
 * Delivers PENDING messages through the {@link MessageGateway} (BR-17, ADR-0003).
 *
 * <p>Each tick handles up to {@code batch-size} due messages, twenty by default, each in its own
 * transaction: claim one due row with {@code FOR UPDATE SKIP LOCKED}, hand it to the gateway, mark
 * the outcome, commit. So a crash re-sends at most the one message in flight, and two dispatchers
 * never hold the same row. A refused message is tried again after the {@link RetryBackoff}'s wait,
 * by default up to 2, 4, 8 and 16 seconds less a random share of at most a fifth, measured from the
 * instant the tick was given, and the refusal of attempt {@code max-attempts}, the fifth by
 * default, marks it FAILED with {@code DELIVERY_FAILED}. A refusal the gateway calls permanent,
 * such as AWS's for a number the SMS sandbox has not verified, marks it FAILED with {@code
 * DELIVERY_FAILED} on that attempt, since sending it again cannot succeed; a gateway that throws
 * counts as a retryable refusal. Either way {@code last_error} holds the refusal's code. If marking
 * a message fails, it is set FAILED with {@code DISPATCH_ERROR} and {@code MARK_FAILED} in a
 * transaction of its own, so a poisoned row is sent at most once more and never loops, and the rest
 * of the batch carries on. An appointment never depends on its messages: delivery changes only the
 * message. The settings are {@code branchbook.dispatcher}'s ({@link DispatcherProperties}).
 *
 * <p>Before each claim it asks the {@link DeliveryCircuit} for a permission. While the circuit is
 * open, because the provider keeps failing, the tick ends there: nothing is claimed, so every
 * message stays PENDING with no attempt spent, and an outage of the provider cannot use up a
 * customer's attempts. Once the circuit's wait is over, the next ticks send a few messages as
 * probes, and their outcome closes the circuit or opens it again. A permission the tick does not
 * use, because nothing was due or the message was superseded, is given back.
 *
 * <p>A cancellation outlives its appointment, which the cancel deleted, only until it is final
 * (BR-14). So where a confirmation would be marked SENT, FAILED with {@code DELIVERY_FAILED}, or
 * FAILED with {@code DISPATCH_ERROR}, a cancellation is deleted instead, in that same transaction,
 * and the line {@code outbox.deleted} names it by id. A cancellation still PENDING stays, with the
 * customer's number and its text, until then.
 *
 * <p>Before it sends a confirmation, it asks booking's {@link AppointmentDirectory} whether the
 * appointment is cancelled, and if so marks the message FAILED with {@code
 * SUPERSEDED_BY_CANCELLATION} instead (BR-24). The cancel transaction deletes every confirmation
 * with its appointment, so this only catches the confirmation of an appointment cancelled before
 * cancels deleted their bookings, or by hand. The question is a plain read: the dispatcher locks
 * only its message's row, never the appointment's, while a cancel locks the appointment and then
 * waits for the message, so the two can never deadlock. It is asked before the gateway is called,
 * so a question that fails is a failed claim, not a failed send: the transaction rolls back, the
 * message stays PENDING and untouched, and the tick ends, as it does when the claim itself fails;
 * the next tick asks again.
 *
 * <p>Log lines carry the message's own correlation id, so they sit beside the booking that wrote
 * it, and name the message by id; never the recipient, the body or the reference. {@code
 * outbox.retry} and {@code outbox.failed} also give the refusal's code, such as {@code
 * AWS_THROTTLED}: a fixed string the gateway builds from what went wrong, never a provider's
 * message, so the log says why a send failed.
 */
@Service
public class NotificationDispatcher {

  private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

  /** What a message that could not be marked records as its last error. */
  static final String MARK_FAILED = "MARK_FAILED";

  /** What a gateway that threw records as its last error. */
  static final String GATEWAY_ERROR = "GATEWAY_ERROR";

  private final TransactionTemplate transaction;
  private final NotificationMessageRepository messages;
  private final MessageGateway gateway;
  private final AppointmentDirectory appointments;
  private final OutboxPendingGauge pending;
  private final DeliveryCircuit circuit;
  private final RetryBackoff backoff;
  private final int batchSize;
  private final int maxAttempts;

  NotificationDispatcher(
      PlatformTransactionManager transactionManager,
      NotificationMessageRepository messages,
      MessageGateway gateway,
      AppointmentDirectory appointments,
      OutboxPendingGauge pending,
      DeliveryCircuit circuit,
      RetryBackoff backoff,
      DispatcherProperties settings) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setName("NotificationDispatcher.dispatchNext");
    this.transaction = template;
    this.messages = messages;
    this.gateway = gateway;
    this.appointments = appointments;
    this.pending = pending;
    this.circuit = circuit;
    this.backoff = backoff;
    this.batchSize = settings.batchSize();
    this.maxAttempts = settings.maxAttempts();
  }

  /**
   * Runs one tick: delivers the messages that are due, up to {@code batch-size}, unless the circuit
   * is open, then counts those still PENDING for the {@code outbox.pending} gauge.
   *
   * @param now the current instant, from the application's clock; the tests pass their own
   * @return how many messages the tick handled
   */
  public int runOnce(Instant now) {
    int handled = 0;
    while (handled < batchSize) {
      if (!circuit.tryAcquire()) {
        // Open: nothing is claimed, so no message spends an attempt on a provider that is down.
        break;
      }
      AtomicReference<NotificationMessage> claimed = new AtomicReference<>();
      AtomicBoolean called = new AtomicBoolean();
      try {
        if (!Boolean.TRUE.equals(
            transaction.execute(status -> dispatchNext(now, claimed, called)))) {
          break;
        }
      } catch (RuntimeException | Error failure) {
        // An Error, such as a gateway's NoClassDefFoundError, is caught too: left PENDING, the same
        // message would be the oldest due row again, re-sent on every tick and blocking the rest.
        NotificationMessage message = claimed.get();
        if (message == null) {
          log.error("outbox.claim_failed cause={}", failure.getClass().getSimpleName());
          rethrowIfFatal(failure);
          break;
        }
        boolean marked = markDispatchError(message, failure);
        rethrowIfFatal(failure);
        if (!marked) {
          break;
        }
      } finally {
        if (!called.get()) {
          circuit.release();
        }
      }
      handled++;
    }
    pending.refresh();
    return handled;
  }

  /**
   * Claims, sends and marks one due message; returns false when none is due. Sets {@code called}
   * once the gateway has been called and its outcome given to the circuit.
   */
  private boolean dispatchNext(
      Instant now, AtomicReference<NotificationMessage> claimed, AtomicBoolean called) {
    Optional<NotificationMessage> due = messages.claimNextDue(now);
    if (due.isEmpty()) {
      return false;
    }
    NotificationMessage message = due.get();
    try (var _ = MDC.putCloseable(CorrelationIds.MDC_KEY, message.correlationId())) {
      // Asked before the message counts as claimed: nothing has been sent yet, so if the question
      // fails, the transaction rolls back and the message stays PENDING for the next tick.
      if (message.kind() == NotificationKind.CONFIRMATION
          && appointments.isCancelled(message.appointmentId())) {
        messages.markSuperseded(message.id());
        log.info("outbox.superseded messageId={}", message.id());
        return true;
      }
      claimed.set(message);
      int attempts = message.attempts() + 1;
      switch (send(message, called)) {
        case Accepted accepted -> {
          if (keptOnceFinal(message)) {
            messages.markSent(message.id(), attempts, accepted.providerMessageId(), now);
          }
          log.info(
              "outbox.sent messageId={} channel={} attempts={}",
              message.id(),
              message.channel(),
              attempts);
          if (!keptOnceFinal(message)) {
            deleteFinal(message);
          }
        }
        case Refused refused when refused.permanent() || attempts >= maxAttempts -> {
          if (keptOnceFinal(message)) {
            messages.markFailed(message.id(), attempts, "DELIVERY_FAILED", refused.errorCode());
          }
          log.warn(
              "outbox.failed messageId={} attempts={} code={}",
              message.id(),
              attempts,
              refused.errorCode());
          if (!keptOnceFinal(message)) {
            deleteFinal(message);
          }
        }
        case Refused refused -> {
          Duration wait = backoff.after(attempts);
          messages.markRetry(message.id(), attempts, now.plus(wait), refused.errorCode());
          log.info(
              "outbox.retry messageId={} attempts={} retryInSeconds={} code={}",
              message.id(),
              attempts,
              seconds(wait),
              refused.errorCode());
        }
      }
    }
    return true;
  }

  /**
   * Tells whether a message stays in the outbox once it is final: a confirmation does, with its
   * appointment; a cancellation does not, as its appointment is already gone (BR-14).
   */
  private static boolean keptOnceFinal(NotificationMessage message) {
    return message.kind() != NotificationKind.CANCELLATION;
  }

  /** Deletes a final cancellation in the caller's transaction, naming it by id only. */
  private void deleteFinal(NotificationMessage message) {
    messages.delete(message.id());
    log.info("outbox.deleted messageId={} kind={}", message.id(), message.kind());
  }

  /**
   * Calls the gateway and gives the circuit its outcome. A gateway that throws is treated as one
   * that refused retryably, so the message is tried again; an {@link Error} is recorded as a
   * failure and thrown on.
   */
  private Delivery send(NotificationMessage message, AtomicBoolean called) {
    long start = System.nanoTime();
    Delivery delivery;
    try {
      delivery = gateway.send(OutgoingMessage.of(message));
    } catch (RuntimeException failure) {
      delivery = Refused.retryable(GATEWAY_ERROR);
    } catch (Error failure) {
      called.set(true);
      circuit.recordError(Duration.ofNanos(System.nanoTime() - start), failure);
      throw failure;
    }
    called.set(true);
    circuit.record(Duration.ofNanos(System.nanoTime() - start), delivery);
    return delivery;
  }

  /** A wait in seconds to the millisecond, without trailing zeros: {@code 2} or {@code 1.734}. */
  private static String seconds(Duration wait) {
    return BigDecimal.valueOf(wait.toMillis(), 3).stripTrailingZeros().toPlainString();
  }

  /** An out-of-memory or similar error ends the tick once the message is marked. */
  private static void rethrowIfFatal(Throwable failure) {
    if (failure instanceof VirtualMachineError fatal) {
      throw fatal;
    }
  }

  /**
   * Sets a message whose outcome could not be recorded FAILED, so it is never sent again; a
   * cancellation, which would then be final, is deleted instead (BR-14). Its log lines carry the
   * message's own correlation id, like the dispatcher's others.
   *
   * @return false if that too fails, which ends the tick
   */
  private boolean markDispatchError(NotificationMessage message, Throwable failure) {
    try (var _ = MDC.putCloseable(CorrelationIds.MDC_KEY, message.correlationId())) {
      try {
        transaction.executeWithoutResult(
            status -> {
              if (keptOnceFinal(message)) {
                messages.markDispatchError(message.id(), MARK_FAILED);
              } else if (messages.deleteIfPending(message.id())) {
                log.info("outbox.deleted messageId={} kind={}", message.id(), message.kind());
              }
            });
        log.error(
            "outbox.dispatch_error messageId={} cause={}",
            message.id(),
            failure.getClass().getSimpleName());
        return true;
      } catch (RuntimeException again) {
        log.error(
            "outbox.dispatch_error_unmarked messageId={} cause={}",
            message.id(),
            again.getClass().getSimpleName());
        return false;
      }
    }
  }
}
