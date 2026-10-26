package za.co.fynbos.branchbook.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;
import static za.co.fynbos.branchbook.support.Bookings.cancel;
import static za.co.fynbos.branchbook.support.Bookings.retry;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import za.co.fynbos.branchbook.notification.application.NotificationDispatcher;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;

/**
 * FR-17, FR-18, BR-19: with {@code branchbook.inbox.redact-references} true, as on the hosted demo,
 * which has no sign-in, the inbox and the retry show every appointment reference as {@code
 * APT-****-****}: in the reference field and in the text of each message. Each message keeps its
 * id, so it can still be retried. {@link InboxApiIT} proves the references are shown while the
 * setting is off, the default. This test builds an application context of its own. The numbers are
 * synthetic.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
@TestPropertySource(properties = "branchbook.inbox.redact-references=true")
class InboxReferencesHiddenIT {

  private static final String HIDDEN = "APT-****-****";

  /** Any reference in the clear: the stored form, in either case, with or without its hyphens. */
  private static final Pattern CLEAR_REFERENCE =
      Pattern.compile("APT-?[A-Z0-9]{4}-?[A-Z0-9]{4}", Pattern.CASE_INSENSITIVE);

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private NotificationDispatcher dispatcher;

  @Autowired private MutableClock clock;

  @Test
  void inboxShowsNoReferenceInAnyFieldOrTextAndKeepsTheIds() {
    String kept =
        reference(book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0187")));
    String cancelled =
        reference(book(mvc, body("BEL", "CARD", "2026-10-28T10:00:00+02:00", "082 555 0188")));
    assertThat(cancel(mvc, cancelled, "0188")).hasStatus(HttpStatus.OK);
    jdbc.update(
        "UPDATE notification_messages SET status = 'FAILED', failure_reason = 'DELIVERY_FAILED',"
            + " last_error = ?, attempts = 5 WHERE kind = 'CONFIRMATION' AND body LIKE ?",
        "REJECTED " + kept,
        "%" + kept + "%");

    MvcTestResult inbox = mvc.get().uri("/api/v1/messages").exchange();

    assertThat(inbox).hasStatusOk();
    String json = json(inbox);
    assertThat(json).doesNotContain(kept).doesNotContain(cancelled);
    assertThat(CLEAR_REFERENCE.matcher(json).find()).as("a reference in the clear").isFalse();
    assertThat(JsonPath.<List<String>>read(json, "$.messages[*].reference"))
        .hasSize(3)
        .containsOnly(HIDDEN);
    assertThat(JsonPath.<List<String>>read(json, "$.messages[*].body"))
        .allSatisfy(text -> assertThat(text).contains("Ref " + HIDDEN + "."));
    assertThat(JsonPath.<List<String>>read(json, "$.messages[?(@.lastError != null)].lastError"))
        .containsExactly("REJECTED " + HIDDEN);
    assertThat(JsonPath.<List<String>>read(json, "$.messages[*].id"))
        .containsExactlyInAnyOrderElementsOf(
            jdbc.queryForList("SELECT id::text FROM notification_messages", String.class));
  }

  @Test
  void retryOfAFailedMessageWorksAndShowsNoReference() {
    String reference =
        reference(book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0189")));
    String id =
        jdbc.queryForObject(
            "SELECT id::text FROM notification_messages WHERE kind = 'CONFIRMATION'", String.class);
    jdbc.update(
        "UPDATE notification_messages SET status = 'FAILED', failure_reason = 'DELIVERY_FAILED',"
            + " last_error = 'SIMULATED_FAILURE', attempts = 5 WHERE id = ?::uuid",
        id);

    MvcTestResult retried = retry(mvc, id);

    assertThat(retried).hasStatus(HttpStatus.OK);
    assertThat(retried)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"id": "%s", "reference": "%s", "kind": "CONFIRMATION", "status": "PENDING",
             "failureReason": null, "attempts": 0, "lastError": null}"""
                .formatted(id, HIDDEN));
    String json = json(retried);
    assertThat(json).doesNotContain(reference);
    assertThat(CLEAR_REFERENCE.matcher(json).find()).as("a reference in the clear").isFalse();
    assertThat(JsonPath.<String>read(json, "$.body")).contains("Ref " + HIDDEN + ".");
    assertThat(dispatcher.runOnce(clock.instant())).isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT body FROM notification_messages WHERE id = ?::uuid", String.class, id))
        .as("only the inbox hides it: the message sent still names the reference")
        .contains(reference);
  }

  private static String reference(MvcTestResult booked) {
    assertThat(booked).hasStatus(HttpStatus.CREATED);
    return JsonPath.read(json(booked), "$.reference");
  }

  private static String json(MvcTestResult result) {
    return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
  }
}
