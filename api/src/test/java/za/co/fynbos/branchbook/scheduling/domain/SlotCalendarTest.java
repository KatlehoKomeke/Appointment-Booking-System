package za.co.fynbos.branchbook.scheduling.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.scheduling.domain.SlotProblem.DATE_OUTSIDE_BOOKING_WINDOW;
import static za.co.fynbos.branchbook.scheduling.domain.SlotProblem.SLOT_OFF_GRID;
import static za.co.fynbos.branchbook.scheduling.domain.SlotProblem.SLOT_ON_CLOSED_DAY;
import static za.co.fynbos.branchbook.scheduling.domain.SlotProblem.SLOT_OUTSIDE_HOURS;
import static za.co.fynbos.branchbook.scheduling.domain.SlotProblem.SLOT_WITHIN_LEAD_TIME;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import za.co.fynbos.branchbook.catalogue.domain.Address;
import za.co.fynbos.branchbook.catalogue.domain.BookingWindow;
import za.co.fynbos.branchbook.catalogue.domain.Branch;
import za.co.fynbos.branchbook.catalogue.domain.BranchCalendar;
import za.co.fynbos.branchbook.catalogue.domain.OperatingHours;
import za.co.fynbos.branchbook.catalogue.domain.Province;
import za.co.fynbos.branchbook.catalogue.domain.PublicHoliday;
import za.co.fynbos.branchbook.catalogue.domain.PublicHoliday.Source;
import za.co.fynbos.branchbook.catalogue.domain.PublicHolidayCalendar;
import za.co.fynbos.branchbook.catalogue.domain.ServiceOffer;

/**
 * BR-1 to BR-5 and BR-21: the slots a service can be booked in, and the check of a requested start,
 * at fixed moments. The trading hours are those V3__reference_data.sql seeds.
 */
class SlotCalendarTest {

  private static final ZoneId JOHANNESBURG = ZoneId.of("Africa/Johannesburg");
  private static final Duration LEAD_TIME = Duration.ofHours(2);
  private static final Period HORIZON = Period.ofDays(30);

  /** The service durations the reference data seeds, in minutes. */
  private static final int[] SEEDED_DURATIONS = {15, 30, 45};

  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
  private static final LocalDate TUESDAY = LocalDate.of(2026, 11, 3);
  private static final LocalDate SATURDAY = LocalDate.of(2026, 11, 7);
  private static final LocalDate SUNDAY = LocalDate.of(2026, 11, 8);

  /** Wednesday 4 November 2026, Local Government Elections: a proclaimed public holiday. */
  private static final LocalDate ELECTION_DAY = LocalDate.of(2026, 11, 4);

  /** 06:00 on Monday 2 November 2026 in Johannesburg: the lead time ends at opening time. */
  private static final Instant MONDAY_06_00 = at(MONDAY, "06:00");

  private static final PublicHolidayCalendar HOLIDAYS =
      new PublicHolidayCalendar(
          List.of(
              new PublicHoliday(
                  LocalDate.of(2026, 8, 9), "National Women's Day", Source.SCHEDULE_1),
              new PublicHoliday(ELECTION_DAY, "Local Government Elections", Source.PROCLAMATION)));

  /** BR-1, with BR-2's weekday values: 30-minute last start 16:30, 45-minute 16:15. */
  @Test
  void slotsStartAtOpeningAndStepByServiceDuration() {
    assertThat(startTimes(standardBranch(), 45, TUESDAY))
        .containsExactly(
            "08:00", "08:45", "09:30", "10:15", "11:00", "11:45", "12:30", "13:15", "14:00",
            "14:45", "15:30", "16:15");
    assertThat(startTimes(standardBranch(), 30, TUESDAY))
        .hasSize(18)
        .startsWith("08:00", "08:30")
        .endsWith("16:00", "16:30");
    assertThat(startTimes(standardBranch(), 15, TUESDAY))
        .hasSize(36)
        .startsWith("08:00", "08:15")
        .endsWith("16:45");
    // The grid starts at each day's own opening time: 09:00 on a Sunday.
    assertThat(startTimes(sandton(), 30, SUNDAY))
        .containsExactly("09:00", "09:30", "10:00", "10:30", "11:00", "11:30", "12:00", "12:30");

    SlotCalendar calendar = calendar(standardBranch(), 45);
    assertThat(calendar.slotsOn(TUESDAY, List.of()))
        .allSatisfy(
            slot ->
                assertThat(slot.endsAt()).isEqualTo(slot.startsAt().plus(Duration.ofMinutes(45))));
    assertThat(calendar.validate(at(TUESDAY, "09:30"))).isEmpty();
    assertThat(calendar.validate(at(TUESDAY, "09:00"))).contains(SLOT_OFF_GRID);
    assertThat(calendar.validate(at(TUESDAY, "09:30").plusSeconds(1))).contains(SLOT_OFF_GRID);
  }

  /** BR-2: 08:00 to 13:00 holds six 45-minute slots; a seventh would end at 13:15. */
  @Test
  void saturdayFortyFiveMinuteLastStartIs1145() {
    assertThat(startTimes(standardBranch(), 45, SATURDAY))
        .containsExactly("08:00", "08:45", "09:30", "10:15", "11:00", "11:45");
  }

  /** BR-2: Rustenburg closes at 12:00 on Saturdays. */
  @Test
  void rustenburgSaturdayFortyFiveMinuteLastStartIs1100() {
    assertThat(startTimes(rustenburg(), 45, SATURDAY))
        .containsExactly("08:00", "08:45", "09:30", "10:15", "11:00");
    assertThat(startTimes(rustenburg(), 30, SATURDAY)).endsWith("11:30");
  }

  /** BR-2: a start on the grid is still refused when the appointment would end after closing. */
  @Test
  void onGridStartEndingAfterCloseIsOutsideHours() {
    assertThat(calendar(standardBranch(), 15).validate(at(TUESDAY, "17:00")))
        .contains(SLOT_OUTSIDE_HOURS);
    assertThat(calendar(standardBranch(), 45).validate(at(SATURDAY, "12:30")))
        .contains(SLOT_OUTSIDE_HOURS);
    assertThat(calendar(rustenburg(), 45).validate(at(SATURDAY, "11:45")))
        .contains(SLOT_OUTSIDE_HOURS);
    // Before opening is outside the hours too, and the last slot that fits is accepted.
    assertThat(calendar(standardBranch(), 15).validate(at(TUESDAY, "07:45")))
        .contains(SLOT_OUTSIDE_HOURS);
    assertThat(calendar(standardBranch(), 45).validate(at(SATURDAY, "11:45"))).isEmpty();
  }

  /** BR-21: each request below breaks every rule after the one reported, and none before it. */
  @Test
  void validatePrecedence() {
    SlotCalendar calendar =
        new SlotCalendar(standardBranch(), offer(30, 2), at(MONDAY, "09:00"), LEAD_TIME, HORIZON);

    // Yesterday, a Sunday: outside the window, closed, off the grid, before opening and past.
    assertThat(calendar.validate(at(MONDAY.minusDays(1), "07:10")))
        .contains(DATE_OUTSIDE_BOOKING_WINDOW);
    // Next Sunday: closed, and off the grid at a time the branch never trades.
    assertThat(calendar.validate(at(SUNDAY, "07:10"))).contains(SLOT_ON_CLOSED_DAY);
    // Today, before opening: off the grid and within the lead time as well.
    assertThat(calendar.validate(at(MONDAY, "07:10"))).contains(SLOT_OUTSIDE_HOURS);
    // Today, inside the hours: off the grid and within the lead time.
    assertThat(calendar.validate(at(MONDAY, "09:10"))).contains(SLOT_OFF_GRID);
    // Today, on the grid: only the lead time is broken.
    assertThat(calendar.validate(at(MONDAY, "10:30"))).contains(SLOT_WITHIN_LEAD_TIME);
    assertThat(calendar.validate(at(MONDAY, "11:00"))).isEmpty();
  }

  /** BR-4: at 09:10 the earliest start is 11:10, so the first 15-minute slot is 11:15. */
  @Test
  void excludesSlotsStartingWithinTwoHoursOfNow() {
    SlotCalendar calendar =
        new SlotCalendar(standardBranch(), offer(15, 2), at(MONDAY, "09:10"), LEAD_TIME, HORIZON);

    assertThat(startTimes(calendar.slotsOn(MONDAY, List.of())))
        .startsWith("11:15")
        .endsWith("16:45");
    assertThat(startTimes(calendar.slotsOn(TUESDAY, List.of()))).startsWith("08:00");
    assertThat(calendar.validate(at(MONDAY, "11:00"))).contains(SLOT_WITHIN_LEAD_TIME);
    assertThat(calendar.validate(at(MONDAY, "11:15"))).isEmpty();
  }

  /** BR-4: the lead time is "at least two hours", so a start exactly two hours away is offered. */
  @Test
  void slotStartingExactlyTwoHoursFromNowIsOffered() {
    SlotCalendar calendar =
        new SlotCalendar(standardBranch(), offer(15, 2), at(MONDAY, "09:00"), LEAD_TIME, HORIZON);

    assertThat(startTimes(calendar.slotsOn(MONDAY, List.of()))).startsWith("11:00");
    assertThat(calendar.validate(at(MONDAY, "11:00"))).isEmpty();
  }

  /** BR-4: late in the afternoon the day still trades, but none of its slots can be booked. */
  @Test
  void noSlotIsLeftTodayOnceTheLeadTimeReachesClosing() {
    SlotCalendar calendar =
        new SlotCalendar(standardBranch(), offer(15, 2), at(MONDAY, "15:00"), LEAD_TIME, HORIZON);

    assertThat(calendar.slotsOn(MONDAY, List.of())).isEmpty();
    assertThat(calendar.validate(at(MONDAY, "16:45"))).contains(SLOT_WITHIN_LEAD_TIME);
  }

  /** BR-3. */
  @Test
  void dayOfTheWeekWithoutHoursHasNoSlots() {
    SlotCalendar calendar = calendar(standardBranch(), 30);

    assertThat(calendar.slotsOn(SUNDAY, List.of())).isEmpty();
    assertThat(calendar.validate(at(SUNDAY, "09:00"))).contains(SLOT_ON_CLOSED_DAY);
  }

  /** BR-3. */
  @Test
  void publicHolidayHasNoSlots() {
    SlotCalendar calendar = calendar(standardBranch(), 30);

    assertThat(calendar.slotsOn(ELECTION_DAY, List.of())).isEmpty();
    assertThat(calendar.validate(at(ELECTION_DAY, "09:00"))).contains(SLOT_ON_CLOSED_DAY);
  }

  /** BR-3: National Women's Day fell on Sunday 9 August 2026, so Monday 10 August is a holiday. */
  @Test
  void mondayAfterASundayHolidayHasNoSlots() {
    LocalDate observed = LocalDate.of(2026, 8, 10);
    SlotCalendar calendar =
        new SlotCalendar(
            standardBranch(),
            offer(30, 2),
            at(LocalDate.of(2026, 8, 3), "06:00"),
            LEAD_TIME,
            HORIZON);

    assertThat(calendar.slotsOn(observed, List.of())).isEmpty();
    assertThat(calendar.validate(at(observed, "09:00"))).contains(SLOT_ON_CLOSED_DAY);
  }

  /** BR-3: a branch that trades on public holidays keeps its normal grid on one. */
  @Test
  void branchOpenOnPublicHolidaysHasItsUsualSlotsOnAHoliday() {
    SlotCalendar calendar = calendar(branch("13:00", false, true), 30);

    assertThat(calendar.slotsOn(ELECTION_DAY, List.of())).hasSize(18);
    assertThat(calendar.validate(at(ELECTION_DAY, "09:00"))).isEmpty();
  }

  /** BR-5: thirty bookable dates, today through today plus 29. */
  @Test
  void onlyTheThirtyDatesFromTodayHaveSlots() {
    SlotCalendar calendar = calendar(standardBranch(), 30);
    LocalDate lastDate = LocalDate.of(2026, 12, 1);

    assertThat(calendar.window()).isEqualTo(new BookingWindow(MONDAY, lastDate));
    assertThat(calendar.slotsOn(lastDate, List.of())).hasSize(18);
    assertThat(calendar.slotsOn(lastDate.plusDays(1), List.of())).isEmpty();
    assertThat(calendar.slotsOn(MONDAY.minusDays(2), List.of())).isEmpty();
    assertThat(calendar.validate(at(lastDate, "09:00"))).isEmpty();
    assertThat(calendar.validate(at(lastDate.plusDays(1), "09:00")))
        .contains(DATE_OUTSIDE_BOOKING_WINDOW);
  }

  /** BR-5: at 22:30 UTC on 1 November it is already 2 November in Johannesburg. */
  @Test
  void todayIsTheBranchsDateNotTheUtcDate() {
    SlotCalendar calendar =
        new SlotCalendar(
            standardBranch(),
            offer(30, 2),
            Instant.parse("2026-11-01T22:30:00Z"),
            LEAD_TIME,
            HORIZON);

    assertThat(calendar.window().firstDate()).isEqualTo(MONDAY);
  }

  /** Each slot counts the seats that no active appointment holds at any moment of it. */
  @Test
  void seatsAvailableCountsTheSeatsHeldDuringTheSlot() {
    List<BookedInterval> booked =
        List.of(
            new BookedInterval(1, at(TUESDAY, "09:00"), at(TUESDAY, "09:30")),
            new BookedInterval(1, at(TUESDAY, "10:00"), at(TUESDAY, "10:30")),
            new BookedInterval(2, at(TUESDAY, "10:00"), at(TUESDAY, "10:30")));

    Map<String, Integer> seats = seatsByStart(calendar(standardBranch(), 30), TUESDAY, booked);

    // 08:30 to 09:00 ends as the 09:00 appointment starts, so the two do not overlap.
    assertThat(seats)
        .containsEntry("08:30", 2)
        .containsEntry("09:00", 1)
        .containsEntry("09:30", 2)
        .containsEntry("10:00", 0)
        .containsEntry("10:30", 2)
        .hasSize(18);
  }

  /**
   * Appointments booked before a service went from 15 to 30 minutes sit on the old grid. Seat 1
   * holds two of them inside the 09:00 slot, but it is one seat, so seat 2 is still free. Counting
   * appointments instead of seats would show the slot as full.
   */
  @Test
  void seatHeldTwiceDuringASlotIsCountedOnce() {
    List<BookedInterval> fifteenMinuteBookings =
        List.of(
            new BookedInterval(1, at(TUESDAY, "09:00"), at(TUESDAY, "09:15")),
            new BookedInterval(1, at(TUESDAY, "09:15"), at(TUESDAY, "09:30")));

    assertThat(
            seatsByStart(calendar(standardBranch(), offer(30, 2)), TUESDAY, fifteenMinuteBookings))
        .containsEntry("09:00", 1)
        .containsEntry("09:30", 2);
  }

  /**
   * A branch can cut a service's capacity after seats were booked. Two seats are then held where
   * one exists, and the slot shows none free rather than a negative number.
   */
  @Test
  void seatsAvailableNeverGoesBelowZeroAfterTheCapacityIsCut() {
    List<BookedInterval> bothSeats =
        List.of(
            new BookedInterval(1, at(TUESDAY, "09:00"), at(TUESDAY, "09:30")),
            new BookedInterval(2, at(TUESDAY, "09:00"), at(TUESDAY, "09:30")));

    assertThat(seatsByStart(calendar(standardBranch(), offer(30, 1)), TUESDAY, bothSeats))
        .containsEntry("09:00", 0)
        .containsEntry("09:30", 1);
  }

  /**
   * BR-7: when 09:00 fills up, the alternatives are the next free slots that day, skipping full
   * ones; near closing there are fewer than three, and never one on the next day.
   */
  @Test
  void nextFreeSlotsSkipFullSlotsAndStayOnTheSameDay() {
    SlotCalendar calendar = calendar(standardBranch(), offer(15, 1));
    List<BookedInterval> booked =
        List.of(
            new BookedInterval(1, at(TUESDAY, "09:15"), at(TUESDAY, "09:30")),
            new BookedInterval(1, at(TUESDAY, "09:45"), at(TUESDAY, "10:00")));

    assertThat(startTimes(calendar.freeSlotsAfter(at(TUESDAY, "09:00"), booked, 3)))
        .containsExactly("09:30", "10:00", "10:15");
    assertThat(startTimes(calendar.freeSlotsAfter(at(TUESDAY, "16:30"), booked, 3)))
        .containsExactly("16:45");
    assertThat(calendar.freeSlotsAfter(at(TUESDAY, "16:45"), booked, 3)).isEmpty();
  }

  /**
   * BR-20: the hours are read in the branch's zone, never the JVM's. New York leaves daylight
   * saving on Sunday 1 November 2026, the day before the date asked for.
   */
  @Test
  void producesSameSlotsUnderAnyJvmDefaultZone() {
    TimeZone original = TimeZone.getDefault();
    List<List<Slot>> results = new ArrayList<>();
    try {
      for (String zone : List.of("UTC", "America/New_York", "Pacific/Kiritimati", "Asia/Kolkata")) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        results.add(calendar(standardBranch(), 30).slotsOn(MONDAY, List.of()));
      }
    } finally {
      TimeZone.setDefault(original);
    }

    assertThat(results).allSatisfy(slots -> assertThat(slots).isEqualTo(results.getFirst()));
    assertThat(results.getFirst().getFirst().startsAt())
        .isEqualTo(Instant.parse("2026-11-02T06:00:00Z"));
  }

  /**
   * Availability lists slots and booking will validate a start, so the two must never disagree: for
   * every minute of the day, a start passes {@code validate} exactly when it is a listed slot.
   * Monday's lead time cuts the morning off, so the lead-time boundary is covered as well.
   */
  @Test
  void validateAgreesWithGeneratedSlotsForEverySeededHoursPatternAndDuration() {
    record Day(String hours, BranchCalendar branch, LocalDate date) {}
    List<Day> days =
        List.of(
            new Day("Monday 08:00-17:00 after the lead time", standardBranch(), MONDAY),
            new Day("weekday 08:00-17:00", standardBranch(), TUESDAY),
            new Day("Saturday 08:00-13:00", standardBranch(), SATURDAY),
            new Day("Rustenburg Saturday 08:00-12:00", rustenburg(), SATURDAY),
            new Day("Sunday 09:00-13:00", sandton(), SUNDAY));
    Instant now = at(MONDAY, "09:10");
    List<String> disagreements = new ArrayList<>();

    for (Day day : days) {
      for (int minutes : SEEDED_DURATIONS) {
        SlotCalendar calendar =
            new SlotCalendar(day.branch(), offer(minutes, 2), now, LEAD_TIME, HORIZON);
        Set<Instant> listed =
            calendar.slotsOn(day.date(), List.of()).stream()
                .map(Slot::startsAt)
                .collect(Collectors.toSet());
        for (Instant start = at(day.date(), "00:00");
            start.isBefore(at(day.date().plusDays(1), "00:00"));
            start = start.plus(Duration.ofMinutes(1))) {
          if (calendar.validate(start).isEmpty() != listed.contains(start)) {
            disagreements.add(day.hours() + ", " + minutes + " minutes, " + start);
          }
        }
      }
    }

    assertThat(disagreements).isEmpty();
  }

  /** Monday to Friday 08:00 to 17:00 and Saturday 08:00 to 13:00, as most seeded branches trade. */
  private static BranchCalendar standardBranch() {
    return branch("13:00", false, false);
  }

  /** Rustenburg, which closes at 12:00 on Saturdays. */
  private static BranchCalendar rustenburg() {
    return branch("12:00", false, false);
  }

  /** Sandton, which also trades 09:00 to 13:00 on Sundays. */
  private static BranchCalendar sandton() {
    return branch("13:00", true, false);
  }

  private static BranchCalendar branch(
      String saturdayCloses, boolean tradesOnSundays, boolean openOnPublicHolidays) {
    List<OperatingHours> week = new ArrayList<>();
    for (DayOfWeek day : EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)) {
      week.add(hours(day, "08:00", "17:00"));
    }
    week.add(hours(DayOfWeek.SATURDAY, "08:00", saturdayCloses));
    if (tradesOnSundays) {
      week.add(hours(DayOfWeek.SUNDAY, "09:00", "13:00"));
    }
    Branch branch =
        new Branch(
            UUID.randomUUID(),
            "TST",
            "Test Branch",
            "Test Branch",
            new Address("1 Test Road", "Testville", "Test City", Province.GAUTENG, "2000"),
            JOHANNESBURG,
            openOnPublicHolidays);
    return new BranchCalendar(branch, week, HOLIDAYS);
  }

  private static OperatingHours hours(DayOfWeek day, String opens, String closes) {
    return new OperatingHours(day, LocalTime.parse(opens), LocalTime.parse(closes));
  }

  private static ServiceOffer offer(int durationMinutes, int capacity) {
    return new ServiceOffer(UUID.randomUUID(), "TEST", "Test", durationMinutes, capacity);
  }

  /** The calendar at 06:00 on Monday 2 November, with two seats. */
  private static SlotCalendar calendar(BranchCalendar branch, int durationMinutes) {
    return calendar(branch, offer(durationMinutes, 2));
  }

  private static SlotCalendar calendar(BranchCalendar branch, ServiceOffer service) {
    return new SlotCalendar(branch, service, MONDAY_06_00, LEAD_TIME, HORIZON);
  }

  /** A wall-clock time in Johannesburg. */
  private static Instant at(LocalDate date, String time) {
    return date.atTime(LocalTime.parse(time)).atZone(JOHANNESBURG).toInstant();
  }

  private static List<String> startTimes(
      BranchCalendar branch, int durationMinutes, LocalDate date) {
    return startTimes(calendar(branch, durationMinutes).slotsOn(date, List.of()));
  }

  /** The slots' start times as Johannesburg wall-clock times, such as {@code 08:45}. */
  private static List<String> startTimes(List<Slot> slots) {
    return slots.stream()
        .map(slot -> LocalTime.ofInstant(slot.startsAt(), JOHANNESBURG).toString())
        .toList();
  }

  private static Map<String, Integer> seatsByStart(
      SlotCalendar calendar, LocalDate date, List<BookedInterval> booked) {
    Map<String, Integer> seats = new LinkedHashMap<>();
    for (Slot slot : calendar.slotsOn(date, booked)) {
      seats.put(
          LocalTime.ofInstant(slot.startsAt(), JOHANNESBURG).toString(), slot.seatsAvailable());
    }
    return seats;
  }
}
