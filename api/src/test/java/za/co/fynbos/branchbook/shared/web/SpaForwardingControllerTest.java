package za.co.fynbos.branchbook.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Proves which paths serve the React app, without starting Spring or a database. */
class SpaForwardingControllerTest {

  private final MockMvcTester mvc = MockMvcTester.of(new SpaForwardingController());

  @ParameterizedTest
  @ValueSource(
      strings = {"/book", "/manage", "/messages", "/privacy", "/appointments/APT-7K3M-9Q2X"})
  void appRoutesServeTheAppPage(String route) {
    assertThat(mvc.get().uri(route)).hasForwardedUrl("/index.html");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/no-such-page",
        "/appointments",
        "/api/v1/appointments/APT-7K3M-9Q2X",
        "/actuator/health"
      })
  void otherPathsAreNotTurnedIntoTheAppPage(String path) {
    assertThat(mvc.get().uri(path)).hasStatus(HttpStatus.NOT_FOUND);
  }
}
