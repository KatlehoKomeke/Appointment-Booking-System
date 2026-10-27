package za.co.fynbos.branchbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;
import static za.co.fynbos.branchbook.support.Bookings.cancel;
import static za.co.fynbos.branchbook.support.Bookings.customer;
import static za.co.fynbos.branchbook.support.Bookings.lookup;
import static za.co.fynbos.branchbook.support.Bookings.retry;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import za.co.fynbos.branchbook.booking.application.AppointmentRepository;
import za.co.fynbos.branchbook.booking.application.CustomerOverlapException;
import za.co.fynbos.branchbook.booking.domain.Appointment;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;
import za.co.fynbos.branchbook.booking.domain.CustomerDetails;
import za.co.fynbos.branchbook.booking.domain.CustomerName;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.notification.application.NotificationDispatcher;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;
import za.co.fynbos.branchbook.support.ScriptedReferenceGenerator;
import za.co.fynbos.branchbook.support.SeedData;
import za.co.fynbos.branchbook.support.TestClockConfiguration;

/**
 * BR-19: a customer's name and mobile number, and the reference that opens their appointment, reach
 * neither the application's log nor the database's, whatever happens to a booking, a lookup, a
 * cancellation or a retry of its messages.
 *
 * <p>One synthetic customer goes through every outcome there is: booked, replayed, refused for each
 * reason, looked up right and wrong until blocked, caught by the database's exclusion constraint,
 * and failed with an unexpected error. The application's log is read as it is written, with its
 * real configuration; the database's is the container's own output, written with the flags
 * compose.yaml uses. Both must hold none of the customer's details in any form, and both must show
 * the events happened, so an empty capture cannot pass.
 */
@IntegrationTest
@ExtendWith({DatabaseReset.class, OutputCaptureExtension.class})
class LogRedactionIT {

  /** The customer's details, synthetic and unlike anything else the application logs. */
  private static final String FIRST_NAME = "Zodwa";

  private static final String LAST_NAME = "Mahlangu";

  /** From the demo range in SECURITY.md, typed as a customer would. */
  private static final String MOBILE = "082 555 0187";

  /** The number in any of its forms: national, international, with or without separators. */
  private static final String ANY_FORM_OF_THE_NUMBER = "(?s).*555\\D{0,3}0187.*";

  private static final String TUESDAY_0930 = "2026-10-27T09:30:00+02:00";

  @Autowired private MockMvcTester mvc;

  @Autowired private ScriptedReferenceGenerator references;

  @Autowired private AppointmentRepository appointments;

  @Autowired private TransactionTemplate transaction;

  @Autowired private PostgreSQLContainer postgres;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private MutableClock clock;

  @Autowired private NotificationDispatcher dispatcher;

  @Test
  void bookingLogsContainNoCustomerNameOrMobile(CapturedOutput output) {
    String key = UUID.randomUUID().toString();
    MvcTestResult booked = book(mvc, key, ours("SAN", "FICA", TUESDAY_0930, MOBILE));
    String reference = string(booked, "$.reference");
    List<MvcTestResult> results = new ArrayList<>(List.of(booked));

    results.add(book(mvc, key, ours("SAN", "FICA", TUESDAY_0930, MOBILE)));
    results.add(book(mvc, key, ours("SAN", "FICA", "2026-10-27T10:00:00+02:00", MOBILE)));
    results.add(book(mvc, withEmailAndBadName()));
    results.add(book(mvc, ours("SAN", "FICA", TUESDAY_0930, "021 555 0187")));
    results.add(book(mvc, ours("SAN", "FICA", "2026-10-27T09:05:00+02:00", MOBILE)));
    results.add(book(mvc, ours("BEL", "FICA", "2026-10-28T10:00:00+02:00", MOBILE)));
    results.add(book(mvc, ours("BEL", "CARD", TUESDAY_0930, MOBILE)));
    results.add(book(mvc, ours("POL", "FICA", "2026-10-27T09:00:00+02:00", "082 555 0188")));
    results.add(book(mvc, ours("POL", "FICA", "2026-10-27T09:00:00+02:00", "082 555 0189")));
    results.add(lookup(mvc, reference, "0187"));
    results.add(lookup(mvc, reference, "1234"));
    for (int wrong = 0; wrong < 5; wrong++) {
      results.add(lookup(mvc, reference, "0188"));
    }
    insertOverlappingAppointmentPastTheChecks();
    references.script(reference, reference);
    results.add(book(mvc, ours("BEL", "CARD", "2026-10-29T10:00:00+02:00", "082 555 0190")));

    assertThat(results)
        .extracting(result -> result.getResponse().getStatus())
        .containsExactly(
            201, 201, 422, 400, 422, 422, 409, 409, 201, 409, 200, 404, 404, 404, 404, 404, 429,
            500);
    assertThat(output.getAll())
        .contains("booking.confirmed", "booking.replayed", "booking.refused", "lookup.blocked")
        .contains("constraint=appointments_customer_no_overlap", "Unexpected failure")
        .as("the application names the constraint but never repeats the database's message")
        .doesNotContain("violates");
    assertHoldsNothingOfTheCustomer("the application's log", output.getAll(), reference);
    assertThat(postgres.getLogs()).contains("violates exclusion constraint");
    assertHoldsNothingOfTheCustomer("the database's log", postgres.getLogs(), reference);
  }

  /**
   * BR-19 for a cancellation and a retry: refused, cancelled and so deleted, repeated after the
   * delete, its cancellation SMS retried while PENDING with no appointment, reset once failed, sent
   * and deleted, and finally a cancel too late. The log shows every step, by appointment and
   * message id, and nothing of the customer.
   */
  @Test
  void cancelAndRetryLogsContainNoCustomerNameMobileOrReference(CapturedOutput output) {
    String reference = string(book(mvc, ours("SAN", "FICA", TUESDAY_0930, MOBILE)), "$.reference");
    // Later than the booking, so the confirmation is listed before the cancellation.
    clock.advance(Duration.ofMinutes(1));
    List<MvcTestResult> results = new ArrayList<>();

    results.add(cancel(mvc, reference, "0188"));
    MvcTestResult cancelled = cancel(mvc, reference, "0187");
    results.add(cancelled);
    results.add(cancel(mvc, reference, "0187"));
    String cancellation = string(cancelled, "$.notifications[0].id");
    results.add(retry(mvc, cancellation));
    jdbc.update(
        "UPDATE notification_messages SET status = 'FAILED', failure_reason = 'DELIVERY_FAILED'"
            + " WHERE id = ?::uuid",
        cancellation);
    results.add(retry(mvc, cancellation));
    assertThat(dispatcher.runOnce(clock.instant())).isOne();
    String later = string(book(mvc, ours("BEL", "CARD", TUESDAY_0930, MOBILE)), "$.reference");
    clock.setInstant(OffsetDateTime.parse(TUESDAY_0930).toInstant());
    results.add(cancel(mvc, later, "0187"));

    assertThat(results)
        .extracting(result -> result.getResponse().getStatus())
        .containsExactly(404, 200, 404, 200, 200, 409);
    assertThat(output.getAll())
        .contains("cancel.refused code=APPOINTMENT_NOT_FOUND", "cancel.deleted")
        .contains("cancel.refused code=TOO_LATE_TO_CANCEL", "outbox.confirmations_deleted")
        .contains("outbox.retry_unchanged", "outbox.retry_reset", "outbox.deleted");
    assertHoldsNothingOfTheCustomer("the application's log", output.getAll(), reference);
    assertHoldsNothingOfTheCustomer("the application's log", output.getAll(), later);
    assertHoldsNothingOfTheCustomer("the database's log", postgres.getLogs(), reference);
  }

  /**
   * A body for our customer.
   *
   * @return the body, with the customer's name
   */
  private static Map<String, Object> ours(
      String branch, String service, String startsAt, String mobile) {
    Map<String, Object> body = body(branch, service, startsAt, mobile);
    customer(body).put("firstName", FIRST_NAME);
    customer(body).put("lastName", LAST_NAME);
    return body;
  }

  /** A body Jackson refuses for an unknown field, and whose name would fail validation. */
  private static Map<String, Object> withEmailAndBadName() {
    Map<String, Object> body = ours("SAN", "FICA", TUESDAY_0930, MOBILE);
    customer(body).put("firstName", FIRST_NAME + "1");
    customer(body).put("email", "zodwa.mahlangu@example.org");
    return body;
  }

  /**
   * Inserts an appointment of the same customer at the same time, but at another branch, straight
   * through the repository, as a booking that slipped past the service's own checks would. Only the
   * database's exclusion constraint stops it, and its error must stay out of both logs.
   */
  private void insertOverlappingAppointmentPastTheChecks() {
    Appointment overlapping =
        Appointment.confirm(
            new AppointmentReference("APT-RDCT-2222"),
            SeedData.BELLVILLE,
            SeedData.FICA,
            Duration.ofMinutes(15),
            1,
            OffsetDateTime.parse(TUESDAY_0930).toInstant(),
            CustomerDetails.of(
                CustomerName.parse(FIRST_NAME).orElseThrow(),
                CustomerName.parse(LAST_NAME).orElseThrow(),
                MobileNumber.parse(MOBILE).orElseThrow()),
            TestClockConfiguration.DEFAULT_INSTANT);

    assertThatExceptionOfType(CustomerOverlapException.class)
        .isThrownBy(
            () -> transaction.executeWithoutResult(status -> appointments.insert(overlapping)));
  }

  private static void assertHoldsNothingOfTheCustomer(String log, String text, String reference) {
    assertThat(text)
        .as("%s must not hold the customer's name", log)
        .doesNotContain(FIRST_NAME)
        .doesNotContain(LAST_NAME)
        .doesNotContain("zodwa.mahlangu@");
    assertThat(text)
        .as("%s must not hold the customer's number, in full or masked", log)
        .doesNotMatch(ANY_FORM_OF_THE_NUMBER)
        .doesNotContain("*** 0187");
    assertThat(text)
        .as("%s must not hold the appointment's reference", log)
        .doesNotContain(reference)
        .doesNotContain(reference.replace("-", ""))
        .doesNotContain("APT-RDCT-2222");
  }

  private static String string(MvcTestResult result, String path) {
    String json = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    return JsonPath.read(json, path);
  }
}
