package za.co.fynbos.branchbook.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import za.co.fynbos.branchbook.support.IntegrationTest;

/**
 * Proves the defences that {@link SecurityConfig} promises, through the full filter chain of the
 * running application.
 */
@IntegrationTest
class SecurityHeadersIT {

  @Autowired private MockMvcTester mvc;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/readyz",
        "/no-such-page",
        "/api/v1/branches",
        "/v3/api-docs",
        "/swagger-ui.html"
      })
  void everyResponseCarriesTheBrowserDefences(String path) {
    assertThat(mvc.get().uri(path))
        .headers()
        .hasValue("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY)
        .hasValue("Permissions-Policy", SecurityConfig.PERMISSIONS_POLICY)
        .hasValue("Referrer-Policy", "no-referrer")
        .hasValue("X-Frame-Options", "DENY")
        .hasValue("X-Content-Type-Options", "nosniff")
        .doesNotContainHeader("Set-Cookie");
  }

  /**
   * Swagger UI's pages are the one place inline styles are allowed, which its rendering needs; the
   * rest of their headers, and the refusal of inline scripts, stay as everywhere else.
   */
  @Test
  void swaggerUiAloneMayUseInlineStyles() {
    assertThat(mvc.get().uri("/swagger-ui/index.html"))
        .hasStatusOk()
        .headers()
        .hasValue("Content-Security-Policy", SecurityConfig.SWAGGER_UI_CONTENT_SECURITY_POLICY)
        .hasValue("X-Frame-Options", "DENY")
        .doesNotContainHeader("Set-Cookie");
    assertThat(SecurityConfig.SWAGGER_UI_CONTENT_SECURITY_POLICY)
        .contains("default-src 'self'")
        .doesNotContain("script-src");
  }

  /**
   * There is no CORS configuration: the app and the API share one origin. A preflight from another
   * origin is answered as a plain OPTIONS request, with no {@code Access-Control-Allow-*} header,
   * so a browser refuses to let any other site read the API through the visitor's browser.
   */
  @Test
  void corsPreflightFromForeignOriginIsNotAllowed() {
    assertThat(
            mvc.options()
                .uri("/api/v1/branches")
                .header("Origin", "https://elsewhere.example")
                .header("Access-Control-Request-Method", "GET"))
        .headers()
        .doesNotContainHeader("Access-Control-Allow-Origin")
        .doesNotContainHeader("Access-Control-Allow-Methods")
        .doesNotContainHeader("Access-Control-Allow-Credentials");
  }

  /**
   * Only health, info and prometheus are exposed (MetricsIT lists them); every other actuator
   * endpoint is a 404, the raw metrics browser and the log-level switch included.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/actuator/env",
        "/actuator/beans",
        "/actuator/heapdump",
        "/actuator/threaddump",
        "/actuator/metrics",
        "/actuator/loggers",
        "/actuator/configprops",
        "/actuator/mappings",
        "/actuator/scheduledtasks"
      })
  void operationalEndpointsBeyondHealthInfoAndPrometheusAreClosed(String path) {
    assertThat(mvc.get().uri(path)).hasStatus(HttpStatus.NOT_FOUND);
  }
}
