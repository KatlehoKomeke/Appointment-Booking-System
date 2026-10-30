package za.co.fynbos.branchbook.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.AccessDeniedException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.AccessDeniedExceptionReason;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ConflictException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ConflictExceptionReason;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.InternalServerException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.MessageType;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.PinpointSmsVoiceV2Exception;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ResourceNotFoundException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.SendTextMessageRequest;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.SendTextMessageResponse;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ServiceQuotaExceededException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ServiceQuotaExceededExceptionReason;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ThrottlingException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ValidationException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ValidationExceptionReason;
import za.co.fynbos.branchbook.notification.application.MessageGateway.Accepted;
import za.co.fynbos.branchbook.notification.application.MessageGateway.Delivery;
import za.co.fynbos.branchbook.notification.application.MessageGateway.Refused;
import za.co.fynbos.branchbook.notification.application.OutgoingMessage;
import za.co.fynbos.branchbook.notification.application.SmsProperties;
import za.co.fynbos.branchbook.notification.domain.NotificationChannel;

/**
 * FR-15, BR-17, BR-19: the AWS gateway sends a transactional SMS with the deployment's MaxPrice and
 * only the optional settings that are set, maps AWS's MessageId to an accepted delivery, and maps
 * each refusal to a fixed code built from the exception's type and typed reason, never its message,
 * and to whether sending it again could succeed. The client is a Mockito mock, so nothing reaches
 * AWS.
 */
class AwsSmsGatewayTest {

  /** A number just past the published demo block, which the router sends through AWS. */
  private static final String RECIPIENT = "+27825550200";

  private static final String BODY =
      "Appt confirmed: FICA update, Sandton Summit, Tue 27 Oct 09:30 SAST. Ref APT-7K3M-9Q2X."
          + " Bring ID + docs. Manage: http://localhost:8080/manage";

  /** What AWS might quote in an exception's message, which must never reach a code. */
  private static final String QUOTED = "+27825550200 APT-7K3M-9Q2X Nomvula";

  private static final OutgoingMessage MESSAGE =
      new OutgoingMessage(
          UUID.fromString("c0000000-0000-4000-8000-000000000001"),
          NotificationChannel.SMS,
          RECIPIENT,
          BODY);

  private static final SmsProperties.Aws NOTHING_OPTIONAL =
      new SmsProperties.Aws(
          null,
          " ",
          "",
          null,
          "0.20",
          false,
          Duration.ofSeconds(2),
          Duration.ofSeconds(5),
          Duration.ofSeconds(10));

  private final PinpointSmsVoiceV2Client client = mock(PinpointSmsVoiceV2Client.class);

  @Test
  void sendsATransactionalSmsWithTheMaxPriceAndLeavesBlankSettingsOut() {
    answerWithId("4fd3c4c4-6e2b-4b0a-9a51-5b8f2d1f0a11");

    Delivery delivery = new AwsSmsGateway(client, NOTHING_OPTIONAL).send(MESSAGE);

    assertThat(delivery).isEqualTo(new Accepted("4fd3c4c4-6e2b-4b0a-9a51-5b8f2d1f0a11"));
    SendTextMessageRequest request = sentRequest();
    assertThat(request.destinationPhoneNumber()).isEqualTo(RECIPIENT);
    assertThat(request.messageBody()).isEqualTo(BODY);
    assertThat(request.messageType()).isEqualTo(MessageType.TRANSACTIONAL);
    assertThat(request.maxPrice()).isEqualTo("0.20");
    assertThat(request.protectConfigurationId()).isNull();
    assertThat(request.configurationSetName()).isNull();
    assertThat(request.originationIdentity()).isNull();
    assertThat(request.dryRun()).isNull();
  }

  @Test
  void sendsEveryOptionalSettingThatIsSet() {
    answerWithId("m-1");
    SmsProperties.Aws everything =
        new SmsProperties.Aws(
            "af-south-1",
            " protect-0a1b2c ",
            "branchbook-sms",
            "pool-0a1b2c",
            "0.05",
            true,
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10));

    new AwsSmsGateway(client, everything).send(MESSAGE);

    SendTextMessageRequest request = sentRequest();
    assertThat(request.protectConfigurationId()).isEqualTo("protect-0a1b2c");
    assertThat(request.configurationSetName()).isEqualTo("branchbook-sms");
    assertThat(request.originationIdentity()).isEqualTo("pool-0a1b2c");
    assertThat(request.maxPrice()).isEqualTo("0.05");
    assertThat(request.dryRun()).isTrue();
    assertThat(request.messageType()).isEqualTo(MessageType.TRANSACTIONAL);
  }

  /**
   * An answer without a MessageId is still a message AWS took, so it is accepted under the
   * message's own id rather than sent twice; an id longer than its column is cut to fit.
   */
  @Test
  void anAnswerWithoutAnIdIsStillAcceptedAndALongIdIsCutToItsColumn() {
    when(client.sendTextMessage(any(SendTextMessageRequest.class)))
        .thenReturn(SendTextMessageResponse.builder().build())
        .thenReturn(SendTextMessageResponse.builder().messageId("x".repeat(80)).build());
    AwsSmsGateway gateway = new AwsSmsGateway(client, NOTHING_OPTIONAL);

    assertThat(gateway.send(MESSAGE)).isEqualTo(new Accepted("aws-" + MESSAGE.id()));
    assertThat(gateway.send(MESSAGE)).isEqualTo(new Accepted("x".repeat(64)));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("refusals")
  void eachRefusalBecomesItsFixedCodeAndNeverQuotesTheMessage(SdkException thrown, String code) {
    when(client.sendTextMessage(any(SendTextMessageRequest.class))).thenThrow(thrown);

    Delivery delivery = new AwsSmsGateway(client, NOTHING_OPTIONAL).send(MESSAGE);

    assertThat(delivery).isInstanceOf(Refused.class);
    assertThat(((Refused) delivery).errorCode()).isEqualTo(code).matches("AWS_[A-Z0-9_]+");
  }

  static Stream<Arguments> refusals() {
    return Stream.of(
        arguments(ThrottlingException.builder().message(QUOTED).build(), "AWS_THROTTLED"),
        arguments(InternalServerException.builder().message(QUOTED).build(), "AWS_INTERNAL"),
        arguments(
            ValidationException.builder()
                .message(QUOTED)
                .reason(ValidationExceptionReason.PRICE_OVER_THRESHOLD)
                .build(),
            "AWS_VALIDATION_PRICE_OVER_THRESHOLD"),
        arguments(ValidationException.builder().message(QUOTED).build(), "AWS_VALIDATION"),
        arguments(
            ValidationException.builder().message(QUOTED).reason(QUOTED).build(),
            "AWS_VALIDATION_UNKNOWN_TO_SDK_VERSION"),
        arguments(
            ServiceQuotaExceededException.builder()
                .message(QUOTED)
                .reason(ServiceQuotaExceededExceptionReason.MONTHLY_SPEND_LIMIT_REACHED_FOR_TEXT)
                .build(),
            "AWS_QUOTA_MONTHLY_SPEND_LIMIT_REACHED_FOR_TEXT"),
        arguments(
            ConflictException.builder()
                .message(QUOTED)
                .reason(ConflictExceptionReason.DESTINATION_PHONE_NUMBER_NOT_VERIFIED)
                .build(),
            "AWS_CONFLICT_DESTINATION_PHONE_NUMBER_NOT_VERIFIED"),
        arguments(
            AccessDeniedException.builder()
                .message(QUOTED)
                .reason(AccessDeniedExceptionReason.ACCOUNT_DISABLED)
                .build(),
            "AWS_ACCESS_DENIED"),
        arguments(
            ResourceNotFoundException.builder().message(QUOTED).resourceId(QUOTED).build(),
            "AWS_RESOURCE_NOT_FOUND"),
        arguments(SdkClientException.create(QUOTED), "AWS_UNREACHABLE"),
        arguments(PinpointSmsVoiceV2Exception.builder().message(QUOTED).build(), "AWS_ERROR"),
        arguments(SdkException.create(QUOTED, null), "AWS_ERROR"));
  }

  /**
   * BR-17: a refusal is permanent, so the dispatcher fails the message at once, only where sending
   * it again cannot succeed: every conflict, such as the sandbox's unverified number, access
   * denied, every validation error, a missing resource, and a monthly spend limit. Throttling,
   * AWS's own failure, the network, every other quota and any other error may pass, so they are
   * retried. Every quota reason the SDK knows is tried, so a new spend limit cannot pass as
   * retryable.
   */
  @ParameterizedTest(name = "{0}: permanent {1}")
  @MethodSource("permanence")
  void aRefusalIsPermanentOnlyWhereSendingAgainCannotSucceed(
      SdkException thrown, boolean permanent) {
    when(client.sendTextMessage(any(SendTextMessageRequest.class))).thenThrow(thrown);

    Delivery delivery = new AwsSmsGateway(client, NOTHING_OPTIONAL).send(MESSAGE);

    assertThat(delivery).isInstanceOf(Refused.class);
    assertThat(((Refused) delivery).permanent()).isEqualTo(permanent);
  }

  static Stream<Arguments> permanence() {
    Stream<Arguments> kinds =
        Stream.of(
            arguments(
                named(
                    "conflict, not verified",
                    ConflictException.builder()
                        .reason(ConflictExceptionReason.DESTINATION_PHONE_NUMBER_NOT_VERIFIED)
                        .build()),
                true),
            arguments(
                named(
                    "conflict, opted out",
                    ConflictException.builder()
                        .reason(ConflictExceptionReason.DESTINATION_PHONE_NUMBER_OPTED_OUT)
                        .build()),
                true),
            arguments(named("conflict, no reason", ConflictException.builder().build()), true),
            arguments(named("access denied", AccessDeniedException.builder().build()), true),
            arguments(
                named(
                    "validation, over MaxPrice",
                    ValidationException.builder()
                        .reason(ValidationExceptionReason.PRICE_OVER_THRESHOLD)
                        .build()),
                true),
            arguments(named("validation, no reason", ValidationException.builder().build()), true),
            arguments(named("not found", ResourceNotFoundException.builder().build()), true),
            arguments(named("throttled", ThrottlingException.builder().build()), false),
            arguments(named("internal", InternalServerException.builder().build()), false),
            arguments(named("unreachable", SdkClientException.create("test")), false),
            arguments(named("call timeout", ApiCallTimeoutException.create(10_000)), false),
            arguments(named("other AWS", PinpointSmsVoiceV2Exception.builder().build()), false),
            arguments(named("other SDK", SdkException.create("test", null)), false),
            arguments(
                named("quota, no reason", ServiceQuotaExceededException.builder().build()), false));
    Stream<Arguments> quotas =
        Arrays.stream(ServiceQuotaExceededExceptionReason.values())
            .map(
                reason ->
                    arguments(
                        named(
                            "quota, " + reason.name(),
                            ServiceQuotaExceededException.builder().reason(reason).build()),
                        reason.name().startsWith("MONTHLY_SPEND_LIMIT_REACHED_")));
    return Stream.concat(kinds, quotas);
  }

  @Test
  void closingTheGatewayClosesItsClient() {
    new AwsSmsGateway(client, NOTHING_OPTIONAL).close();

    verify(client).close();
  }

  private void answerWithId(String messageId) {
    when(client.sendTextMessage(any(SendTextMessageRequest.class)))
        .thenReturn(SendTextMessageResponse.builder().messageId(messageId).build());
  }

  private SendTextMessageRequest sentRequest() {
    ArgumentCaptor<SendTextMessageRequest> sent =
        ArgumentCaptor.forClass(SendTextMessageRequest.class);
    verify(client).sendTextMessage(sent.capture());
    return sent.getValue();
  }
}
