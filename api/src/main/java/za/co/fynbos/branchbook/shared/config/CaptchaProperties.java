package za.co.fynbos.branchbook.shared.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The AWS WAF CAPTCHA settings the web app needs, under {@code branchbook.captcha}. Staging and
 * production put a CAPTCHA in front of every booking and cancel at the edge: a request without a
 * valid token is answered {@code 405} with {@code x-amzn-waf-action: captcha}, and the web app then
 * shows AWS's puzzle with the CAPTCHA JavaScript API and sends the request again. Locally there is
 * no edge, so both settings are empty and there is no CAPTCHA.
 *
 * <p>Both are public by design: the script is AWS's, and AWS binds the API key to the domains its
 * tokens are valid for. Start-up fails if only one is set, or if the script is not an {@code https}
 * address under {@code awswaf.com}, the domain the Content-Security-Policy then allows.
 *
 * @param scriptUrl the CAPTCHA JavaScript API, the account's integration URL followed by {@code
 *     jsapi.js}; blank when there is no CAPTCHA
 * @param apiKey the CAPTCHA API key the web app passes to {@code renderCaptcha}; blank when there
 *     is no CAPTCHA
 */
@ConfigurationProperties("branchbook.captcha")
public record CaptchaProperties(@Nullable String scriptUrl, @Nullable String apiKey) {

  /** The apex domain every AWS WAF JavaScript endpoint is under. */
  public static final String AWS_WAF_DOMAIN = "awswaf.com";

  /** Refuses half a configuration, and a script from anywhere but AWS WAF over https. */
  public CaptchaProperties {
    boolean hasScript = hasText(scriptUrl);
    if (hasScript != hasText(apiKey)) {
      throw new IllegalArgumentException(
          "branchbook.captcha.script-url and branchbook.captcha.api-key must be set together");
    }
    if (hasScript && !isAwsWafScript(scriptUrl.strip())) {
      throw new IllegalArgumentException(
          "branchbook.captcha.script-url must be an https address under " + AWS_WAF_DOMAIN);
    }
  }

  /**
   * Returns the settings the web app needs, when a CAPTCHA is configured.
   *
   * @return the script and the key, both stripped; empty when there is no CAPTCHA
   */
  public Optional<Captcha> captcha() {
    if (!hasText(scriptUrl) || !hasText(apiKey)) {
      return Optional.empty();
    }
    return Optional.of(new Captcha(scriptUrl.strip(), apiKey.strip()));
  }

  private static boolean isAwsWafScript(String url) {
    try {
      URI uri = new URI(url);
      String host = uri.getHost();
      return "https".equals(uri.getScheme())
          && host != null
          && host.toLowerCase(Locale.ROOT).endsWith("." + AWS_WAF_DOMAIN);
    } catch (URISyntaxException malformed) {
      return false;
    }
  }

  private static boolean hasText(@Nullable String value) {
    return value != null && !value.isBlank();
  }

  /**
   * A configured CAPTCHA.
   *
   * @param scriptUrl the CAPTCHA JavaScript API's address
   * @param apiKey the API key for {@code renderCaptcha}
   */
  public record Captcha(String scriptUrl, String apiKey) {}
}
