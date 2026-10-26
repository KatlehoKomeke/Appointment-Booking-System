package za.co.fynbos.branchbook.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import za.co.fynbos.branchbook.booking.application.BookAppointmentHandler;
import za.co.fynbos.branchbook.catalogue.application.BranchCalendarProvider;
import za.co.fynbos.branchbook.scheduling.application.BookedIntervalsPort;
import za.co.fynbos.branchbook.shared.config.BookingProperties;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;

/**
 * FR-28: the demo data. The loader books its three fixtures through the real booking path, at
 * Bellville and Umhlanga only, on each branch's next open day, under one {@code sys-} correlation
 * id; it books nothing into a database that holds appointments, logs a fixture that fails without
 * any personal data, and runs on start-up only when switched on, handing the work to its own
 * thread. The loader bean is driven directly; its setting is off in the tests, as everywhere but
 * docker compose. The numbers are the synthetic demo ones in SECURITY.md.
 */
@IntegrationTest
@ExtendWith({DatabaseReset.class, OutputCaptureExtension.class})
class DemoDataLoaderIT {

  /** The three fixtures as they are stored: branch, service, number, status. */
  private static final List<Map<String, Object>> FIXTURES =
      List.of(
          fixture("BEL", "FICA", "+27600000001"),
          fixture("UMH", "CARD", "+27600000002"),
          fixture("UMH", "FICA", "+27600000000"));

  @Autowired private DemoDataLoader loader;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private MutableClock clock;

  @Autowired private MockMvcTester mvc;

  @Autowired private ConfigurableApplicationContext context;

  @Autowired private BookAppointmentHandler booking;

  @Autowired private BranchCalendarProvider calendars;

  @Autowired private BookedIntervalsPort bookedIntervals;

  @Autowired private JdbcClient jdbcClient;

  @Autowired private BookingProperties rules;

  @Test
  void booksThreeConfirmedAppointmentsAtBellvilleAndUmhlanga() {
    assertThat(loader.loadIfEmpty()).isEqualTo(3);

    assertThat(
            jdbc.queryForList(
                "SELECT b.code AS branch, s.code AS service, a.customer_mobile AS mobile, a.status"
                    + " FROM appointments a JOIN branches b ON b.id = a.branch_id"
                    + " JOIN services s ON s.id = a.service_id"))
        .containsExactlyInAnyOrderElementsOf(FIXTURES);
    List<Map<String, Object>> messages =
        jdbc.queryForList("SELECT kind, status, correlation_id FROM notification_messages");
    assertThat(messages)
        .hasSize(3)
        .allSatisfy(
            message ->
                assertThat(message)
                    .containsEntry("kind", "CONFIRMATION")
                    .containsEntry("status", "PENDING"));
    assertThat(messages.stream().map(message -> message.get("correlation_id")).distinct())
        .as("one correlation id for the whole load")
        .singleElement()
        .asString()
        .matches("sys-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }

  /**
   * Each fixture takes the first slot of its branch's next open day after today, from the branch's
   * own calendar: Monday's slots are skipped though two hours of lead time leave most of them free;
   * Bellville, closed on Sundays, waits for Monday while Umhlanga opens at 09:00 on Sunday; and the
   * Day of Reconciliation, 16 December, is skipped by both.
   */
  @ParameterizedTest(name = "[{index}] at {0}")
  @CsvSource({
    "2026-10-26T06:00:00Z, 2026-10-27T06:00:00Z, 2026-10-27T06:00:00Z",
    "2026-10-31T10:00:00Z, 2026-11-02T06:00:00Z, 2026-11-01T07:00:00Z",
    "2026-12-15T10:00:00Z, 2026-12-17T06:00:00Z, 2026-12-17T06:00:00Z"
  })
  void booksTheFirstSlotOfEachBranchsNextOpenDay(Instant now, Instant bellville, Instant umhlanga) {
    clock.setInstant(now);

    loader.loadIfEmpty();

    assertThat(
            jdbc.queryForList(
                "SELECT b.code AS branch, a.starts_at AS starts FROM appointments a"
                    + " JOIN branches b ON b.id = a.branch_id"))
        .extracting(row -> row.get("branch") + " " + instant(row.get("starts")))
        .containsExactlyInAnyOrder("BEL " + bellville, "UMH " + umhlanga, "UMH " + umhlanga);
  }

  @Test
  void booksNothingWhenAnAppointmentExists(CapturedOutput output) {
    assertThat(book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0162")))
        .hasStatus(HttpStatus.CREATED);

    assertThat(loader.loadIfEmpty()).isZero();

    assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class)).isOne();
    assertThat(output).contains("demo_data.skipped reason=appointments_exist");
  }

  /**
   * ADR-0006: where only the published demo numbers book, as on the hosted demo, the fixtures,
   * whose numbers are outside them on purpose, are not booked. The load says why in one INFO line
   * that holds no number, rather than storing numbers the deployment promises it never takes. The
   * setting is the only difference from the loader the context holds.
   */
  @Test
  void booksNothingWhereOnlyTheDemoNumbersBook(CapturedOutput output) {
    BookingProperties demoNumbersOnly =
        new BookingProperties(
            rules.leadTime(),
            rules.horizon(),
            rules.lookupMaxFailures(),
            rules.lookupWindow(),
            true);
    DemoDataLoader hosted =
        new DemoDataLoader(
            booking,
            calendars,
            bookedIntervals,
            jdbcClient,
            clock,
            demoNumbersOnly,
            new DemoDataProperties(true),
            Runnable::run);

    assertThat(hosted.loadIfEmpty()).isZero();

    assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class)).isZero();
    assertThat(output.getAll().lines().filter(line -> line.contains("demo_data.")))
        .singleElement()
        .asString()
        .contains("INFO")
        .contains("demo_data.skipped reason=demo_numbers_only");
    assertThat(output.getAll()).doesNotContainPattern("60\\D{0,3}000\\D{0,3}000[012]");
  }

  /**
   * A fixture the database refuses is logged as a warning by branch and service, never by number or
   * name, and the other two are still booked; nothing is thrown. A test-only trigger refuses the
   * second fixture's insert.
   */
  @Test
  void fixtureThatFailsIsLoggedWithoutPersonalDataAndTheOthersAreBooked(CapturedOutput output) {
    jdbc.execute(
        """
        CREATE OR REPLACE FUNCTION test_refuse_demo_fixture() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          RAISE EXCEPTION 'test: this demo fixture is refused';
        END $$""");
    jdbc.execute(
        """
        CREATE TRIGGER test_refuse_demo_fixture BEFORE INSERT ON appointments FOR EACH ROW
        WHEN (NEW.customer_mobile = '+27600000002') EXECUTE FUNCTION test_refuse_demo_fixture()""");
    int booked;
    try {
      booked = loader.loadIfEmpty();
    } finally {
      jdbc.execute("DROP TRIGGER test_refuse_demo_fixture ON appointments");
      jdbc.execute("DROP FUNCTION test_refuse_demo_fixture()");
    }

    assertThat(booked).isEqualTo(2);
    assertThat(jdbc.queryForList("SELECT customer_mobile FROM appointments", String.class))
        .containsExactlyInAnyOrder("+27600000001", "+27600000000");
    assertThat(output)
        .contains("WARN")
        .contains("demo_data.fixture_failed branch=UMH service=CARD")
        .contains("demo_data.loaded booked=2 fixtures=3");
    assertThat(output.getAll())
        .doesNotContainPattern("60\\D{0,3}000\\D{0,3}000[012]")
        .doesNotContain("Ayanda")
        .doesNotContain("Pieter")
        .doesNotContain("Priya");
  }

  /**
   * The setting is off unless a deployment switches it on, and off, the ready event starts nothing:
   * no thread, no booking. The application's own ready event is published as well.
   */
  @Test
  void readyEventStartsNothingWhenSwitchedOff() {
    List<Runnable> handedOff = new ArrayList<>();

    loaderWith(false, handedOff::add).onApplicationReady();
    context.publishEvent(
        new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));

    assertThat(context.getEnvironment().getProperty("branchbook.demo-data.enabled"))
        .isEqualTo("false");
    assertThat(handedOff).isEmpty();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class)).isZero();
  }

  /**
   * On, the ready event hands the load to another thread and returns before anything is booked, so
   * readiness never waits for it. Here the thread is the test's own, run when it chooses.
   */
  @Test
  void readyEventHandsTheLoadToItsOwnThreadWhenSwitchedOn() {
    List<Runnable> handedOff = new ArrayList<>();

    loaderWith(true, handedOff::add).onApplicationReady();

    assertThat(handedOff).hasSize(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class))
        .as("nothing is booked on the event's own thread")
        .isZero();
    handedOff.getFirst().run();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class))
        .isEqualTo(3);
  }

  /**
   * The loader as the application builds it hands the load to a virtual thread of its own, named
   * demo-data, so the ready event returns without waiting for the database. Its clock notes the
   * thread that asks it the time, which the load does once it has found the table empty, and then
   * stops the load, so nothing is booked.
   */
  @Test
  void applicationsLoaderLoadsOnAVirtualThreadNamedDemoData() throws Exception {
    CompletableFuture<Thread> loading = new CompletableFuture<>();
    Clock noteTheThread =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            loading.complete(Thread.currentThread());
            throw new IllegalStateException("test: the demo load stops here");
          }
        };
    DemoDataLoader applications =
        new DemoDataLoader(
            booking,
            calendars,
            bookedIntervals,
            jdbcClient,
            noteTheThread,
            rules,
            new DemoDataProperties(true));

    applications.onApplicationReady();

    Thread thread = loading.get(10, TimeUnit.SECONDS);
    assertThat(thread)
        .as("not the thread that published the event")
        .isNotSameAs(Thread.currentThread());
    assertThat(thread.join(Duration.ofSeconds(10))).as("the load has ended").isTrue();
    assertThat(thread.isVirtual()).isTrue();
    assertThat(thread.getName()).isEqualTo("demo-data");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class)).isZero();
  }

  /** A loader with the context's beans, the given setting, and the given thread to load on. */
  private DemoDataLoader loaderWith(boolean enabled, Executor background) {
    return new DemoDataLoader(
        booking,
        calendars,
        bookedIntervals,
        jdbcClient,
        clock,
        rules,
        new DemoDataProperties(enabled),
        background);
  }

  private static Map<String, Object> fixture(String branch, String service, String mobile) {
    return Map.of("branch", branch, "service", service, "mobile", mobile, "status", "CONFIRMED");
  }

  private static Instant instant(Object timestamp) {
    return switch (timestamp) {
      case java.sql.Timestamp sql -> sql.toInstant();
      case java.time.OffsetDateTime offset -> offset.toInstant();
      default -> throw new IllegalArgumentException("Not a timestamp: " + timestamp);
    };
  }
}
