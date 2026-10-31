package za.co.fynbos.branchbook.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The CAPTCHA settings stop start-up when they are half set, or when the script would come from
 * anywhere but AWS WAF over https: the Content-Security-Policy allows AWS WAF's domain alone.
 */
class CaptchaPropertiesTest {

  private static final String SCRIPT =
      "https://abcd1234.af-south-1.captcha-sdk.awswaf.com/abcd1234/jsapi.js";

  @Test
  void unsetThereIsNoCaptcha() {
    assertThat(new CaptchaProperties(null, null).captcha()).isEmpty();
    assertThat(new CaptchaProperties("", " ").captcha()).isEmpty();
  }

  @Test
  void setTheScriptAndKeyArePublished() {
    assertThat(new CaptchaProperties(" " + SCRIPT + " ", "key-1").captcha())
        .contains(new CaptchaProperties.Captcha(SCRIPT, "key-1"));
  }

  @Test
  void halfAConfigurationIsRefused() {
    assertThatIllegalArgumentException().isThrownBy(() -> new CaptchaProperties(SCRIPT, ""));
    assertThatIllegalArgumentException().isThrownBy(() -> new CaptchaProperties("", "key-1"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://abcd1234.af-south-1.captcha-sdk.awswaf.com/abcd1234/jsapi.js",
        "https://captcha.example.test/jsapi.js",
        "https://awswaf.com.example.test/jsapi.js",
        "https://evilawswaf.com/jsapi.js",
        "not a url"
      })
  void aScriptFromAnywhereButAwsWafOverHttpsIsRefused(String script) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new CaptchaProperties(script, "key-1"))
        .withMessageContaining("awswaf.com");
  }
}
