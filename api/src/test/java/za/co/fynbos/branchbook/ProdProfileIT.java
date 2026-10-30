package za.co.fynbos.branchbook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import za.co.fynbos.branchbook.support.TestcontainersConfiguration;

/**
 * The prod profile that staging and production run with, over real HTTP on real ports: the API
 * document, Swagger UI and the simulated inbox are not served, the actuator answers only on its own
 * management port, which the load balancer never routes, and the probes stay on the main port for
 * the load balancer's health check.
 *
 * <p>The management port is random here ({@code 0}) so parallel runs cannot collide;
 * application-prod.yml's default, 8081, is proven by {@code ConfigurationReferenceTest}. The
 * database is empty, so this context migrates at start-up, which staging and production leave to
 * the migrate-only task. The public base URL is https, as the profile's guard requires. Metrics are
 * exported as the application exports them, which Spring Boot switches off in tests by default.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "branchbook.notification.public-base-url=https://example.test",
      "management.server.port=0",
      "spring.flyway.enabled=true",
      "branchbook.dispatcher.enabled=false",
      "branchbook.inbox.enabled=false",
      "branchbook.inbox.redact-references=true",
      "branchbook.demo-data.enabled=false",
      "branchbook.sms.gateway=simulated",
      "branchbook.sms.recipient-daily-cap=0",
      "branchbook.captcha.script-url=",
      "branchbook.captcha.api-key="
    })
@ActiveProfiles("prod")
@AutoConfigureMetrics
@Import(TestcontainersConfiguration.class)
class ProdProfileIT {

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort private int port;

  @LocalManagementPort private int managementPort;

  @Test
  void theActuatorHasAPortOfItsOwn() {
    assertThat(managementPort).isPositive().isNotEqualTo(port);
  }

  /** The load balancer checks /readyz on the main port; liveness stays there too. */
  @ParameterizedTest
  @ValueSource(strings = {"/readyz", "/livez"})
  void theProbesStayOnTheMainPort(String path) throws Exception {
    HttpResponse<String> response = get(port, path);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
  }

  /** Nothing under /actuator is served where the load balancer routes. */
  @ParameterizedTest
  @ValueSource(
      strings = {"/actuator/health", "/actuator/prometheus", "/actuator/info", "/actuator/env"})
  void theMainPortServesNoActuatorEndpoint(String path) throws Exception {
    assertThat(get(port, path).statusCode()).isEqualTo(404);
  }

  /** Health, info and the metrics are read on the management port, from inside the network. */
  @ParameterizedTest
  @ValueSource(strings = {"/actuator/health", "/actuator/prometheus", "/actuator/info"})
  void theManagementPortServesTheActuator(String path) throws Exception {
    assertThat(get(managementPort, path).statusCode()).isEqualTo(200);
  }

  /** The contract is in the repository; a deployment does not serve it. */
  @ParameterizedTest
  @ValueSource(
      strings = {"/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/index.html"})
  void theApiDocumentAndSwaggerUiAreNotServed(String path) throws Exception {
    assertThat(get(port, path).statusCode()).isEqualTo(404);
  }

  @Test
  void theSimulatedInboxIsNotServed() throws Exception {
    assertThat(get(port, "/api/v1/messages").statusCode()).isEqualTo(404);
  }

  /** The API itself is served as everywhere else. */
  @Test
  void theApiIsServed() throws Exception {
    HttpResponse<String> response = get(port, "/api/v1/branches");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"code\":\"SAN\"");
  }

  private HttpResponse<String> get(int onPort, String path)
      throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + onPort + path)).build(),
        BodyHandlers.ofString());
  }
}
