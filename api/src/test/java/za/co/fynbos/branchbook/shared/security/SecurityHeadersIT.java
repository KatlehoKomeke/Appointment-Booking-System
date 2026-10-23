package za.co.fynbos.branchbook.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

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
  @ValueSource(strings = {"/readyz", "/no-such-page"})
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

  @ParameterizedTest
  @ValueSource(strings = {"/actuator/env", "/actuator/beans", "/actuator/heapdump"})
  void operationalEndpointsBeyondHealthAndInfoAreClosed(String path) {
    assertThat(mvc.get().uri(path)).hasStatus(HttpStatus.NOT_FOUND);
  }
}
