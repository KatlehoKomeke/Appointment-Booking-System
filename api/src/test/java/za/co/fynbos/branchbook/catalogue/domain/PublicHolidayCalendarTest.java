package za.co.fynbos.branchbook.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import za.co.fynbos.branchbook.catalogue.domain.PublicHoliday.Source;

/**
 * BR-3: the public holidays branches close on, including section 2(1) of the Public Holidays Act: a
 * holiday on a Sunday makes the following Monday a holiday; a holiday on a Saturday gets nothing.
 */
class PublicHolidayCalendarTest {

  @Test
  void sundayHoliday9Aug2026IsObservedOnMonday10Aug() {
    PublicHolidayCalendar calendar = calendarOf(holiday("2026-08-09", "National Women's Day"));

    assertThat(calendar.holidayOn(LocalDate.of(2026, 8, 10)))
        .contains("National Women's Day (observed)");
  }

  @Test
  void sundayHolidays21Mar2027And26Dec2027AreObservedOnTheFollowingMonday() {
    PublicHolidayCalendar calendar =
        calendarOf(
            holiday("2027-03-21", "Human Rights Day"),
            holiday("2027-12-25", "Christmas Day"),
            holiday("2027-12-26", "Day of Goodwill"));

    assertThat(calendar.holidayOn(LocalDate.of(2027, 3, 22)))
        .contains("Human Rights Day (observed)");
    assertThat(calendar.holidayOn(LocalDate.of(2027, 12, 27)))
        .contains("Day of Goodwill (observed)");
  }

  @Test
  void saturdayHoliday21Mar2026GetsNoSubstitute() {
    PublicHolidayCalendar calendar = calendarOf(holiday("2026-03-21", "Human Rights Day"));

    assertThat(calendar.isHoliday(LocalDate.of(2026, 3, 21))).isTrue();
    assertThat(calendar.isHoliday(LocalDate.of(2026, 3, 22))).isFalse();
    assertThat(calendar.isHoliday(LocalDate.of(2026, 3, 23))).isFalse();
  }

  /** In 2022, Christmas fell on a Sunday and the Monday was already the Day of Goodwill. */
  @Test
  void mondayThatIsAlreadyAHolidayKeepsItsOwnName() {
    PublicHolidayCalendar calendar =
        calendarOf(
            holiday("2022-12-25", "Christmas Day"), holiday("2022-12-26", "Day of Goodwill"));

    assertThat(calendar.holidayOn(LocalDate.of(2022, 12, 26))).contains("Day of Goodwill");
  }

  @Test
  void proclaimedDayIsAHoliday() {
    PublicHolidayCalendar calendar =
        new PublicHolidayCalendar(
            List.of(
                new PublicHoliday(
                    LocalDate.of(2026, 11, 4), "Local Government Elections", Source.PROCLAMATION)));

    assertThat(calendar.holidayOn(LocalDate.of(2026, 11, 4)))
        .contains("Local Government Elections");
  }

  @Test
  void ordinaryDayIsNotAHoliday() {
    PublicHolidayCalendar calendar = calendarOf(holiday("2026-12-16", "Day of Reconciliation"));

    assertThat(calendar.holidayOn(LocalDate.of(2026, 12, 15))).isEmpty();
  }

  @Test
  void coverageEndsOnTheLastDayOfTheLastYearItHolds() {
    PublicHolidayCalendar calendar =
        calendarOf(
            holiday("2026-01-01", "New Year's Day"), holiday("2027-01-01", "New Year's Day"));

    assertThat(calendar.coveredThrough()).isEqualTo(LocalDate.of(2027, 12, 31));
  }

  @Test
  void refusesToBeBuiltWithoutHolidays() {
    assertThatIllegalArgumentException().isThrownBy(() -> new PublicHolidayCalendar(List.of()));
  }

  private static PublicHolidayCalendar calendarOf(PublicHoliday... holidays) {
    return new PublicHolidayCalendar(Stream.of(holidays).toList());
  }

  private static PublicHoliday holiday(String date, String name) {
    return new PublicHoliday(LocalDate.parse(date), name, Source.SCHEDULE_1);
  }
}
