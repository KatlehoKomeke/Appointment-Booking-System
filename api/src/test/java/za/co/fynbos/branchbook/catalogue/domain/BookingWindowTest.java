package za.co.fynbos.branchbook.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.time.Period;
import org.junit.jupiter.api.Test;

/** BR-5: the window of bookable dates is half-open, so a thirty-day horizon is thirty dates. */
class BookingWindowTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

  @Test
  void thirtyDayHorizonGivesExactlyThirtyBookableDates() {
    BookingWindow window = BookingWindow.startingOn(TODAY, Period.ofDays(30));

    assertThat(window.dates()).hasSize(30).startsWith(TODAY).endsWith(LocalDate.of(2026, 11, 24));
  }

  @Test
  void bothEndsAreBookableAndTheDayAfterIsNot() {
    BookingWindow window = BookingWindow.startingOn(TODAY, Period.ofDays(30));

    assertThat(window.contains(TODAY)).isTrue();
    assertThat(window.contains(LocalDate.of(2026, 11, 24))).isTrue();
    assertThat(window.contains(LocalDate.of(2026, 11, 25))).isFalse();
    assertThat(window.contains(TODAY.minusDays(1))).isFalse();
  }

  @Test
  void refusesAWindowThatEndsBeforeItStarts() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new BookingWindow(TODAY, TODAY.minusDays(1)));
  }
}
