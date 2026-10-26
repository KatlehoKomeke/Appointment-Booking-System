package za.co.fynbos.branchbook.notification.application;

/**
 * Where messages are handed over for delivery: the simulated SMS gateway in this version, and a
 * real provider would be one more implementation (FR-15). An implementation must never put the
 * recipient or the body in what it returns or logs.
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
   * The gateway refused the message this time; the dispatcher may try again.
   *
   * @param errorCode a short code for why, such as {@code SIMULATED_FAILURE}; never recipient data
   */
  record Refused(String errorCode) implements Delivery {

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
  }
}
