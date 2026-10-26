package za.co.fynbos.branchbook.notification.application;

import jakarta.validation.constraints.NotBlank;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import za.co.fynbos.branchbook.notification.domain.Gsm7;
import za.co.fynbos.branchbook.notification.domain.NotificationTemplates;

/**
 * The notification settings, under {@code branchbook.notification}.
 *
 * <p>The application refuses to start with a base URL that could push a confirmation or a
 * cancellation over one SMS segment, so a configuration mistake shows at start-up, never in a
 * customer's message (BR-18).
 *
 * @param publicBaseUrl the address customers reach the app at, without a trailing slash, for
 *     example {@code http://localhost:8080}; it ends the confirmation SMS's link to {@code /manage}
 *     and the cancellation SMS's link to {@code /book}
 */
@Validated
@ConfigurationProperties("branchbook.notification")
public record NotificationProperties(@NotBlank String publicBaseUrl) {

  /** An http or https address with no trailing slash. */
  private static final Pattern BASE_URL = Pattern.compile("^https?://[^/\\s]+(/[^\\s]*[^/\\s])?$");

  /**
   * Refuses a base URL that is malformed, ends with a slash, has a character outside the GSM-7
   * alphabet, or would make the longest confirmation or cancellation exceed one segment.
   */
  public NotificationProperties {
    if (publicBaseUrl == null || !BASE_URL.matcher(publicBaseUrl).matches()) {
      throw new IllegalArgumentException(
          "branchbook.notification.public-base-url must be an http(s) address without a trailing"
              + " slash");
    }
    if (!Gsm7.isEncodable(publicBaseUrl)) {
      throw new IllegalArgumentException(
          "branchbook.notification.public-base-url must use only GSM-7 characters");
    }
    int septets =
        Math.max(
            NotificationTemplates.longestConfirmationSeptets(publicBaseUrl),
            NotificationTemplates.longestCancellationSeptets(publicBaseUrl));
    if (septets > Gsm7.SINGLE_SEGMENT) {
      throw new IllegalArgumentException(
          "branchbook.notification.public-base-url is too long: the longest SMS would take "
              + septets
              + " characters, more than one segment of "
              + Gsm7.SINGLE_SEGMENT);
    }
  }
}
