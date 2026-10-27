package za.co.fynbos.branchbook.notification.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;

/**
 * Turns a mobile number into the fingerprint the per-recipient daily cap's ledger holds in its
 * place (BR-17, V7): HMAC-SHA256 of the number's E.164 form, in lower-case hex. A plain hash of a
 * South African mobile number could be reversed by hashing all 250 million of them; without the
 * key, a fingerprint cannot be matched to a number.
 *
 * <p>The key is {@code branchbook.sms.recipient-cap-key}, at least {@value #MIN_KEY_LENGTH}
 * characters; a shorter one stops start-up, with a message that never quotes it. Where it is blank,
 * the default, a key of 32 random bytes is made at start-up, which no one ever sees, so every
 * number's count starts again whenever the application restarts; while the cap is on, one WARN line
 * says so. The key is never logged or printed.
 */
@Component
class RecipientFingerprints {

  private static final Logger log = LoggerFactory.getLogger(RecipientFingerprints.class);

  /** The fewest characters a configured key may have. */
  static final int MIN_KEY_LENGTH = 32;

  private static final String ALGORITHM = "HmacSHA256";

  /** How many random bytes a key made at start-up has: as many as the hash's output. */
  private static final int RANDOM_KEY_BYTES = 32;

  private final SecretKeySpec key;

  RecipientFingerprints(SmsProperties sms) {
    String configured = sms.recipientCapKey();
    if (configured == null || configured.isBlank()) {
      key = new SecretKeySpec(randomKey(), ALGORITHM);
      if (sms.recipientDailyCap() > 0) {
        log.warn(
            "Recipient daily cap: branchbook.sms.recipient-cap-key is not set, so the ledger's"
                + " fingerprints use a random key made at start-up, and every number's count"
                + " starts again from zero whenever the application restarts.");
      }
    } else if (configured.length() < MIN_KEY_LENGTH) {
      throw new IllegalStateException(
          "branchbook.sms.recipient-cap-key must be at least " + MIN_KEY_LENGTH + " characters");
    } else {
      key = new SecretKeySpec(configured.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }
    // Fails start-up, rather than the first booking, should the JDK not offer the algorithm.
    mac();
  }

  /**
   * Returns the number's fingerprint.
   *
   * @param number the mobile number
   * @return HMAC-SHA256 of its E.164 form under the key: 64 lower-case hex characters
   */
  String of(MobileNumber number) {
    return HexFormat.of().formatHex(mac().doFinal(number.e164().getBytes(StandardCharsets.UTF_8)));
  }

  /** A MAC under the key. A {@link Mac} is not thread-safe, so each fingerprint has its own. */
  private Mac mac() {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return mac;
    } catch (GeneralSecurityException unavailable) {
      throw new IllegalStateException(ALGORITHM + " is not available", unavailable);
    }
  }

  private static byte[] randomKey() {
    byte[] random = new byte[RANDOM_KEY_BYTES];
    new SecureRandom().nextBytes(random);
    return random;
  }
}
