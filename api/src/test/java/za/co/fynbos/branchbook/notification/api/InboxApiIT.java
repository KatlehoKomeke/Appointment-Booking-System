package za.co.fynbos.branchbook.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;
import za.co.fynbos.branchbook.support.ResponseText;

/**
 * FR-17, BR-19: {@code GET /api/v1/messages}, the simulated inbox. It lists every booking's
 * messages newest first, each with its appointment's reference, and never a digit of the recipient
 * after the prefix. The numbers are synthetic.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class InboxApiIT {

  @Autowired private MockMvcTester mvc;

  @Autowired private MutableClock clock;

  @Test
  void listsTheNewestMessagesWithTheirReferencesAndRedactedRecipients() throws Exception {
    String first =
        reference(book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0183")));
    clock.advance(Duration.ofMinutes(5));
    String second =
        reference(book(mvc, body("BEL", "CARD", "2026-10-28T10:00:00+02:00", "082 555 0184")));

    MvcTestResult inbox = mvc.get().uri("/api/v1/messages").exchange();

    assertThat(inbox).hasStatusOk();
    assertThat(inbox)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"messages": [
              {"reference": "%s", "kind": "CONFIRMATION", "channel": "SMS",
               "recipientMasked": "082 *** ****", "subject": null, "status": "PENDING",
               "failureReason": null, "retryable": false, "attempts": 0,
               "nextAttemptAt": "2026-10-26T08:05:00+02:00", "lastError": null,
               "createdAt": "2026-10-26T08:05:00+02:00", "sentAt": null},
              {"reference": "%s", "createdAt": "2026-10-26T08:00:00+02:00"}
            ]}"""
                .formatted(second, first));
    assertThat(string(inbox, "$.messages[0].body")).contains(second);
    assertThat(ResponseText.withoutIdentifiers(inbox.getResponse().getContentAsString()))
        .doesNotContain("0183")
        .doesNotContain("0184")
        .doesNotContain("555");
    assertThat(inbox.getResponse().getHeader("Cache-Control")).contains("no-store");
  }

  @Test
  void limitBoundsTheList() {
    book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0185"));
    book(mvc, body("BEL", "CARD", "2026-10-28T10:00:00+02:00", "082 555 0186"));

    assertThat(mvc.get().uri("/api/v1/messages?limit=1"))
        .bodyJson()
        .extractingPath("$.messages.length()")
        .isEqualTo(1);
  }

  @Test
  void limitOutsideOneToTwoHundredReturns400NamingTheParameter() {
    for (Map.Entry<String, String> outOfRange : Map.of("0", "MIN", "201", "MAX").entrySet()) {
      MvcTestResult refused =
          mvc.get().uri("/api/v1/messages?limit=" + outOfRange.getKey()).exchange();

      assertThat(refused).hasStatus(HttpStatus.BAD_REQUEST);
      assertThat(refused)
          .bodyJson()
          .extractingPath("$.errors[*]['field','code']")
          .asArray()
          .containsExactly(Map.of("field", "limit", "code", outOfRange.getValue()));
    }
  }

  @Test
  void emptyInboxIsAnEmptyList() {
    assertThat(mvc.get().uri("/api/v1/messages"))
        .bodyJson()
        .extractingPath("$.messages")
        .isEqualTo(List.of());
  }

  private static String reference(MvcTestResult booked) {
    return string(booked, "$.reference");
  }

  private static String string(MvcTestResult result, String path) {
    return JsonPath.read(
        new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), path);
  }
}
