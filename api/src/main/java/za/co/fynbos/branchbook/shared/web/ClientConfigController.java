package za.co.fynbos.branchbook.shared.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.fynbos.branchbook.shared.config.CaptchaProperties;

/**
 * Tells the web app how this deployment is set up at the edge, so one build of the app runs in
 * every environment: today, whether there is a CAPTCHA in front of bookings and cancels, and how to
 * show it ({@code branchbook.captcha}). Everything it returns is public by design.
 */
@RestController
@RequestMapping(path = "/api/v1/client-config", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Client configuration", description = "How the web app should talk to this deployment.")
class ClientConfigController {

  private final ClientConfigResponse response;

  ClientConfigController(CaptchaProperties captcha) {
    this.response =
        new ClientConfigResponse(
            captcha
                .captcha()
                .map(c -> new ClientConfigResponse.Captcha(c.scriptUrl(), c.apiKey()))
                .orElse(null));
  }

  /**
   * Describes the deployment's client settings.
   *
   * @return the settings; {@code captcha} is null where there is no CAPTCHA
   */
  @Operation(
      operationId = "getClientConfig",
      summary = "Describe this deployment's client settings",
      description =
          "Where a CAPTCHA protects bookings and cancels, the AWS WAF CAPTCHA JavaScript API to"
              + " load and the API key to render it with; a booking or cancel without a valid"
              + " token is then answered 405 with the header x-amzn-waf-action: captcha. Null"
              + " where there is none.")
  @ApiResponse(responseCode = "200", description = "The client settings")
  @GetMapping
  ClientConfigResponse get() {
    return response;
  }
}
