package za.co.fynbos.branchbook.notification.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import za.co.fynbos.branchbook.support.IntegrationTest;

/**
 * FR-17: with {@code branchbook.inbox.enabled} false, the application's default, the inbox does not
 * exist: its path is a plain 404 with no code, like any other unknown path. This test builds a
 * second application context, the only one that differs from {@link IntegrationTest}'s.
 */
@IntegrationTest
@TestPropertySource(properties = "branchbook.inbox.enabled=false")
class InboxSwitchedOffIT {

  @Autowired private MockMvcTester mvc;

  @Test
  void inboxIsAPlain404WhenSwitchedOff() throws Exception {
    MvcTestResult inbox = mvc.get().uri("/api/v1/messages").exchange();

    assertThat(inbox).hasStatus(HttpStatus.NOT_FOUND);
    assertThat(inbox.getResponse().getContentAsString()).doesNotContain("\"code\"");
  }
}
