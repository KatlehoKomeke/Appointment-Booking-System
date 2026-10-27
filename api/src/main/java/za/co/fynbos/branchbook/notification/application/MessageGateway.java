package za.co.fynbos.branchbook.notification.application;

/**
 * Where messages are handed over for delivery (FR-15): the simulated SMS gateway, or, where {@code
 * branchbook.sms.gateway} is {@code aws}, AWS End User Messaging SMS for every number outside the
 * published demo block. An implementation must never put the recipient or the body in what it
 * returns or logs, nor a provider's error message, which may quote them: a refusal is a fixed code,
 * which the dispatcher logs, and says whether sending the message again could ever succeed.
 */
public interface MessageGateway {

  /**
   * Hands a message over for delivery.
   *
   * @param message what to send
   * @return whether the gateway accepted it
   */
  Delivery send(OutgoingMessage message);

  /** What the gateway did with a message. */
  sealed interface Delivery permits Accepted, Refused {}

  /**
   * The gateway accepted the message.
   *
   * @param providerMessageId the gateway's id for it, from 1 to 64 characters, what the table
   *     stores; an adapter maps a longer id before returning
   */
  record Accepted(String providerMessageId) implements Delivery {

    /** The longest id {@code notification_messages.provider_message_id} can store. */
    static final int MAX_PROVIDER_ID = 64;

    /** Rejects an id the table could not store, which would turn a delivery into a failed mark. */
    public Accepted {
      if (providerMessageId.isBlank() || providerMessageId.length() > MAX_PROVIDER_ID) {
        throw new IllegalArgumentException("A provider message id is 1 to 64 characters");
      }
    }
  }

  /**
   * The gateway refused the message. A retryable refusal may pass next time, so the dispatcher
   * tries again after its backoff; a permanent one cannot, such as a number the provider will not
   * text, so the dispatcher marks the message FAILED on that attempt. A gateway builds one with
   * {@link #retryable} or {@link #permanent(String)}, so each refusal says which it is.
   *
   * @param errorCode a short fixed code for why, such as {@code SIMULATED_FAILURE}, which the
   *     dispatcher stores and logs; never recipient data, a name, a reference or a provider's
   *     message
   * @param permanent whether sending the message again cannot succeed
   */
  record Refused(String errorCode, boolean permanent) implements Delivery {

    /** The longest code {@code notification_messages.last_error} can store. */
    static final int MAX_ERROR_CODE = 500;

    /**
     * Cuts a code longer than the table can store, so it cannot turn a retry into a failed mark.
     */
    public Refused {
      if (errorCode.length() > MAX_ERROR_CODE) {
        errorCode = errorCode.substring(0, MAX_ERROR_CODE);
      }
    }

    /**
     * A refusal that may pass next time, so the dispatcher tries the message again.
     *
     * @param errorCode a short fixed code for why, such as {@code AWS_THROTTLED}
     * @return the refusal
     */
    public static Refused retryable(String errorCode) {
      return new Refused(errorCode, false);
    }

    /**
     * A refusal that cannot pass however often the message is sent, so the dispatcher marks it
     * FAILED at once.
     *
     * @param errorCode a short fixed code for why, such as {@code
     *     AWS_CONFLICT_DESTINATION_PHONE_NUMBER_NOT_VERIFIED}
     * @return the refusal
     */
    public static Refused permanent(String errorCode) {
      return new Refused(errorCode, true);
    }
  }
}
