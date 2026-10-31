package za.co.fynbos.branchbook.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * The body of {@code GET /api/v1/client-config}.
 *
 * @param captcha how to show the CAPTCHA that protects bookings and cancels; null where there is
 *     none, as on a developer's machine
 */
@Schema(name = "ClientConfig")
public record ClientConfigResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Captcha captcha) {

  /**
   * The AWS WAF CAPTCHA JavaScript API's settings.
   *
   * @param scriptUrl the script to load, {@code <integration URL>jsapi.js}
   * @param apiKey the key to pass to {@code AwsWafCaptcha.renderCaptcha}; public by design, since
   *     AWS binds it to the domains its tokens are valid for
   */
  @Schema(name = "CaptchaConfig")
  public record Captcha(
      @Schema(example = "https://abcd1234.af-south-1.captcha-sdk.awswaf.com/abcd1234/jsapi.js")
          String scriptUrl,
      String apiKey) {}
}
