package za.co.fynbos.branchbook.notification.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fynbos.branchbook.notification.application.MessageGateway.Accepted;
import za.co.fynbos.branchbook.notification.application.MessageGateway.Delivery;
import za.co.fynbos.branchbook.notification.application.MessageGateway.Refused;
import za.co.fynbos.branchbook.notification.domain.NotificationMessage;
import za.co.fynbos.branchbook.shared.correlation.CorrelationIds;

/**
 * Delivers PENDING messages through the {@link MessageGateway} (BR-17, ADR-0003).
 *
 * <p>Each tick handles up to {@value #BATCH} due messages, each in its own transaction: claim one
 * due row with {@code FOR UPDATE SKIP LOCKED}, hand it to the gateway, mark the outcome, commit. So
 * a crash re-sends at most the one message in flight, and two dispatchers never hold the same row.
 * A refused message is tried again after 2, 4, 8 and 16 seconds, measured from the instant the tick
 * was given, and the fifth refusal marks it FAILED with {@code DELIVERY_FAILED}. If marking a
 * message fails, it is set FAILED with {@code DISPATCH_ERROR} and {@code MARK_FAILED} in a
 * transaction of its own, so a poisoned row is sent at most once more and never loops, and the rest
 * of the batch carries on. An appointment never depends on its messages: delivery changes only the
 * message.
 *
 * <p>Log lines carry the message's own correlation id, so they sit beside the booking that wrote
 * it, and name the message by id; never the recipient, the body or the reference.
 */
@Service
public class NotificationDispatcher {

  private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

  /** The most messages one tick handles, so one tick never runs for long. */
  static final int BATCH = 20;

  /** The attempt that, refused, marks a message FAILED. */
  static final int MAX_ATTEMPTS = 5;

  /** What a message that could not be marked records as its last error. */
  static final String MARK_FAILED = "MARK_FAILED";

  /** What a gateway that threw records as its last error. */
  static final String GATEWAY_ERROR = "GATEWAY_ERROR";

  private final TransactionTemplate transaction;
  private final NotificationMessageRepository messages;
  private final MessageGateway gateway;

  NotificationDispatcher(
      PlatformTransactionManager transactionManager,
      NotificationMessageRepository messages,
      MessageGateway gateway) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setName("NotificationDispatcher.dispatchNext");
    this.transaction = template;
    this.messages = messages;
    this.gateway = gateway;
  }

  /**
   * Runs one tick: delivers the messages that are due, up to {@value #BATCH}.
   *
   * @param now the current instant, from the application's clock; the tests pass their own
   * @return how many messages the tick handled
   */
  public int runOnce(Instant now) {
    int handled = 0;
    while (handled < BATCH) {
      AtomicReference<NotificationMessage> claimed = new AtomicReference<>();
      try {
        if (!Boolean.TRUE.equals(transaction.execute(status -> dispatchNext(now, claimed)))) {
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
      }
      handled++;
    }
    return handled;
  }

  /** Claims, sends and marks one due message; returns false when none is due. */
  private boolean dispatchNext(Instant now, AtomicReference<NotificationMessage> claimed) {
    Optional<NotificationMessage> due = messages.claimNextDue(now);
    if (due.isEmpty()) {
      return false;
    }
    NotificationMessage message = due.get();
    claimed.set(message);
    try (var _ = MDC.putCloseable(CorrelationIds.MDC_KEY, message.correlationId())) {
      int attempts = message.attempts() + 1;
      switch (send(message)) {
        case Accepted accepted -> {
          messages.markSent(message.id(), attempts, accepted.providerMessageId(), now);
          log.info(
              "outbox.sent messageId={} channel={} attempts={}",
              message.id(),
              message.channel(),
              attempts);
        }
        case Refused refused when attempts >= MAX_ATTEMPTS -> {
          messages.markFailed(message.id(), attempts, "DELIVERY_FAILED", refused.errorCode());
          log.warn("outbox.failed messageId={} attempts={}", message.id(), attempts);
        }
        case Refused refused -> {
          Duration wait = backoffAfter(attempts);
          messages.markRetry(message.id(), attempts, now.plus(wait), refused.errorCode());
          log.info(
              "outbox.retry messageId={} attempts={} retryInSeconds={}",
              message.id(),
              attempts,
              wait.toSeconds());
        }
      }
    }
    return true;
  }

  /** A gateway that throws is treated as one that refused, so the message is tried again. */
  private Delivery send(NotificationMessage message) {
    try {
      return gateway.send(OutgoingMessage.of(message));
    } catch (RuntimeException failure) {
      return new Refused(GATEWAY_ERROR);
    }
  }

  /**
   * The wait after the given failed attempt: 2, 4, 8 and then 16 seconds.
   *
   * @param attempts the attempts made so far, from 1 to 4
   * @return the wait before the next attempt
   */
  static Duration backoffAfter(int attempts) {
    return Duration.ofSeconds(1L << attempts);
  }

  /** An out-of-memory or similar error ends the tick once the message is marked. */
  private static void rethrowIfFatal(Throwable failure) {
    if (failure instanceof VirtualMachineError fatal) {
      throw fatal;
    }
  }

  /**
   * Sets a message whose outcome could not be recorded FAILED, so it is never sent again. Its log
   * lines carry the message's own correlation id, like the dispatcher's others.
   *
   * @return false if that too fails, which ends the tick
   */
  private boolean markDispatchError(NotificationMessage message, Throwable failure) {
    try (var _ = MDC.putCloseable(CorrelationIds.MDC_KEY, message.correlationId())) {
      try {
        transaction.executeWithoutResult(
            status -> messages.markDispatchError(message.id(), MARK_FAILED));
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
