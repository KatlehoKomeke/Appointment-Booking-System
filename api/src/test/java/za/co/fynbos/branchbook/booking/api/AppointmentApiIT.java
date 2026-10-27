package za.co.fynbos.branchbook.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;
import static za.co.fynbos.branchbook.support.Bookings.customer;
import static za.co.fynbos.branchbook.support.Bookings.lookup;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;
import za.co.fynbos.branchbook.support.ResponseText;

/**
 * {@code POST /api/v1/appointments} and {@code GET /api/v1/appointments/{reference}} through the
 * whole application on a real database: what a booking and a lookup return, every refusal and its
 * code, and that no response leaks what it must not. The clock stands at 08:00 on Monday 26 October
 * 2026 in Johannesburg; the numbers are synthetic.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class AppointmentApiIT {

  private static final String TUESDAY_0930 = "2026-10-27T09:30:00+02:00";

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private MutableClock clock;

  /** BR-16, as evidence: the booking and its PENDING confirmation come back together. */
  @Test
  void postReturns201WithLocationReferenceAndPendingNotifications() {
    MvcTestResult result = book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0141"));

    assertThat(result).hasStatus(HttpStatus.CREATED).hasContentType(MediaType.APPLICATION_JSON);
    String reference = string(result, "$.reference");
    assertThat(reference).matches(AppointmentReference.PATTERN);
    assertThat(result.getResponse().getHeader("Location"))
        .isEqualTo("/api/v1/appointments/" + reference);
    assertThat(result).doesNotContainHeader(AppointmentController.IDEMPOTENT_REPLAYED);
    assertThat(result)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {
              "status": "CONFIRMED",
              "branch": {"code": "SAN", "name": "Sandton Summit Mall",
                         "address": "Shop 4, 1 Protea Lane, Sandton, Johannesburg, 2196"},
              "service": {"code": "FICA", "durationMinutes": 15},
              "startsAt": "2026-10-27T09:30:00+02:00",
              "endsAt": "2026-10-27T09:45:00+02:00",
              "customer": {"firstName": "Nomvula", "lastName": "Dlamini",
                           "mobileMasked": "082 *** 0141"},
              "canCancel": true,
              "cancelledAt": null
            }""");
    String sms =
        "Appt confirmed: FICA update, Sandton Summit, Tue 27 Oct 09:30 SAST. Ref "
            + reference
            + ". Bring ID + docs. Manage: http://localhost:8080/manage";
    assertThat(result).bodyJson().extractingPath("$.notifications.length()").isEqualTo(1);
    assertThat(result)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"notifications": [{
              "channel": "SMS", "kind": "CONFIRMATION", "status": "PENDING", "attempts": 0,
              "sentAt": null, "failureReason": null, "recipientMasked": "082 *** 0141",
              "body": "%s"
            }]}"""
                .formatted(sms));
    assertThat(count("appointments")).isOne();
    assertThat(count("notification_messages")).isOne();
    assertThat(count("idempotency_keys")).isOne();
  }

  /** BR-12: a booking without a UUID Idempotency-Key is refused before anything is written. */
  @ParameterizedTest(name = "[{index}] {0}")
  @NullSource
  @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1"})
  void postWithoutIdempotencyKeyReturns400IdempotencyKeyRequired(@Nullable String key) {
    MvcTestResult result = book(mvc, key, body("SAN", "FICA", TUESDAY_0930, "082 555 0142"));

    assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    assertThat(count("appointments")).isZero();
  }

  /** BR-19: a landline cannot receive the SMS. The field is named, the number never repeated. */
  @Test
  void postWithLandlineReturns422MobileNumberRequiredFieldError() throws Exception {
    MvcTestResult result = book(mvc, body("SAN", "FICA", TUESDAY_0930, "021 555 0143"));

    assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MOBILE_NUMBER_REQUIRED");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].field")
        .asArray()
        .containsExactly("customer.mobile");
    assertThat(ResponseText.withoutIdentifiers(result.getResponse().getContentAsString()))
        .doesNotContain("0143")
        .contains("for example 082 555 0101");
  }

  /**
   * ADR-0006: {@code branchbook.booking.demo-numbers-only} is off by default, so a number outside
   * the published demo block books like any other. DemoNumbersOnlyIT shows it switched on.
   */
  @Test
  void postWithANumberOutsideTheDemoBlockBooksWhileDemoNumbersOnlyIsOff() {
    MvcTestResult result = book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0200"));

    assertThat(result).hasStatus(HttpStatus.CREATED);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.customer.mobileMasked")
        .isEqualTo("082 *** 0200");
    assertThat(count("appointments")).isOne();
  }

  /** BR-2: the end is the server's to compute, so a client cannot send it. */
  @Test
  void postWithUnknownJsonFieldReturns400ValidationFailed() throws Exception {
    Map<String, Object> withEnd = body("SAN", "FICA", TUESDAY_0930, "082 555 0144");
    withEnd.put("endsAt", "2026-10-27T11:00:00+02:00");
    Map<String, Object> withEmail = body("SAN", "FICA", TUESDAY_0930, "082 555 0144");
    customer(withEmail).put("email", "nomvula@example.org");

    MvcTestResult end = book(mvc, withEnd);
    MvcTestResult email = book(mvc, withEmail);

    assertThat(end).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(end)
        .bodyJson()
        .extractingPath("$.errors")
        .isEqualTo(
            List.of(
                Map.of(
                    "field",
                    "endsAt",
                    "code",
                    "UNKNOWN_FIELD",
                    "message",
                    "is not a field of this request")));
    assertThat(end.getResponse().getContentAsString()).doesNotContain("11:00");
    assertThat(email).bodyJson().extractingPath("$.errors[0].field").isEqualTo("customer.email");
    assertThat(email.getResponse().getContentAsString()).doesNotContain("nomvula@");
  }

  @Test
  void postWithMalformedFieldsReturns400NamingEachField() {
    Map<String, Object> body = body("S4N", "FICA", "2026-10-27T09:30", "082 555 0145");
    customer(body).put("firstName", "Th@ndi");
    customer(body).put("lastName", " ");

    MvcTestResult result = book(mvc, body);

    assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*]['field','code']")
        .asArray()
        .containsExactlyInAnyOrder(
            Map.of("field", "branchCode", "code", "PATTERN"),
            Map.of("field", "startsAt", "code", "OFFSET_REQUIRED"),
            Map.of("field", "customer.firstName", "code", "NAME_INVALID"),
            Map.of("field", "customer.lastName", "code", "NOT_BLANK"));
  }

  @Test
  void postForUnknownBranchReturns404BranchNotFound() {
    assertThat(book(mvc, body("XYZ", "FICA", TUESDAY_0930, "082 555 0146")))
        .hasStatus(HttpStatus.NOT_FOUND)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("BRANCH_NOT_FOUND");
  }

  /** BR-6: Gqeberha offers no home loans; nothing is written, not even the key. */
  @Test
  void postForServiceNotOfferedReturns422() {
    MvcTestResult result = book(mvc, body("GQE", "HOME", TUESDAY_0930, "082 555 0147"));

    assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("SERVICE_NOT_OFFERED_AT_BRANCH");
    assertThat(count("appointments") + count("idempotency_keys") + count("notification_messages"))
        .isZero();
  }

  /** BR-3: 4 November 2026 is the election-day public holiday. */
  @Test
  void postForPublicHolidayReturns422SlotOnClosedDay() {
    assertRefusedAsNotASlot("2026-11-04T09:00:00+02:00", "FICA", "SLOT_ON_CLOSED_DAY");
  }

  /** BR-1: 09:05 is not on the 15-minute grid that starts at 08:00. */
  @Test
  void postOffGridStartReturns422SlotOffGrid() {
    assertRefusedAsNotASlot("2026-10-27T09:05:00+02:00", "FICA", "SLOT_OFF_GRID");
  }

  /** BR-2: a 45-minute home loan at 12:30 on a Saturday would end after 13:00. */
  @Test
  void postEndingAfterCloseReturns422SlotOutsideHours() {
    assertRefusedAsNotASlot("2026-10-31T12:30:00+02:00", "HOME", "SLOT_OUTSIDE_HOURS");
  }

  /** BR-4: at 08:00, a slot at 09:30 the same morning is within the two-hour lead time. */
  @Test
  void postWithinLeadTimeReturns422SlotWithinLeadTime() {
    assertRefusedAsNotASlot("2026-10-26T09:30:00+02:00", "FICA", "SLOT_WITHIN_LEAD_TIME");
  }

  /** BR-5: the window ends on 24 November, the thirtieth date. */
  @Test
  void postOutsideWindowReturns422DateOutsideBookingWindow() {
    assertRefusedAsNotASlot("2026-11-25T09:00:00+02:00", "FICA", "DATE_OUTSIDE_BOOKING_WINDOW");
  }

  /** BR-5: a start at the far end of time is outside the window, not an unexpected failure. */
  @Test
  void postAtTheLimitOfTimeReturns422DateOutsideBookingWindow() {
    assertRefusedAsNotASlot("+999999999-12-31T23:00:00Z", "FICA", "DATE_OUTSIDE_BOOKING_WINDOW");
  }

  /**
   * BR-7, sequentially: Polokwane has one seat for FICA updates. Once 09:00 is taken, the next
   * customer is offered the three slots after it.
   */
  @Test
  void postWhenSlotFullReturns409WithThreeAlternatives() {
    assertThat(book(mvc, body("POL", "FICA", "2026-10-27T09:00:00+02:00", "082 555 0148")))
        .hasStatus(HttpStatus.CREATED);

    MvcTestResult full =
        book(mvc, body("POL", "FICA", "2026-10-27T09:00:00+02:00", "082 555 0149"));

    assertThat(full).hasStatus(HttpStatus.CONFLICT);
    assertThat(full).bodyJson().extractingPath("$.code").isEqualTo("SLOT_FULL");
    assertThat(full)
        .bodyJson()
        .extractingPath("$.alternatives")
        .isEqualTo(
            List.of(
                Map.of(
                    "startsAt", "2026-10-27T09:15:00+02:00", "endsAt", "2026-10-27T09:30:00+02:00"),
                Map.of(
                    "startsAt", "2026-10-27T09:30:00+02:00", "endsAt", "2026-10-27T09:45:00+02:00"),
                Map.of(
                    "startsAt",
                    "2026-10-27T09:45:00+02:00",
                    "endsAt",
                    "2026-10-27T10:00:00+02:00")));
    assertThat(count("appointments")).isOne();
  }

  /**
   * BR-23: a caller who knows only a number learns nothing that opens an appointment from the two
   * customer conflicts: no reference, branch or time.
   */
  @Test
  void conflict409BodiesNeverContainReferenceOrStartsAt() throws Exception {
    assertThat(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0150")))
        .hasStatus(HttpStatus.CREATED);

    MvcTestResult duplicate =
        book(mvc, body("BEL", "FICA", "2026-10-28T10:00:00+02:00", "082 555 0150"));
    MvcTestResult overlap = book(mvc, body("BEL", "CARD", TUESDAY_0930, "082 555 0150"));

    assertThat(duplicate).bodyJson().extractingPath("$.code").isEqualTo("DUPLICATE_APPOINTMENT");
    assertThat(overlap).bodyJson().extractingPath("$.code").isEqualTo("CUSTOMER_OVERLAP");
    for (MvcTestResult conflict : List.of(duplicate, overlap)) {
      assertThat(conflict).hasStatus(HttpStatus.CONFLICT);
      assertThat(conflict.getResponse().getContentAsString())
          .doesNotContainPattern("APT-[A-HJKMNP-Z2-9]{4}")
          .doesNotContain("2026-")
          .doesNotContain("SAN")
          .doesNotContain("Sandton")
          .doesNotContain("09:30");
    }
  }

  /**
   * BR-23: no refusal ever carries an appointment reference, not even a refused lookup of the
   * reference itself.
   */
  @Test
  void noProblemBodyEverContainsAnAppointmentReference() throws Exception {
    MvcTestResult booked = book(mvc, body("POL", "FICA", TUESDAY_0930, "082 555 0151"));
    assertThat(booked).hasStatus(HttpStatus.CREATED);
    String reference = string(booked, "$.reference");
    Map<String, Object> malformed = body("SAN", "FICA", TUESDAY_0930, "082 555 0151");
    malformed.put("endsAt", "x");

    List<MvcTestResult> problems =
        List.of(
            book(mvc, malformed),
            book(mvc, body("POL", "FICA", "2026-10-28T09:00:00+02:00", "082 555 0151")),
            book(mvc, body("POL", "CARD", TUESDAY_0930, "082 555 0151")),
            book(mvc, body("POL", "FICA", TUESDAY_0930, "082 555 0152")),
            book(mvc, body("SAN", "FICA", "2026-11-04T09:00:00+02:00", "082 555 0152")),
            lookup(mvc, reference, "12x4"),
            lookup(mvc, reference, "9999"),
            lookup(mvc, reference, "9999"),
            lookup(mvc, reference, "9999"),
            lookup(mvc, reference, "9999"),
            lookup(mvc, reference, "9999"),
            lookup(mvc, reference, "0151"));

    assertThat(problems)
        .extracting(result -> result.getResponse().getStatus())
        .containsExactly(400, 409, 409, 409, 422, 400, 404, 404, 404, 404, 404, 429);
    for (MvcTestResult problem : problems) {
      assertThat(problem.getResponse().getContentAsString())
          .doesNotContainPattern("APT-?[A-HJKMNP-Z2-9]{4}-?[A-HJKMNP-Z2-9]{4}");
    }
  }

  /** BR-19: the number appears masked in the booking, and nowhere in full. */
  @Test
  void responsesNeverContainUnmaskedMobile() throws Exception {
    MvcTestResult booked = book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0153"));
    MvcTestResult duplicate =
        book(mvc, body("BEL", "FICA", "2026-10-28T10:00:00+02:00", "082 555 0153"));

    for (MvcTestResult result : List.of(booked, duplicate)) {
      assertThat(result.getResponse().getContentAsString())
          .doesNotContain("+27825550153")
          .doesNotContain("0825550153")
          .doesNotContain("825550153")
          .doesNotContain("082 555 0153");
    }
    assertThat(booked.getResponse().getContentAsString()).contains("082 *** 0153");
  }

  /** Every refusal carries its code and the request's correlation id, and nothing is cached. */
  @Test
  void everyProblemCarriesCodeAndCorrelationId() {
    List<MvcTestResult> problems =
        List.of(
            book(mvc, null, body("SAN", "FICA", TUESDAY_0930, "082 555 0154")),
            book(mvc, body("XYZ", "FICA", TUESDAY_0930, "082 555 0154")),
            book(mvc, body("SAN", "FICA", TUESDAY_0930, "021 555 0154")));

    for (MvcTestResult problem : problems) {
      assertThat(problem).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
      assertThat(problem).bodyJson().extractingPath("$.code").asString().isNotBlank();
      assertThat(problem).bodyJson().extractingPath("$.correlationId").isEqualTo("test-booking");
    }
  }

  /** NFR-6: a booking response is personal data, so no cache may keep it. */
  @Test
  void appointmentResponsesAreNeverCached() {
    MvcTestResult booked = book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0155"));

    assertThat(booked.getResponse().getHeader("Cache-Control")).contains("no-store");
  }

  /**
   * BR-13, BR-16: the reference and the last four digits open the appointment, which shows the same
   * as when it was booked, with its confirmation still PENDING. The reference may be typed in
   * lowercase and without hyphens.
   */
  @Test
  void getReturnsTheAppointmentWithItsPendingConfirmation() throws Exception {
    MvcTestResult booked = book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0157"));
    String reference = string(booked, "$.reference");

    MvcTestResult opened = lookup(mvc, reference, "0157");
    MvcTestResult typedLoosely =
        lookup(mvc, reference.toLowerCase(Locale.ROOT).replace("-", ""), "0157");

    assertThat(opened).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
    assertThat(opened).bodyJson().isStrictlyEqualTo(booked.getResponse().getContentAsString());
    assertThat(opened)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": "CONFIRMED", "startsAt": "2026-10-27T09:30:00+02:00",
             "customer": {"mobileMasked": "082 *** 0157"},
             "notifications": [{"kind": "CONFIRMATION", "status": "PENDING"}]}""");
    assertThat(typedLoosely).hasStatus(HttpStatus.OK);
    assertThat(string(typedLoosely, "$.reference")).isEqualTo(reference);
    assertThat(opened.getResponse().getHeader("Cache-Control")).contains("no-store");
  }

  /**
   * BR-13, ADR-0004: the right reference with the wrong digits is answered exactly as an unknown or
   * malformed reference is: 404, never 403, so no answer confirms that a reference exists.
   */
  @Test
  void getWithWrongLast4Returns404NotForbidden() {
    String reference =
        string(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0158")), "$.reference");

    List<MvcTestResult> misses =
        List.of(
            lookup(mvc, reference, "0159"),
            lookup(mvc, "APT-2222-3333", "0158"),
            lookup(mvc, "not-a-reference", "0158"));

    for (MvcTestResult miss : misses) {
      assertThat(miss)
          .hasStatus(HttpStatus.NOT_FOUND)
          .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
      assertThat(miss)
          .bodyJson()
          .isLenientlyEqualTo(
              """
              {"status": 404, "code": "APPOINTMENT_NOT_FOUND", "title": "Appointment not found",
               "detail": "We couldn't find an appointment with those details.",
               "correlationId": "test-lookup"}""");
      assertThat(miss).bodyJson().doesNotHavePath("$.errors");
    }
  }

  /**
   * BR-13: digits that are missing or not four digits are a 400 naming the header, checked before
   * the reference is touched, so they never count towards blocking it.
   */
  @Test
  void malformedLast4Returns400AndIsNotCounted() {
    String reference =
        string(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0160")), "$.reference");

    List<MvcTestResult> malformed = new ArrayList<>();
    for (String last4 : List.of("016", "01600", "o160", "0 60", "+160", "٠١٦٠")) {
      malformed.add(lookup(mvc, reference, last4));
    }
    MvcTestResult missing = lookup(mvc, reference, null);

    for (MvcTestResult result : malformed) {
      assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
      assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
      assertThat(result)
          .bodyJson()
          .extractingPath("$.errors[*]['field','code']")
          .asArray()
          .containsExactly(Map.of("field", "X-Mobile-Last4", "code", "PATTERN"));
    }
    assertThat(missing).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(missing)
        .bodyJson()
        .extractingPath("$.errors[*]['field','code']")
        .asArray()
        .containsExactly(Map.of("field", "X-Mobile-Last4", "code", "REQUIRED"));
    assertThat(lookup(mvc, reference, "0160")).hasStatus(HttpStatus.OK);
  }

  /**
   * BR-13: five wrong tries block the reference for an hour from the first, even for the right
   * digits; once the hour has passed, the right digits open it again.
   */
  @Test
  void sixthWrongLast4WithinHourReturns429() {
    String reference =
        string(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0161")), "$.reference");
    for (int attempt = 1; attempt <= 5; attempt++) {
      assertThat(lookup(mvc, reference, "9999")).hasStatus(HttpStatus.NOT_FOUND);
      clock.advance(Duration.ofMinutes(1));
    }

    MvcTestResult wrong = lookup(mvc, reference, "9999");
    MvcTestResult right = lookup(mvc, reference, "0161");

    for (MvcTestResult blocked : List.of(wrong, right)) {
      assertThat(blocked)
          .hasStatus(HttpStatus.TOO_MANY_REQUESTS)
          .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
          .hasHeader("Retry-After", "3300");
      assertThat(blocked).bodyJson().extractingPath("$.code").isEqualTo("LOOKUP_RATE_LIMITED");
    }
    clock.advance(Duration.ofMinutes(54).plusSeconds(59));
    assertThat(lookup(mvc, reference, "0161"))
        .hasStatus(HttpStatus.TOO_MANY_REQUESTS)
        .hasHeader("Retry-After", "1");
    clock.advance(Duration.ofSeconds(1));
    assertThat(lookup(mvc, reference, "0161")).hasStatus(HttpStatus.OK);
  }

  private void assertRefusedAsNotASlot(String startsAt, String service, String code) {
    MvcTestResult result = book(mvc, body("SAN", service, startsAt, "082 555 0156"));

    assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*]['field','code']")
        .asArray()
        .containsExactly(Map.of("field", "startsAt", "code", code));
    assertThat(count("appointments")).isZero();
  }

  private int count(String table) {
    Integer rows = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    return rows == null ? 0 : rows;
  }

  private static String string(MvcTestResult result, String path) {
    String json = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    return JsonPath.read(json, path);
  }
}
