package za.co.fynbos.branchbook.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** BR-18: a base URL that could push a confirmation past one SMS stops the application starting. */
class NotificationPropertiesTest {

  @Test
  void rejectsBaseUrlThatOverflowsSms() {
    String longest = "https://" + "b".repeat(20);

    assertThat(new NotificationProperties(longest).publicBaseUrl()).hasSize(28);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new NotificationProperties(longest + "b"))
        .withMessageContaining("too long");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://localhost:8080/",
        "ftp://localhost",
        "localhost:8080",
        "http://local host"
      })
  void rejectsATrailingSlashOrAnAddressThatIsNotHttp(String baseUrl) {
    assertThatIllegalArgumentException().isThrownBy(() -> new NotificationProperties(baseUrl));
  }

  @Test
  void rejectsABaseUrlOutsideTheGsm7Alphabet() {
    String gWithBreve = Character.toString(0x011F);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> new NotificationProperties("https://" + gWithBreve + ".co.za"))
        .withMessageContaining("GSM-7");
  }

  @Test
  void acceptsTheLocalAddress() {
    assertThat(new NotificationProperties("http://localhost:8080").publicBaseUrl())
        .isEqualTo("http://localhost:8080");
  }
}
