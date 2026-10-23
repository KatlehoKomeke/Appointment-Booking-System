package za.co.fynbos.branchbook.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import za.co.fynbos.branchbook.catalogue.domain.ClosedDay.Reason;
import za.co.fynbos.branchbook.catalogue.domain.PublicHoliday.Source;

/** BR-3: which days a branch takes appointments on, and why it is closed on the others. */
class BranchCalendarTest {

  private static final PublicHolidayCalendar HOLIDAYS =
      new PublicHolidayCalendar(
          List.of(
              new PublicHoliday(
                  LocalDate.of(2026, 8, 9), "National Women's Day", Source.SCHEDULE_1),
              new PublicHoliday(
                  LocalDate.of(2026, 12, 16), "Day of Reconciliation", Source.SCHEDULE_1)));

  /** Wednesday 16 December 2026. */
  private static final LocalDate DAY_OF_RECONCILIATION = LocalDate.of(2026, 12, 16);

  @Test
  void publicHolidayClosesABranchThatTradesThatWeekday() {
    BranchCalendar calendar = weekdaysAndSaturdays(false);

    assertThat(calendar.closure(DAY_OF_RECONCILIATION))
        .contains(
            new ClosedDay(DAY_OF_RECONCILIATION, Reason.PUBLIC_HOLIDAY, "Day of Reconciliation"));
    assertThat(calendar.hoursOn(DAY_OF_RECONCILIATION)).isEmpty();
  }

  @Test
  void observedMondayClosesTheBranch() {
    LocalDate observed = LocalDate.of(2026, 8, 10);

    assertThat(weekdaysAndSaturdays(false).closure(observed))
        .contains(
            new ClosedDay(observed, Reason.PUBLIC_HOLIDAY, "National Women's Day (observed)"));
  }

  @Test
  void dayOfTheWeekWithoutHoursIsAClosedDay() {
    LocalDate sunday = LocalDate.of(2026, 11, 22);

    assertThat(weekdaysAndSaturdays(false).closure(sunday))
        .contains(new ClosedDay(sunday, Reason.CLOSED_DAY, null));
  }

  /** The holiday's name tells the customer more than "closed on Sundays" does. */
  @Test
  void holidayOnADayTheBranchNeverTradesIsReportedAsTheHoliday() {
    LocalDate sundayHoliday = LocalDate.of(2026, 8, 9);

    assertThat(weekdaysAndSaturdays(false).closure(sundayHoliday))
        .map(ClosedDay::reason)
        .contains(Reason.PUBLIC_HOLIDAY);
  }

  @Test
  void branchOpenOnPublicHolidaysTradesItsNormalHours() {
    BranchCalendar calendar = weekdaysAndSaturdays(true);

    assertThat(calendar.closure(DAY_OF_RECONCILIATION)).isEmpty();
    assertThat(calendar.hoursOn(DAY_OF_RECONCILIATION))
        .contains(hours(DayOfWeek.WEDNESDAY, "08:00", "17:00"));
  }

  @Test
  void closuresInAWindowAreListedInDateOrder() {
    BookingWindow window = BookingWindow.startingOn(LocalDate.of(2026, 12, 14), Period.ofDays(7));

    assertThat(weekdaysAndSaturdays(false).closuresIn(window))
        .extracting(ClosedDay::date)
        .containsExactly(DAY_OF_RECONCILIATION, LocalDate.of(2026, 12, 20));
  }

  @Test
  void refusesTwoSetsOfHoursForOneDay() {
    List<OperatingHours> hours =
        List.of(
            hours(DayOfWeek.MONDAY, "08:00", "17:00"), hours(DayOfWeek.MONDAY, "09:00", "12:00"));

    assertThatIllegalArgumentException()
        .isThrownBy(() -> new BranchCalendar(branch(false), hours, HOLIDAYS));
  }

  /** Monday to Friday 08:00 to 17:00, Saturday 08:00 to 13:00, closed on Sundays. */
  private static BranchCalendar weekdaysAndSaturdays(boolean openOnPublicHolidays) {
    List<OperatingHours> week = new ArrayList<>();
    for (DayOfWeek day : List.of(DayOfWeek.values()).subList(0, 5)) {
      week.add(hours(day, "08:00", "17:00"));
    }
    week.add(hours(DayOfWeek.SATURDAY, "08:00", "13:00"));
    return new BranchCalendar(branch(openOnPublicHolidays), week, HOLIDAYS);
  }

  private static OperatingHours hours(DayOfWeek day, String opens, String closes) {
    return new OperatingHours(day, LocalTime.parse(opens), LocalTime.parse(closes));
  }

  private static Branch branch(boolean openOnPublicHolidays) {
    return new Branch(
        UUID.randomUUID(),
        "TST",
        "Test Branch",
        "Test Branch",
        new Address("1 Test Road", "Testville", "Test City", Province.GAUTENG, "2000"),
        ZoneId.of("Africa/Johannesburg"),
        openOnPublicHolidays);
  }
}
