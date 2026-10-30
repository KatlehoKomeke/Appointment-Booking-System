package za.co.fynbos.branchbook.notification.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How SMS leave the application, under {@code branchbook.sms} in application.yml.
 *
 * <p>The simulated gateway is the default, so a copy run locally or in the tests sends nothing.
 * Where a deployment sets {@code gateway} to {@code aws}, as the hosted demo does, every number
 * outside the published demo block, 082 555 0000 to 0199, is sent a real SMS through AWS End User
 * Messaging SMS; the block itself stays simulated, because it is published for anyone to type and
 * may belong to a real stranger. The per-recipient daily cap limits how many SMS one number can be
 * made to receive, whichever gateway sends them (BR-17).
 *
 * <p>The cap's key is a secret, so {@link #toString()} masks it, and nothing here validates it:
 * Spring Boot would print a rejected value in its start-up failure. {@code RecipientFingerprints}
 * refuses a key that is too short instead, with a message that never quotes it.
 *
 * @param gateway which gateway sends: {@link Gateway#SIMULATED}, the default, or {@link
 *     Gateway#AWS}
 * @param recipientDailyCap the most SMS one number outside the demo block may be written in any 24
 *     hours; a message at or above it is written FAILED with {@code RECIPIENT_DAILY_CAP} and never
 *     sent. {@code 0}, the default, switches the cap off
 * @param recipientCapKey the secret that keys each number's fingerprint in the cap's ledger, at
 *     least 32 characters; never logged or printed. Blank, the default, makes a random key at
 *     start-up, so every number's count starts again whenever the application restarts
 * @param aws how to reach AWS End User Messaging SMS; read only when the gateway is {@link
 *     Gateway#AWS}
 */
@Validated
@ConfigurationProperties("branchbook.sms")
public record SmsProperties(
    @NotNull @DefaultValue("simulated") Gateway gateway,
    @PositiveOrZero int recipientDailyCap,
    @Nullable String recipientCapKey,
    @NotNull @Valid @DefaultValue Aws aws) {

  /**
   * Names every setting but the cap's key, which shows only whether it is set, so no log line or
   * error message can print it.
   *
   * @return the settings, with the key masked
   */
  @Override
  public String toString() {
    String key = recipientCapKey == null || recipientCapKey.isBlank() ? "(blank)" : "(masked)";
    return "SmsProperties[gateway=%s, recipientDailyCap=%d, recipientCapKey=%s, aws=%s]"
        .formatted(gateway, recipientDailyCap, key, aws);
  }

  /** The gateways an SMS can leave through. */
  public enum Gateway {

    /** Nothing is sent: the simulated gateway accepts or refuses every message itself. */
    SIMULATED,

    /**
     * Numbers outside the published demo block are sent a real SMS through AWS End User Messaging
     * SMS; numbers inside it stay simulated.
     */
    AWS
  }

  /**
   * The settings of AWS End User Messaging SMS (the {@code pinpoint-sms-voice-v2} API). Credentials
   * are never set here: they come from the SDK's default provider chain, which in the hosted demo
   * is the ECS task role. A blank optional value is left out of every request.
   *
   * @param region the AWS region to send from, such as {@code af-south-1}; blank leaves it to the
   *     SDK's default region chain, and start-up fails if that finds none
   * @param protectConfigurationId the protect configuration that limits which countries a message
   *     may go to; blank sends none
   * @param configurationSet the configuration set whose event destinations record each message;
   *     blank sends none
   * @param originationIdentity the phone number, pool or sender ID to send from; blank lets AWS
   *     choose, which for South Africa is a shared route
   * @param maxPrice the most, in US dollars, one message may cost, such as {@code 0.20}; AWS
   *     refuses a dearer one. {@code 0.20} by default
   * @param dryRun whether AWS only validates each message and sends none; {@code false} by default
   * @param connectTimeout how long opening a connection to AWS may take; two seconds by default
   * @param readTimeout how long AWS may take to answer once a request is sent; five seconds by
   *     default
   * @param callTimeout how long one {@code SendTextMessage} call may take in all, so the
   *     dispatcher's thread and its row lock are never held for long; ten seconds by default
   */
  public record Aws(
      @Nullable String region,
      @Nullable String protectConfigurationId,
      @Nullable String configurationSet,
      @Nullable String originationIdentity,
      @NotNull @Pattern(regexp = MAX_PRICE_PATTERN) @DefaultValue("0.20") String maxPrice,
      boolean dryRun,
      @NotNull @DefaultValue("PT2S") Duration connectTimeout,
      @NotNull @DefaultValue("PT5S") Duration readTimeout,
      @NotNull @DefaultValue("PT10S") Duration callTimeout) {

    /** The form AWS accepts for {@code MaxPrice}: up to two digits, a point, one to five digits. */
    public static final String MAX_PRICE_PATTERN = "[0-9]{0,2}\\.[0-9]{1,5}";

    /**
     * Refuses a timeout that is zero or negative, which would make every call fail or never end.
     */
    public Aws {
      for (Duration timeout : new Duration[] {connectTimeout, readTimeout, callTimeout}) {
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
          throw new IllegalArgumentException(
              "branchbook.sms.aws timeouts must be more than zero, ISO-8601 durations");
        }
      }
    }
  }
}
