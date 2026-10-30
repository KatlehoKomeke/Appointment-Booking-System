package za.co.fynbos.branchbook.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import za.co.fynbos.branchbook.support.IntegrationTest;

/**
 * Where no CAPTCHA is configured, as on a developer's machine and in CI, {@code GET
 * /api/v1/client-config} says so with {@code "captcha": null}, and the web app never asks for a
 * token. {@code CaptchaConfiguredIT} proves the configured case.
 */
@IntegrationTest
class ClientConfigApiIT {

  @Autowired private MockMvcTester mvc;

  @Test
  void withoutACaptchaTheClientConfigSaysNull() {
    assertThat(mvc.get().uri("/api/v1/client-config"))
        .hasStatusOk()
        .bodyJson()
        .isStrictlyEqualTo("{\"captcha\":null}");
  }
}
