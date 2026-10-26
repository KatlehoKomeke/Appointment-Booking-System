package za.co.fynbos.branchbook.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.book;
import static za.co.fynbos.branchbook.support.Bookings.lookup;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.support.Bookings;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;
import za.co.fynbos.branchbook.support.TestClockConfiguration;

/**
 * ADR-0005: {@code docs/openapi.yaml}, the committed contract the web app's types are generated
 * from, is exactly what the running API describes. An API change that is not reflected in the file
 * fails the build, so the web app can never compile against a stale contract.
 *
 * <p>When the change is intended, {@code make contract} regenerates the file, by running this test
 * with {@code -Dopenapi.update=true}, and then the web app's types from it. Commit both with the
 * change.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class OpenApiContractIT {

  /** The committed contract, relative to the api module, where Maven runs the tests. */
  private static final Path COMMITTED = Path.of("..", "docs", "openapi.yaml");

  @Autowired private MockMvcTester mvc;

  @Autowired private MutableClock clock;

  @Test
  void committedContractMatchesTheRunningApi() throws IOException {
    MvcTestResult result = mvc.get().uri("/v3/api-docs.yaml").exchange();
    assertThat(result).hasStatusOk();
    String generated = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

    if (Boolean.getBoolean("openapi.update")) {
      Files.writeString(COMMITTED, generated, StandardCharsets.UTF_8);
    }

    assertThat(COMMITTED)
        .as(
            "docs/openapi.yaml differs from the running API; if the change is intended, run"
                + " make contract and commit docs/openapi.yaml and web/src/api/schema.d.ts")
        .exists()
        .hasContent(generated);
  }

  /**
   * JSON Schema matches a {@code pattern} anywhere in the value, while Bean Validation's
   * {@code @Pattern} must match all of it. Anchoring every pattern makes a client that validates
   * against the document accept exactly what the server does.
   */
  @Test
  void everyPatternIsAnchoredSoClientsMatchTheWholeValue() {
    assertThat(mvc.get().uri("/v3/api-docs"))
        .bodyJson()
        .extractingPath("$..pattern")
        .asArray()
        .isNotEmpty()
        .allSatisfy(pattern -> assertThat((String) pattern).startsWith("^").endsWith("$"));
  }

  /**
   * The Problem schema's {@code code} lists exactly the codes in {@link ProblemCode}, the catalogue
   * that ProblemCodeCatalogueTest keeps in step with docs/errors.md.
   */
  @Test
  void problemCodeListsEveryProblemCode() {
    assertThat(mvc.get().uri("/v3/api-docs"))
        .bodyJson()
        .extractingPath("$.components.schemas.Problem.properties.code.enum")
        .asArray()
        .containsExactly(Arrays.stream(ProblemCode.values()).map(ProblemCode::name).toArray());
  }

  /**
   * BR-12: the document tells a client that a booking needs an {@code Idempotency-Key} holding a
   * UUID, so a generated client cannot leave it out.
   */
  @Test
  void specDeclaresTheIdempotencyKeyHeader() {
    assertThat(mvc.get().uri("/v3/api-docs"))
        .bodyJson()
        .extractingPath(
            "$.paths['/api/v1/appointments'].post.parameters[?(@.name == 'Idempotency-Key')]")
        .asArray()
        .singleElement()
        .satisfies(
            header ->
                assertThat(header)
                    .asInstanceOf(InstanceOfAssertFactories.MAP)
                    .containsEntry("in", "header")
                    .containsEntry("required", true)
                    .extracting("schema")
                    .asInstanceOf(InstanceOfAssertFactories.MAP)
                    .containsEntry("format", "uuid"));
  }

  /**
   * BR-13: the document tells a client that opening an appointment needs {@code X-Mobile-Last4},
   * four digits, and that a {@code 429} says when to try again.
   */
  @Test
  void specDeclaresTheMobileLast4HeaderAndRetryAfter() {
    String lookupPath = "$.paths['/api/v1/appointments/{reference}'].get";

    assertThat(mvc.get().uri("/v3/api-docs"))
        .bodyJson()
        .extractingPath(lookupPath + ".parameters[?(@.name == 'X-Mobile-Last4')]")
        .asArray()
        .singleElement()
        .satisfies(
            header ->
                assertThat(header)
                    .asInstanceOf(InstanceOfAssertFactories.MAP)
                    .containsEntry("in", "header")
                    .containsEntry("required", true)
                    .extracting("schema")
                    .asInstanceOf(InstanceOfAssertFactories.MAP)
                    .containsEntry("pattern", "^[0-9]{4}$"));
    assertThat(mvc.get().uri("/v3/api-docs"))
        .bodyJson()
        .extractingPath(lookupPath + ".responses['429'].headers")
        .asInstanceOf(InstanceOfAssertFactories.MAP)
        .containsKey("Retry-After");
  }

  /**
   * The document must describe what the API really sends, not only agree with itself. Each real
   * body below carries every property its schema requires and none the schema does not declare:
   * successes (among them a cancel), problems with a code (among them a lookup's 400, 404 and 429,
   * and a cancel's 409), and the HTTP-level problems that have none. On the default test clock,
   * Monday 26 October 2026, 27 October is bookable.
   */
  @Test
  void realResponsesHaveExactlyThePropertiesTheirSchemasDeclare() {
    String document = body(mvc.get().uri("/v3/api-docs").exchange());
    String availability =
        body(
            mvc.get()
                .uri("/api/v1/branches/SAN/availability?service=FICA&date=2026-10-27")
                .exchange());
    List<String> mismatches = new ArrayList<>();

    check(document, "Availability", availability, "$", mismatches);
    check(document, "Slot", availability, "$.slots[0]", mismatches);
    check(
        document,
        "BranchList",
        body(mvc.get().uri("/api/v1/branches").exchange()),
        "$",
        mismatches);
    check(
        document,
        "BranchSummary",
        body(mvc.get().uri("/api/v1/branches").exchange()),
        "$.branches[0]",
        mismatches);
    check(
        document,
        "BranchDetail",
        body(mvc.get().uri("/api/v1/branches/SAN").exchange()),
        "$",
        mismatches);
    String booked =
        body(book(mvc, Bookings.body("POL", "FICA", "2026-10-27T09:00:00+02:00", "082 555 0195")));
    check(document, "Appointment", booked, "$", mismatches);
    check(document, "AppointmentBranch", booked, "$.branch", mismatches);
    check(document, "AppointmentService", booked, "$.service", mismatches);
    check(document, "AppointmentCustomer", booked, "$.customer", mismatches);
    check(document, "Notification", booked, "$.notifications[0]", mismatches);
    String reference = JsonPath.read(booked, "$.reference");
    check(document, "Appointment", body(lookup(mvc, reference, "0195")), "$", mismatches);
    String inbox = body(mvc.get().uri("/api/v1/messages").exchange());
    check(document, "Inbox", inbox, "$", mismatches);
    check(document, "InboxMessage", inbox, "$.messages[0]", mismatches);
    String cancelled = body(Bookings.cancel(mvc, booked(mvc, "BEL", "082 555 0198"), "0198"));
    check(document, "Appointment", cancelled, "$", mismatches);
    check(document, "Notification", cancelled, "$.notifications[0]", mismatches);
    check(document, "Notification", cancelled, "$.notifications[1]", mismatches);
    String started = booked(mvc, "UMH", "082 555 0199");
    clock.setInstant(Instant.parse("2026-10-27T08:00:00Z"));
    MvcTestResult tooLate = Bookings.cancel(mvc, started, "0199");
    clock.setInstant(TestClockConfiguration.DEFAULT_INSTANT);
    List<MvcTestResult> wrongDigits = new ArrayList<>();
    for (int attempt = 0; attempt < 6; attempt++) {
      wrongDigits.add(lookup(mvc, reference, "9999"));
    }
    Map<String, Object> unknownField =
        Bookings.body("SAN", "FICA", "2026-10-27T09:00:00+02:00", "082 555 0196");
    unknownField.put("endsAt", "2026-10-27T09:15:00+02:00");
    for (MvcTestResult problem :
        List.of(
            book(mvc, unknownField),
            book(mvc, Bookings.body("SAN", "FICA", "2026-10-27T09:00:00+02:00", "021 555 0196")),
            book(mvc, Bookings.body("POL", "FICA", "2026-10-27T09:00:00+02:00", "082 555 0197")),
            mvc.get().uri("/api/v1/branches/12").exchange(),
            mvc.get().uri("/api/v1/branches/XYZ").exchange(),
            mvc.get()
                .uri("/api/v1/branches/GQE/availability?service=HOME&date=2026-10-27")
                .exchange(),
            lookup(mvc, reference, "95"),
            tooLate,
            mvc.get().uri("/api/v1/messages?limit=0").exchange(),
            wrongDigits.getFirst(),
            wrongDigits.getLast(),
            mvc.get().uri("/api/v1/no-such-path").exchange(),
            mvc.post().uri("/api/v1/branches").exchange(),
            mvc.get()
                .uri("/api/v1/branches/SAN/availability?service=FICA&date=2026-10-27")
                .accept(MediaType.TEXT_HTML)
                .exchange())) {
      check(document, "Problem", body(problem), "$", mismatches);
    }

    assertThat(mismatches).isEmpty();
  }

  /**
   * Records how the JSON object at {@code path} in {@code json} differs from the named schema: a
   * required property it lacks, or a property the schema does not declare.
   */
  private static void check(
      String document, String schema, String json, String path, List<String> mismatches) {
    Map<String, Object> properties =
        JsonPath.read(document, "$.components.schemas." + schema + ".properties");
    List<String> required = JsonPath.read(document, "$.components.schemas." + schema + ".required");
    Map<String, Object> actual = JsonPath.read(json, path);
    required.stream()
        .filter(name -> !actual.containsKey(name))
        .forEach(name -> mismatches.add(schema + " requires " + name + ", missing from " + json));
    actual.keySet().stream()
        .filter(name -> !properties.containsKey(name))
        .forEach(
            name -> mismatches.add(schema + " does not declare " + name + ", sent in " + json));
  }

  /** Books a card replacement at 10:00 on 27 October and returns its reference. */
  private static String booked(MockMvcTester mvc, String branch, String mobile) {
    return JsonPath.read(
        body(book(mvc, Bookings.body(branch, "CARD", "2026-10-27T10:00:00+02:00", mobile))),
        "$.reference");
  }

  private static String body(MvcTestResult result) {
    try {
      return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
