package za.co.fynbos.branchbook.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import za.co.fynbos.branchbook.support.IntegrationTest;

/**
 * Where a CAPTCHA protects bookings and cancels, as in staging and production, {@code GET
 * /api/v1/client-config} publishes AWS WAF's CAPTCHA script and API key, and the
 * Content-Security-Policy lets the page load that script and let it call AWS WAF: {@code
 * https://*.awswaf.com} in {@code script-src} and {@code connect-src}, as AWS's documentation asks.
 * Every other directive is unchanged.
 */
@IntegrationTest
@TestPropertySource(
    properties = {
      "branchbook.captcha.script-url=" + CaptchaConfiguredIT.SCRIPT,
      "branchbook.captcha.api-key=public-api-key-1"
    })
class CaptchaConfiguredIT {

  static final String SCRIPT =
      "https://abcd1234.af-south-1.captcha-sdk.awswaf.com/abcd1234/jsapi.js";

  @Autowired private MockMvcTester mvc;

  @Test
  void theClientConfigPublishesTheCaptchaScriptAndKey() {
    assertThat(mvc.get().uri("/api/v1/client-config"))
        .hasStatusOk()
        .bodyJson()
        .isStrictlyEqualTo(
            "{\"captcha\":{\"scriptUrl\":\"" + SCRIPT + "\",\"apiKey\":\"public-api-key-1\"}}");
  }

  @Test
  void thePolicyAllowsAwsWafForScriptsAndConnections() {
    assertThat(mvc.get().uri("/"))
        .headers()
        .hasValue(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self'; "
                + "form-action 'self'; frame-ancestors 'none'; "
                + "script-src 'self' https://*.awswaf.com; connect-src 'self' https://*.awswaf.com");
  }
}
