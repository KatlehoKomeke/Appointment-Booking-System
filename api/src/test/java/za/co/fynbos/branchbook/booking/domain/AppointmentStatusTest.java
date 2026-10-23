package za.co.fynbos.branchbook.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.booking.domain.AppointmentStatus.CANCELLED;
import static za.co.fynbos.branchbook.booking.domain.AppointmentStatus.CHECKED_IN;
import static za.co.fynbos.branchbook.booking.domain.AppointmentStatus.COMPLETED;
import static za.co.fynbos.branchbook.booking.domain.AppointmentStatus.CONFIRMED;
import static za.co.fynbos.branchbook.booking.domain.AppointmentStatus.NO_SHOW;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** BR-15: five statuses, four legal transitions, and the statuses that hold a seat. */
class AppointmentStatusTest {

  @Test
  void hasExactlyTheFiveStatusesOfTheLifecycle() {
    assertThat(AppointmentStatus.values())
        .containsExactly(CONFIRMED, CHECKED_IN, COMPLETED, CANCELLED, NO_SHOW);
  }

  @ParameterizedTest
  @EnumSource(names = {"COMPLETED", "CANCELLED", "NO_SHOW"})
  void terminalStatesRejectEveryTransition(AppointmentStatus terminal) {
    assertThat(terminal.isFinal()).isTrue();
    assertThat(AppointmentStatus.values()).noneMatch(terminal::canTransitionTo);
  }

  @Test
  void exactlyFourTransitionsAreLegal() {
    List<String> legal =
        Arrays.stream(AppointmentStatus.values())
            .flatMap(
                from ->
                    Arrays.stream(AppointmentStatus.values())
                        .filter(from::canTransitionTo)
                        .map(to -> from + "->" + to))
            .toList();

    assertThat(legal)
        .containsExactlyInAnyOrder(
            CONFIRMED + "->" + CANCELLED,
            CONFIRMED + "->" + CHECKED_IN,
            CONFIRMED + "->" + NO_SHOW,
            CHECKED_IN + "->" + COMPLETED);
  }

  @Test
  void onlyConfirmedAndCheckedInHoldASeat() {
    assertThat(AppointmentStatus.values())
        .filteredOn(AppointmentStatus::isActive)
        .containsExactly(CONFIRMED, CHECKED_IN);
  }

  @Test
  void activeSqlSpellsTheActiveStatuses() {
    assertThat(AppointmentStatus.ACTIVE_SQL).isEqualTo("('CONFIRMED', 'CHECKED_IN')");
  }
}
