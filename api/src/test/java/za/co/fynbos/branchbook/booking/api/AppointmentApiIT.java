package za.co.fynbos.branchbook.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;
import static za.co.fynbos.branchbook.support.Bookings.customer;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

/**
 * {@code POST /api/v1/appointments} through the whole application on a real database: what a
 * booking returns, every refusal and its code, and that no response leaks what it must not. The
 * clock stands at 08:00 on Monday 26 October 2026 in Johannesburg; the numbers are synthetic.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class AppointmentApiIT {

  private static final String TUESDAY_0930 = "2026-10-27T09:30:00+02:00";

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

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
    assertThat(result.getResponse().getContentAsString()).doesNotContain("555");
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

  /** BR-23: no refusal of a booking ever carries an appointment reference. */
  @Test
  void noProblemBodyEverContainsAnAppointmentReference() throws Exception {
    assertThat(book(mvc, body("POL", "FICA", TUESDAY_0930, "082 555 0151")))
        .hasStatus(HttpStatus.CREATED);
    Map<String, Object> malformed = body("SAN", "FICA", TUESDAY_0930, "082 555 0151");
    malformed.put("endsAt", "x");

    List<MvcTestResult> problems =
        List.of(
            book(mvc, malformed),
            book(mvc, body("POL", "FICA", "2026-10-28T09:00:00+02:00", "082 555 0151")),
            book(mvc, body("POL", "CARD", TUESDAY_0930, "082 555 0151")),
            book(mvc, body("POL", "FICA", TUESDAY_0930, "082 555 0152")),
            book(mvc, body("SAN", "FICA", "2026-11-04T09:00:00+02:00", "082 555 0152")));

    assertThat(problems)
        .extracting(result -> result.getResponse().getStatus())
        .containsExactly(400, 409, 409, 409, 422);
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
