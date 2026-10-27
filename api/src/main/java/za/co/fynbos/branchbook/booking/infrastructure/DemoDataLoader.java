package za.co.fynbos.branchbook.booking.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import za.co.fynbos.branchbook.booking.application.BookAppointmentCommand;
import za.co.fynbos.branchbook.booking.application.BookAppointmentHandler;
import za.co.fynbos.branchbook.booking.domain.CustomerName;
import za.co.fynbos.branchbook.booking.domain.IdempotencyKey;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.catalogue.application.BranchCalendarProvider;
import za.co.fynbos.branchbook.catalogue.domain.BranchCalendar;
import za.co.fynbos.branchbook.catalogue.domain.ServiceOffer;
import za.co.fynbos.branchbook.scheduling.application.BookedIntervalsPort;
import za.co.fynbos.branchbook.scheduling.domain.Slot;
import za.co.fynbos.branchbook.scheduling.domain.SlotCalendar;
import za.co.fynbos.branchbook.shared.config.BookingProperties;
import za.co.fynbos.branchbook.shared.correlation.CorrelationIds;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * Books three demo appointments when the application has started on an empty database, so the first
 * screen and the simulated inbox have something to show (FR-28). Only docker compose switches it on
 * ({@code branchbook.demo-data.enabled}).
 *
 * <p>Each fixture is booked through the real {@link BookAppointmentHandler}, with its own {@code
 * Idempotency-Key}, so it gets the same checks, the same seat and the same confirmation SMS as a
 * customer's booking. The time is the first free slot on the branch's next open day after today,
 * found by {@link SlotCalendar}, so the lead time, closed Sundays and public holidays are respected
 * whatever day the stack starts, and the appointments stay upcoming for the whole demo. The
 * fixtures are at Bellville Kingsway and Umhlanga Ridge only: never at Polokwane, whose single
 * seats {@code make demo-409} races for, nor at Sandton, where the README's walkthrough books and
 * the smoke test reads, so those start from the seats they expect. Each fixture is for a different
 * service at its branch and a different number, so none can take another's seat or overlap it:
 *
 * <ul>
 *   <li>Bellville Kingsway, FICA update, +27 60 000 0001;
 *   <li>Umhlanga Ridge, card replacement, +27 60 000 0002;
 *   <li>Umhlanga Ridge, FICA update, +27 60 000 0000, which the simulated gateway always refuses,
 *       so the inbox also shows a delivery that fails.
 * </ul>
 *
 * <p>It runs only when {@code appointments} is empty, so a restart never adds to a database that
 * holds bookings. It books nothing where {@code branchbook.booking.demo-numbers-only} is on, as on
 * the hosted demo (ADR-0006): the fixtures' numbers are outside the published demo numbers on
 * purpose, so the README's walkthrough never meets them, and the handler, which does not check that
 * rule, would otherwise store numbers that deployment promises it never takes. It also books
 * nothing where {@code branchbook.sms.gateway} is {@code aws}: fixtures booked at start-up must
 * never send a real SMS, whatever their numbers, and only the published demo block is kept
 * simulated there. In either case it says so in one line at INFO instead. It runs on a thread of
 * its own, after the application is ready, so it never delays readiness, and under one correlation
 * id, {@code sys-<uuid>}, which its log lines and the three confirmations carry. A fixture that
 * cannot be booked is logged as a warning, by its branch and service only, and the others carry on;
 * nothing it does can stop the application.
 */
@Component
class DemoDataLoader {

  private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

  /** The fixtures, in the order they are booked. The numbers are synthetic (SECURITY.md). */
  static final List<Fixture> FIXTURES =
      List.of(
          new Fixture("BEL", "FICA", "+27600000001", "Ayanda", "Demo"),
          new Fixture("UMH", "CARD", "+27600000002", "Pieter", "Demo"),
          new Fixture("UMH", "FICA", "+27600000000", "Priya", "Demo"));

  private final BookAppointmentHandler booking;
  private final BranchCalendarProvider calendars;
  private final BookedIntervalsPort bookedIntervals;
  private final JdbcClient jdbc;
  private final Clock clock;
  private final BookingProperties rules;
  private final DemoDataProperties settings;
  private final boolean realSms;
  private final Executor background;

  /**
   * The loader the application uses. The SMS gateway is read as the plain setting, so booking never
   * depends on the notification module's settings type.
   */
  @Autowired
  DemoDataLoader(
      BookAppointmentHandler booking,
      BranchCalendarProvider calendars,
      BookedIntervalsPort bookedIntervals,
      JdbcClient jdbc,
      Clock clock,
      BookingProperties rules,
      DemoDataProperties settings,
      @Value("${branchbook.sms.gateway:simulated}") String smsGateway) {
    this(
        booking,
        calendars,
        bookedIntervals,
        jdbc,
        clock,
        rules,
        settings,
        "aws".equalsIgnoreCase(smsGateway.strip()),
        task -> Thread.ofVirtual().name("demo-data").start(task));
  }

  /** As the application's loader, where the SMS gateway is simulated; the tests use it. */
  DemoDataLoader(
      BookAppointmentHandler booking,
      BranchCalendarProvider calendars,
      BookedIntervalsPort bookedIntervals,
      JdbcClient jdbc,
      Clock clock,
      BookingProperties rules,
      DemoDataProperties settings) {
    this(booking, calendars, bookedIntervals, jdbc, clock, rules, settings, "simulated");
  }

  /** With a simulated gateway and the executor the load is handed to; the tests pass their own. */
  DemoDataLoader(
      BookAppointmentHandler booking,
      BranchCalendarProvider calendars,
      BookedIntervalsPort bookedIntervals,
      JdbcClient jdbc,
      Clock clock,
      BookingProperties rules,
      DemoDataProperties settings,
      Executor background) {
    this(booking, calendars, bookedIntervals, jdbc, clock, rules, settings, false, background);
  }

  /** Every collaborator given, including whether the deployment sends real SMS. */
  DemoDataLoader(
      BookAppointmentHandler booking,
      BranchCalendarProvider calendars,
      BookedIntervalsPort bookedIntervals,
      JdbcClient jdbc,
      Clock clock,
      BookingProperties rules,
      DemoDataProperties settings,
      boolean realSms,
      Executor background) {
    this.booking = booking;
    this.calendars = calendars;
    this.bookedIntervals = bookedIntervals;
    this.jdbc = jdbc;
    this.clock = clock;
    this.rules = rules;
    this.settings = settings;
    this.realSms = realSms;
    this.background = background;
  }

  /**
   * Hands the load to its own thread once the application is ready, if the setting is on. The event
   * listener returns at once, so readiness is never held up.
   */
  @EventListener(ApplicationReadyEvent.class)
  void onApplicationReady() {
    if (!settings.enabled()) {
      return;
    }
    try {
      background.execute(this::loadIfEmpty);
    } catch (RuntimeException failure) {
      log.warn("demo_data.not_started cause={}", failure.getClass().getSimpleName());
    }
  }

  /**
   * Books the fixtures if no appointment exists, the deployment takes any mobile number and its SMS
   * are simulated. It never throws: any failure is logged.
   *
   * @return how many fixtures were booked
   */
  int loadIfEmpty() {
    try (var _ = MDC.putCloseable(CorrelationIds.MDC_KEY, CorrelationIds.systemId())) {
      try {
        if (realSms) {
          log.info("demo_data.skipped reason=real_sms_gateway");
          return 0;
        }
        if (rules.demoNumbersOnly()) {
          log.info("demo_data.skipped reason=demo_numbers_only");
          return 0;
        }
        if (appointmentsExist()) {
          log.info("demo_data.skipped reason=appointments_exist");
          return 0;
        }
        Instant now = clock.instant();
        int booked = 0;
        for (Fixture fixture : FIXTURES) {
          if (book(fixture, now)) {
            booked++;
          }
        }
        log.info("demo_data.loaded booked={} fixtures={}", booked, FIXTURES.size());
        return booked;
      } catch (RuntimeException failure) {
        log.warn("demo_data.failed cause={}", failure.getClass().getSimpleName());
        return 0;
      }
    }
  }

  private boolean appointmentsExist() {
    return Boolean.TRUE.equals(
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM appointments)").query(Boolean.class).single());
  }

  /** Books one fixture; a refusal or a failure is logged by branch and service, and skipped. */
  private boolean book(Fixture fixture, Instant now) {
    try {
      Optional<Instant> startsAt = firstFreeSlotAfterToday(fixture, now);
      if (startsAt.isEmpty()) {
        log.warn(
            "demo_data.no_free_slot branch={} service={}",
            fixture.branchCode(),
            fixture.serviceCode());
        return false;
      }
      booking.handle(fixture.command(startsAt.get()));
      return true;
    } catch (ProblemException refused) {
      log.warn(
          "demo_data.fixture_refused branch={} service={} code={}",
          fixture.branchCode(),
          fixture.serviceCode(),
          refused.code());
    } catch (RuntimeException failure) {
      log.warn(
          "demo_data.fixture_failed branch={} service={} cause={}",
          fixture.branchCode(),
          fixture.serviceCode(),
          failure.getClass().getSimpleName());
    }
    return false;
  }

  /**
   * The first slot with a free seat on the branch's next open day after today, by its own zone and
   * calendar, looking no further than the bookable window.
   */
  private Optional<Instant> firstFreeSlotAfterToday(Fixture fixture, Instant now) {
    BranchCalendar calendar = calendars.calendarFor(fixture.branchCode());
    ServiceOffer service = calendars.offerFor(calendar.branch(), fixture.serviceCode());
    ZoneId zone = calendar.branch().timezone();
    SlotCalendar slots =
        new SlotCalendar(calendar, service, now, rules.leadTime(), rules.horizon());
    LocalDate today = slots.window().firstDate();
    return slots
        .window()
        .dates()
        .filter(date -> date.isAfter(today))
        .flatMap(
            date ->
                slots
                    .slotsOn(
                        date,
                        bookedIntervals.activeOn(
                            calendar.branch().id(), service.serviceId(), date, zone))
                    .stream())
        .filter(slot -> slot.seatsAvailable() > 0)
        .map(Slot::startsAt)
        .findFirst();
  }

  /**
   * One demo booking. Its {@link #toString()} names only the branch and the service, so a fixture
   * can never put a number or a name in a log line.
   *
   * @param branchCode the branch, Bellville or Umhlanga
   * @param serviceCode the service
   * @param mobile the synthetic number, in E.164 form
   * @param firstName the fictional first name
   * @param lastName the fictional last name
   */
  record Fixture(
      String branchCode, String serviceCode, String mobile, String firstName, String lastName) {

    /** The booking's command, with a fresh {@code Idempotency-Key}. */
    BookAppointmentCommand command(Instant startsAt) {
      return new BookAppointmentCommand(
          new IdempotencyKey(UUID.randomUUID()),
          branchCode,
          serviceCode,
          startsAt,
          CustomerName.parse(firstName).orElseThrow(),
          CustomerName.parse(lastName).orElseThrow(),
          MobileNumber.parse(mobile).orElseThrow());
    }

    @Override
    public String toString() {
      return "Fixture[" + branchCode + ", " + serviceCode + ", customer redacted]";
    }
  }
}
