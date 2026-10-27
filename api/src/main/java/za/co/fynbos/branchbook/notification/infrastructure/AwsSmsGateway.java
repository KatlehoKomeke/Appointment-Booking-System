package za.co.fynbos.branchbook.notification.infrastructure;

import java.time.Duration;
import java.util.EnumSet;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2ClientBuilder;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.AccessDeniedException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ConflictException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.InternalServerException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.MessageType;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ResourceNotFoundException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.SendTextMessageRequest;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ServiceQuotaExceededException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ServiceQuotaExceededExceptionReason;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ThrottlingException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ValidationException;
import za.co.fynbos.branchbook.notification.application.MessageGateway;
import za.co.fynbos.branchbook.notification.application.OutgoingMessage;
import za.co.fynbos.branchbook.notification.application.SmsProperties;

/**
 * The real SMS gateway: sends each message as a transactional SMS through AWS End User Messaging
 * SMS, the {@code pinpoint-sms-voice-v2} API's {@code SendTextMessage} (FR-15). It is used only
 * where {@code branchbook.sms.gateway} is {@code aws}, and only for numbers outside the published
 * demo block, which {@link RoutingSmsGateway} keeps simulated.
 *
 * <p>One client is built at start-up and closed on shutdown. Its credentials come from the SDK's
 * default provider chain, which in the hosted demo is the ECS task role, so no key is ever
 * configured. It does not retry: a refusal goes back to the dispatcher, and a call that hangs gives
 * up after {@link #CALL_TIMEOUT}, so the dispatcher's thread and its row lock are never held for
 * long. A refusal that sending again cannot cure, such as a number the SMS sandbox has not
 * verified, is permanent, so the dispatcher marks the message FAILED at once; any other gets the
 * dispatcher's backoff and fifth failed attempt, as the simulated gateway's refusal does.
 *
 * <p>A refusal is recorded as a fixed code built from the exception's type and, where AWS gives
 * one, its typed reason, such as {@code AWS_CONFLICT_DESTINATION_PHONE_NUMBER_NOT_VERIFIED}. An
 * exception's message is never read: AWS may quote the destination number in it. Like the simulated
 * gateway, it logs nothing.
 */
final class AwsSmsGateway implements MessageGateway, AutoCloseable {

  /** How long opening a connection to AWS may take. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** How long AWS may take to answer once the request is sent. */
  static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

  /** How long one {@code SendTextMessage} call may take in all. */
  static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

  /** What AWS is asked to send every message as: a message the customer is waiting for. */
  static final MessageType MESSAGE_TYPE = MessageType.TRANSACTIONAL;

  /** The longest provider id {@code notification_messages.provider_message_id} stores. */
  private static final int MAX_PROVIDER_ID = 64;

  /**
   * The quota reasons that say a monthly spend limit is used up, which lasts until the month ends
   * or the limit is raised, so a refusal for one of them is permanent. Never changed. An {@link
   * EnumSet} answers false when asked about a missing reason, where {@code Set.of} would throw.
   */
  private static final EnumSet<ServiceQuotaExceededExceptionReason> MONTHLY_SPEND_LIMITS =
      EnumSet.of(
          ServiceQuotaExceededExceptionReason.MONTHLY_SPEND_LIMIT_REACHED_FOR_TEXT,
          ServiceQuotaExceededExceptionReason.MONTHLY_SPEND_LIMIT_REACHED_FOR_MEDIA,
          ServiceQuotaExceededExceptionReason.MONTHLY_SPEND_LIMIT_REACHED_FOR_VOICE,
          ServiceQuotaExceededExceptionReason.MONTHLY_SPEND_LIMIT_REACHED_FOR_RCS,
          ServiceQuotaExceededExceptionReason.MONTHLY_SPEND_LIMIT_REACHED_FOR_NOTIFY);

  private final PinpointSmsVoiceV2Client client;
  private final SmsProperties.Aws settings;

  /**
   * Wraps a client; the tests pass a mock.
   *
   * @param client the client to send through, which this gateway closes
   * @param settings what every request carries
   */
  AwsSmsGateway(PinpointSmsVoiceV2Client client, SmsProperties.Aws settings) {
    this.client = client;
    this.settings = settings;
  }

  /**
   * Builds the gateway and its client from the settings. Nothing is sent and no credentials are
   * read until the first message.
   *
   * @param settings the region and what every request carries
   * @return the gateway
   * @throws SdkClientException if no region is set and the SDK's default region chain finds none
   */
  static AwsSmsGateway create(SmsProperties.Aws settings) {
    PinpointSmsVoiceV2ClientBuilder builder =
        PinpointSmsVoiceV2Client.builder()
            .httpClientBuilder(
                UrlConnectionHttpClient.builder()
                    .connectionTimeout(CONNECT_TIMEOUT)
                    .socketTimeout(READ_TIMEOUT))
            .overrideConfiguration(
                override ->
                    override
                        .retryStrategy(AwsRetryStrategy.doNotRetry())
                        .apiCallTimeout(CALL_TIMEOUT));
    if (hasText(settings.region())) {
      builder.region(Region.of(settings.region().strip()));
    }
    return new AwsSmsGateway(builder.build(), settings);
  }

  /**
   * Returns the region the client sends from, for the start-up log.
   *
   * @return for example {@code af-south-1}
   */
  Region region() {
    return client.serviceClientConfiguration().region();
  }

  @Override
  public Delivery send(OutgoingMessage message) {
    try {
      String messageId = client.sendTextMessage(request(message)).messageId();
      return new Accepted(providerId(messageId, message));
    } catch (SdkException failure) {
      return new Refused(codeFor(failure), isPermanent(failure));
    }
  }

  /** Closes the client and the HTTP client it built. */
  @Override
  public void close() {
    client.close();
  }

  /** The request for one message, with each optional setting only where it is set. */
  SendTextMessageRequest request(OutgoingMessage message) {
    SendTextMessageRequest.Builder request =
        SendTextMessageRequest.builder()
            .destinationPhoneNumber(message.recipient())
            .messageBody(message.body())
            .messageType(MESSAGE_TYPE)
            .maxPrice(settings.maxPrice());
    if (hasText(settings.originationIdentity())) {
      request.originationIdentity(settings.originationIdentity().strip());
    }
    if (hasText(settings.configurationSet())) {
      request.configurationSetName(settings.configurationSet().strip());
    }
    if (hasText(settings.protectConfigurationId())) {
      request.protectConfigurationId(settings.protectConfigurationId().strip());
    }
    if (settings.dryRun()) {
      request.dryRun(true);
    }
    return request.build();
  }

  /**
   * The id to record for an accepted message. AWS documents a {@code MessageId} on every answer;
   * should one ever come back without it, the message is still accepted, because sending it again
   * would send a second SMS, and it is recorded under its own id.
   */
  private static String providerId(@Nullable String messageId, OutgoingMessage message) {
    if (messageId == null || messageId.isBlank()) {
      return "aws-" + message.id();
    }
    return messageId.length() <= MAX_PROVIDER_ID
        ? messageId
        : messageId.substring(0, MAX_PROVIDER_ID);
  }

  /**
   * The fixed code a refusal records as its last error: the exception's type and, where AWS gives
   * one, the name of its typed reason. Never the exception's message.
   *
   * @param failure what the SDK threw
   * @return for example {@code AWS_THROTTLED} or {@code
   *     AWS_QUOTA_MONTHLY_SPEND_LIMIT_REACHED_FOR_TEXT}
   */
  static String codeFor(SdkException failure) {
    return switch (failure) {
      case ThrottlingException _ -> "AWS_THROTTLED";
      case InternalServerException _ -> "AWS_INTERNAL";
      case ValidationException invalid -> withReason("AWS_VALIDATION", invalid.reason());
      case ServiceQuotaExceededException quota -> withReason("AWS_QUOTA", quota.reason());
      case ConflictException conflict -> withReason("AWS_CONFLICT", conflict.reason());
      case AccessDeniedException _ -> "AWS_ACCESS_DENIED";
      case ResourceNotFoundException _ -> "AWS_RESOURCE_NOT_FOUND";
      case SdkClientException _ -> "AWS_UNREACHABLE";
      default -> "AWS_ERROR";
    };
  }

  /**
   * Tells whether AWS would refuse the same message again however often it were sent, so the
   * dispatcher should mark it FAILED at once rather than spend four more attempts on it: any
   * conflict, such as a number the sandbox has not verified or one that has opted out; access
   * denied; any request AWS finds invalid; a setting that names something that does not exist; and
   * a monthly spend limit reached. Throttling, AWS's own failure, the network, any other quota and
   * any other error may pass next time.
   *
   * @param failure what the SDK threw
   * @return true if sending the message again cannot succeed
   */
  static boolean isPermanent(SdkException failure) {
    return switch (failure) {
      case ConflictException _,
          AccessDeniedException _,
          ValidationException _,
          ResourceNotFoundException _ ->
          true;
      case ServiceQuotaExceededException quota -> MONTHLY_SPEND_LIMITS.contains(quota.reason());
      default -> false;
    };
  }

  /**
   * Adds a typed reason's constant name, which the SDK spells in capitals and underscores, or
   * {@code UNKNOWN_TO_SDK_VERSION} for a reason newer than the SDK.
   */
  private static String withReason(String code, @Nullable Enum<?> reason) {
    return reason == null ? code : code + "_" + reason.name();
  }

  private static boolean hasText(@Nullable String value) {
    return value != null && !value.isBlank();
  }
}
