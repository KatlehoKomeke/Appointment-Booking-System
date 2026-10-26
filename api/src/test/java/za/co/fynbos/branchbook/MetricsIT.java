package za.co.fynbos.branchbook;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;
import static za.co.fynbos.branchbook.support.Bookings.cancel;
import static za.co.fynbos.branchbook.support.Bookings.customer;
import static za.co.fynbos.branchbook.support.Bookings.lookup;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;
import za.co.fynbos.branchbook.booking.application.BookingOutcome;
import za.co.fynbos.branchbook.notification.application.NotificationDispatcher;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;
import za.co.fynbos.branchbook.support.ScriptedReferenceGenerator;
import za.co.fynbos.branchbook.support.SeedData;

/**
 * FR-30: {@code /actuator/prometheus} serves Spring Boot's meters and the application's two, read
 * here exactly as Prometheus reads them. Every booking request is counted once by its outcome, the
 * outbox gauge follows the dispatcher's ticks, and nothing in the scrape is personal data. The
 * meters are shared by every test in the run, so counts are read as how far they moved. The clock
 * stands at 08:00 on Monday 26 October 2026 in Johannesburg; the numbers are synthetic.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class MetricsIT {

  private static final String TUESDAY_0930 = "2026-10-27T09:30:00+02:00";

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private DataSource dataSource;

  @Autowired private MutableClock clock;

  @Autowired private NotificationDispatcher dispatcher;

  @Autowired private ScriptedReferenceGenerator references;

  @Autowired private WebApplicationContext application;

  @Test
  void prometheusServesTheMetricsInItsTextFormat() throws Exception {
    MvcTestResult scrape = mvc.get().uri("/actuator/prometheus").exchange();

    assertThat(scrape).hasStatusOk();
    assertThat(scrape.getResponse().getContentType()).startsWith("text/plain;version=0.0.4");
    assertThat(scrape.getResponse().getContentAsString())
        .contains("# TYPE appointments_booked_total counter")
        .contains("# TYPE outbox_pending gauge")
        .contains("http_server_requests_seconds")
        .contains("hikaricp_connections")
        .contains("jvm_memory_used_bytes");
  }

  /**
   * Every outcome is a series in the scrape, with the outcome as its only tag.
   * BookingOutcomeMeterTest proves each is there at 0 before the first request.
   */
  @Test
  void everyOutcomeIsASeriesWithTheOutcomeAsItsOnlyTag() {
    String scrape = scrape();

    for (BookingOutcome outcome : BookingOutcome.values()) {
      assertThat(scrape).contains("appointments_booked_total{outcome=\"" + outcome.label() + "\"}");
    }
    assertThat(Pattern.compile("appointments_booked_total\\{[^}]*,").matcher(scrape).find())
        .as("the outcome is the counter's only tag")
        .isFalse();
  }

  /**
   * Only health, info and prometheus are exposed over HTTP; SecurityHeadersIT checks that the
   * others answer 404.
   */
  @Test
  void onlyHealthInfoAndPrometheusAreExposed() {
    assertThat(mvc.get().uri("/actuator"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$._links")
        .asMap()
        .containsOnlyKeys("self", "health", "health-path", "info", "prometheus");
  }

  /**
   * Thirteen booking requests, each counted once by how it ended; the lookups and cancels in
   * between are not booking requests and move nothing, not even a cancel refused with a 400, which
   * is a POST under the booking path. Validation covers the booking's own refusals and the 400s
   * Spring gives before the controller runs.
   */
  @Test
  void eachBookingRequestIsCountedOnceByItsOutcome() {
    Map<BookingOutcome, Double> before = counts();
    String key = UUID.randomUUID().toString();
    List<MvcTestResult> results = new ArrayList<>();

    results.add(book(mvc, key, body("SAN", "FICA", TUESDAY_0930, "082 555 0150")));
    results.add(book(mvc, key, body("SAN", "FICA", TUESDAY_0930, "082 555 0150")));
    results.add(book(mvc, key, body("SAN", "FICA", "2026-10-27T10:00:00+02:00", "082 555 0150")));
    results.add(book(mvc, body("POL", "FICA", "2026-10-27T09:00:00+02:00", "082 555 0151")));
    results.add(book(mvc, body("POL", "FICA", "2026-10-27T09:00:00+02:00", "082 555 0152")));
    results.add(book(mvc, body("SAN", "FICA", "2026-10-27T11:00:00+02:00", "082 555 0150")));
    results.add(book(mvc, body("BEL", "CARD", TUESDAY_0930, "082 555 0150")));
    results.add(book(mvc, body("SAN", "FICA", "2026-10-27T09:05:00+02:00", "082 555 0153")));
    results.add(book(mvc, body("SAN", "FICA", TUESDAY_0930, "021 555 0154")));
    results.add(book(mvc, null, body("SAN", "FICA", TUESDAY_0930, "082 555 0155")));
    results.add(book(mvc, body("XYZ", "FICA", TUESDAY_0930, "082 555 0155")));
    results.add(book(mvc, body("1", "FICA", TUESDAY_0930, "082 555 0155")));
    Map<String, Object> withEnd = body("SAN", "FICA", TUESDAY_0930, "082 555 0155");
    withEnd.put("endsAt", "2026-10-27T09:45:00+02:00");
    results.add(book(mvc, withEnd));
    String reference = string(results.getFirst(), "$.reference");
    assertThat(lookup(mvc, reference, "0150")).hasStatusOk();
    assertThat(lookup(mvc, reference, "9999")).hasStatus(HttpStatus.NOT_FOUND);
    assertThat(cancel(mvc, reference, "12")).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(cancel(mvc, reference, "0150")).hasStatusOk();

    assertThat(results)
        .extracting(result -> result.getResponse().getStatus())
        .containsExactly(201, 201, 422, 201, 409, 409, 409, 422, 422, 400, 404, 400, 400);
    Map<BookingOutcome, Double> moved = movedSince(before);
    assertThat(moved)
        .containsEntry(BookingOutcome.CONFIRMED, 2.0)
        .containsEntry(BookingOutcome.REPLAYED, 1.0)
        .containsEntry(BookingOutcome.SLOT_FULL, 1.0)
        .containsEntry(BookingOutcome.DUPLICATE, 1.0)
        .containsEntry(BookingOutcome.CUSTOMER_OVERLAP, 1.0)
        .containsEntry(BookingOutcome.VALIDATION, 7.0)
        .containsEntry(BookingOutcome.TRY_AGAIN, 0.0);
    assertThat(moved.values().stream().mapToDouble(Double::doubleValue).sum())
        .as("exactly one count per booking request")
        .isEqualTo(results.size());
  }

  /**
   * A booking that cannot get the seat lock in 2 seconds is a 503 that only the problem handler
   * names TRY_AGAIN; it is counted as try_again all the same. Another connection holds Sandton's
   * FICA row.
   */
  @Test
  void lockTimeoutIsCountedAsTryAgain() throws Exception {
    Map<BookingOutcome, Double> before = counts();
    MvcTestResult waited;
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement lock =
          holder.prepareStatement(
              "SELECT capacity FROM branch_services WHERE branch_id = ? AND service_id = ?"
                  + " FOR UPDATE")) {
        lock.setObject(1, SeedData.SANDTON);
        lock.setObject(2, SeedData.FICA);
        lock.executeQuery().close();
      }
      try {
        waited = book(mvc, body("SAN", "FICA", "2026-10-27T10:00:00+02:00", "082 555 0156"));
      } finally {
        holder.rollback();
      }
    }

    assertThat(waited).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(movedSince(before))
        .containsEntry(BookingOutcome.TRY_AGAIN, 1.0)
        .containsEntry(BookingOutcome.CONFIRMED, 0.0)
        .containsEntry(BookingOutcome.VALIDATION, 0.0);
  }

  /**
   * A booking that committed is counted as confirmed even when its 201 cannot be written, as when
   * the client has gone before the answer: the appointment exists and holds its seat, though the
   * request ends in the problem handler as an internal error. Here the response's output stream
   * fails on its first write.
   */
  @Test
  void bookingWhoseAnswerCannotBeWrittenIsStillCountedAsConfirmed() {
    MockMvcTester clientGone =
        MockMvcTester.from(application, builder -> builder.addFilters(new ClientGone()).build());
    Map<BookingOutcome, Double> before = counts();

    book(clientGone, body("SAN", "FICA", TUESDAY_0930, "082 555 0164"));

    assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class))
        .as("the booking committed")
        .isOne();
    Map<BookingOutcome, Double> moved = movedSince(before);
    assertThat(moved).containsEntry(BookingOutcome.CONFIRMED, 1.0);
    assertThat(moved.values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(1.0);
  }

  /**
   * A 500 is a defect, not an outcome of booking, so it is not counted. Two reference collisions in
   * a row, which only a broken random source could cause, give one.
   */
  @Test
  void internalErrorIsNotCounted() {
    String taken =
        string(book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0157")), "$.reference");
    references.script(taken, taken);
    Map<BookingOutcome, Double> before = counts();

    MvcTestResult failed = book(mvc, body("BEL", "CARD", TUESDAY_0930, "082 555 0158"));

    assertThat(failed).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(movedSince(before).values()).containsOnly(0.0);
  }

  /**
   * outbox_pending is the number of PENDING messages after the dispatcher's last tick, counted once
   * per tick; a message backing off after a refusal is still pending. The dispatcher's loop is off
   * in the tests, so each tick here is a direct call.
   */
  @Test
  void outboxPendingIsThePendingCountAfterEachTick() {
    dispatcher.runOnce(clock.instant());
    assertThat(sample(scrape(), "outbox_pending")).as("an empty outbox").isZero();

    book(mvc, body("SAN", "FICA", TUESDAY_0930, "082 555 0159"));
    book(mvc, body("BEL", "CARD", TUESDAY_0930, "082 555 0000"));
    assertThat(sample(scrape(), "outbox_pending"))
        .as("the gauge moves only when a tick runs")
        .isZero();
    dispatcher.runOnce(clock.instant());

    Integer pendingRows =
        jdbc.queryForObject(
            "SELECT count(*) FROM notification_messages WHERE status = 'PENDING'", Integer.class);
    assertThat(pendingRows).as("one sent, one refused and backing off").isOne();
    assertThat(sample(scrape(), "outbox_pending")).isEqualTo(1.0);
  }

  /**
   * BR-19: after a booking, its lookups, its cancel, the app page that names its reference, and a
   * number typed into an unknown path, the scrape holds no name, number or reference in any form.
   * Tags hold URI templates and codes, never what a caller sent.
   */
  @Test
  void scrapeHoldsNoPersonalData() {
    Map<String, Object> booking = body("SAN", "FICA", TUESDAY_0930, "082 555 0160");
    customer(booking).put("firstName", "Zanele");
    customer(booking).put("lastName", "Khumalo");
    String reference = string(book(mvc, booking), "$.reference");
    lookup(mvc, reference, "0160");
    lookup(mvc, reference.replace("-", "").toLowerCase(), "0161");
    cancel(mvc, reference.replace("-", "").toLowerCase(), "0160");
    mvc.get().uri("/appointments/" + reference).exchange();
    mvc.get().uri("/api/v1/nothing-here/+27825550160").exchange();
    dispatcher.runOnce(clock.instant());

    String scrape = scrape();

    assertThat(scrape).contains("uri=\"/api/v1/appointments/{reference}\"");
    assertThat(scrape).contains("uri=\"/api/v1/appointments/{reference}/cancel\"");
    assertThat(scrape).doesNotContain("Zanele").doesNotContain("Khumalo");
    assertThat(scrape)
        .doesNotMatch("(?s).*555\\D{0,3}0160.*")
        .doesNotContain("825550160")
        .doesNotContain("*** 0160");
    assertThat(scrape)
        .doesNotContain(reference)
        .doesNotContain(reference.replace("-", ""))
        .doesNotContainIgnoringCase(reference.replace("-", "").toLowerCase());
  }

  /** A client that has gone: every write of the response's body fails, as a closed socket does. */
  private static final class ClientGone extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      chain.doFilter(
          request,
          new HttpServletResponseWrapper(response) {
            @Override
            public ServletOutputStream getOutputStream() {
              return new ServletOutputStream() {
                @Override
                public boolean isReady() {
                  return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                  // Writes are blocking here; there is nothing to listen for.
                }

                @Override
                public void write(int b) throws IOException {
                  throw new IOException("test: the client has gone");
                }
              };
            }

            @Override
            public PrintWriter getWriter() throws IOException {
              throw new IOException("test: the client has gone");
            }
          });
    }
  }

  private String scrape() {
    MvcTestResult scrape = mvc.get().uri("/actuator/prometheus").exchange();
    assertThat(scrape).hasStatusOk();
    return new String(scrape.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
  }

  private Map<BookingOutcome, Double> counts() {
    String scrape = scrape();
    Map<BookingOutcome, Double> counts = new EnumMap<>(BookingOutcome.class);
    for (BookingOutcome outcome : BookingOutcome.values()) {
      counts.put(
          outcome,
          sample(scrape, "appointments_booked_total{outcome=\"" + outcome.label() + "\"}"));
    }
    return counts;
  }

  private Map<BookingOutcome, Double> movedSince(Map<BookingOutcome, Double> before) {
    Map<BookingOutcome, Double> after = counts();
    Map<BookingOutcome, Double> moved = new EnumMap<>(BookingOutcome.class);
    before.forEach((outcome, count) -> moved.put(outcome, after.get(outcome) - count));
    return moved;
  }

  /** The value of one series in a scrape, as Prometheus's text format writes it. */
  private static double sample(String scrape, String series) {
    Matcher line =
        Pattern.compile("^" + Pattern.quote(series) + " (\\S+)", Pattern.MULTILINE).matcher(scrape);
    assertThat(line.find()).as("the scrape has %s", series).isTrue();
    return Double.parseDouble(line.group(1));
  }

  private static String string(MvcTestResult result, String path) {
    return JsonPath.read(
        new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), path);
  }
}
