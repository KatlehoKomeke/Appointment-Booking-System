package za.co.fynbos.branchbook.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;

/**
 * BR-17, V7: the daily cap's ledger holds a number's fingerprint, HMAC-SHA256 of its E.164 form
 * under {@code branchbook.sms.recipient-cap-key}, never the number. A configured key gives the same
 * fingerprint on every start; a blank one gives a random key each time, with one WARN while the cap
 * is on; a key shorter than 32 characters stops start-up, and is never printed.
 */
@ExtendWith(OutputCaptureExtension.class)
class RecipientFingerprintsTest {

  /** A test value, of low entropy so no secret scanner mistakes it for a real key. */
  private static final String KEY = "a-test-key-a-test-key-a-test-key-a-test-key";

  private static final String WARNING =
      "Recipient daily cap: branchbook.sms.recipient-cap-key is not set, so the ledger's"
          + " fingerprints use a random key made at start-up, and every number's count starts"
          + " again from zero whenever the application restarts.";

  private static final MobileNumber NUMBER = MobileNumber.ofE164("+27825550200");

  @Test
  void theFingerprintIsTheHmacOfTheE164NumberUnderTheKey() throws GeneralSecurityException {
    RecipientFingerprints fingerprints = new RecipientFingerprints(sms(KEY, 5));

    String fingerprint = fingerprints.of(NUMBER);

    assertThat(fingerprint).isEqualTo(hmacSha256(KEY, "+27825550200")).matches("[0-9a-f]{64}");
    assertThat(fingerprints.of(MobileNumber.parse("082 555 0200").orElseThrow()))
        .as("the number as typed is fingerprinted in its E.164 form")
        .isEqualTo(fingerprint);
    assertThat(new RecipientFingerprints(sms(KEY, 5)).of(NUMBER))
        .as("the same key after a restart")
        .isEqualTo(fingerprint);
    assertThat(fingerprints.of(MobileNumber.ofE164("+27825550201"))).isNotEqualTo(fingerprint);
  }

  @Test
  void aBlankKeyMakesARandomKeyAndOneWarningWhileTheCapIsOn(CapturedOutput output) {
    RecipientFingerprints first = new RecipientFingerprints(sms(null, 5));

    assertThat(output.getAll())
        .containsOnlyOnce("recipient-cap-key is not set")
        .containsPattern("WARN.*" + Pattern.quote(WARNING));
    RecipientFingerprints second = new RecipientFingerprints(sms(" ", 5));
    assertThat(second.of(NUMBER))
        .as("two starts with no key fingerprint one number differently")
        .isNotEqualTo(first.of(NUMBER))
        .matches("[0-9a-f]{64}");
  }

  @Test
  void aBlankKeyWarnsNothingWhileTheCapIsOff(CapturedOutput output) {
    new RecipientFingerprints(sms("", 0));

    assertThat(output.getAll()).doesNotContain("recipient-cap-key");
  }

  @Test
  void aKeyOfThirtyTwoCharactersIsEnough() {
    String key = "k".repeat(32);

    assertThat(new RecipientFingerprints(sms(key, 5)).of(NUMBER)).hasSize(64);
  }

  @Test
  void aShorterKeyIsRefusedWithoutQuotingIt() {
    String key = "k".repeat(30) + "Z";

    assertThatIllegalStateException()
        .isThrownBy(() -> new RecipientFingerprints(sms(key, 0)))
        .withMessage("branchbook.sms.recipient-cap-key must be at least 32 characters");
  }

  /**
   * At start-up, as the application builds it: a short key stops the context, and nothing in the
   * failure, or in the settings' own description, quotes the key.
   */
  @Test
  void aShortKeyStopsStartUpAndIsNeverPrinted(CapturedOutput output) {
    String key = "short-key-Z";
    new ApplicationContextRunner()
        .withUserConfiguration(SmsSettings.class)
        .withBean(RecipientFingerprints.class)
        .withInitializer(
            started ->
                started
                    .getEnvironment()
                    .getPropertySources()
                    .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of(
                                "BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP",
                                "5",
                                "BRANCHBOOK_SMS_RECIPIENT_CAP_KEY",
                                key))))
        .run(
            started -> {
              assertThat(started)
                  .hasFailed()
                  .getFailure()
                  .rootCause()
                  .hasMessage("branchbook.sms.recipient-cap-key must be at least 32 characters");
              Throwable failure = started.getStartupFailure();
              for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                assertThat(String.valueOf(cause.getMessage())).doesNotContain(key);
              }
            });

    assertThat(output.getAll()).doesNotContain(key);
  }

  private static SmsProperties sms(@Nullable String key, int cap) {
    return new SmsProperties(
        SmsProperties.Gateway.SIMULATED,
        cap,
        key,
        new SmsProperties.Aws(null, null, null, null, "0.20", false));
  }

  /** The fingerprint as the test works it out: HMAC-SHA256 under the key, in lower-case hex. */
  private static String hmacSha256(String key, String text) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
  }

  /** Binds the SMS settings alone. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(SmsProperties.class)
  static class SmsSettings {}
}
