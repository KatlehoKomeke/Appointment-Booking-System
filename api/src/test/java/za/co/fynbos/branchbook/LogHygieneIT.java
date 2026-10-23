package za.co.fynbos.branchbook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import za.co.fynbos.branchbook.support.IntegrationTest;

/**
 * BR-19, for what M1 serves: a mobile number a caller puts in a URL or a header reaches neither the
 * response nor the log, with the application's real logging configuration. The booking flow adds
 * LogRedactionTest in M3.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LogHygieneIT {

  /** A synthetic number from the demo range in SECURITY.md. */
  private static final String MOBILE_DIGITS = "825550101";

  @Autowired private MockMvcTester mvc;

  @Test
  void aNumberTypedIntoAnUnknownPathIsNeitherEchoedNorLogged(CapturedOutput output)
      throws Exception {
    var result = mvc.get().uri("/api/v1/nothing-here/+27" + MOBILE_DIGITS).exchange();

    assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
    assertThat(result.getResponse().getContentAsString()).doesNotContain(MOBILE_DIGITS);
    assertThat(output).doesNotContain(MOBILE_DIGITS);
  }

  @Test
  void aNumberSentAsTheCorrelationIdIsReplaced(CapturedOutput output) throws Exception {
    var result =
        mvc.get()
            .uri("/api/v1/branches")
            .header("X-Correlation-Id", "+27" + MOBILE_DIGITS)
            .exchange();

    assertThat(result).hasStatusOk();
    assertThat(result.getResponse().getHeader("X-Correlation-Id")).doesNotContain(MOBILE_DIGITS);
    assertThat(output).doesNotContain(MOBILE_DIGITS);
  }
}
