package za.co.fynbos.branchbook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.booking.application.BookingOutcome.CONFIRMED;
import static za.co.fynbos.branchbook.booking.application.BookingOutcome.CUSTOMER_OVERLAP;
import static za.co.fynbos.branchbook.booking.application.BookingOutcome.DUPLICATE;
import static za.co.fynbos.branchbook.booking.application.BookingOutcome.REPLAYED;
import static za.co.fynbos.branchbook.booking.application.BookingOutcome.SLOT_FULL;
import static za.co.fynbos.branchbook.booking.application.BookingOutcome.TRY_AGAIN;
import static za.co.fynbos.branchbook.booking.application.BookingOutcome.VALIDATION;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;

/**
 * FR-30: {@link BookingOutcome} gives every way a booking request can end exactly one outcome, or
 * none on purpose. The table below is the documented mapping (api/README.md); it must name every
 * {@link ProblemCode}, so a new code cannot reach the counter unreviewed.
 */
class BookingOutcomeTest {

  /** Every problem code, and the outcome a booking answered with it is counted as. */
  private static final Map<ProblemCode, Optional<BookingOutcome>> REFUSALS = refusals();

  private static Map<ProblemCode, Optional<BookingOutcome>> refusals() {
    Map<ProblemCode, Optional<BookingOutcome>> refusals = new EnumMap<>(ProblemCode.class);
    refusals.put(ProblemCode.SLOT_FULL, Optional.of(SLOT_FULL));
    refusals.put(ProblemCode.DUPLICATE_APPOINTMENT, Optional.of(DUPLICATE));
    refusals.put(ProblemCode.CUSTOMER_OVERLAP, Optional.of(CUSTOMER_OVERLAP));
    refusals.put(ProblemCode.TRY_AGAIN, Optional.of(TRY_AGAIN));
    for (ProblemCode validation :
        EnumSet.of(
            ProblemCode.VALIDATION_FAILED,
            ProblemCode.IDEMPOTENCY_KEY_REQUIRED,
            ProblemCode.IDEMPOTENCY_KEY_REUSED,
            ProblemCode.BRANCH_NOT_FOUND,
            ProblemCode.SERVICE_NOT_OFFERED_AT_BRANCH,
            ProblemCode.DATE_OUTSIDE_BOOKING_WINDOW,
            ProblemCode.SLOT_ON_CLOSED_DAY,
            ProblemCode.SLOT_OUTSIDE_HOURS,
            ProblemCode.SLOT_OFF_GRID,
            ProblemCode.SLOT_WITHIN_LEAD_TIME,
            ProblemCode.MOBILE_NUMBER_REQUIRED,
            ProblemCode.DEMO_NUMBER_REQUIRED)) {
      refusals.put(validation, Optional.of(VALIDATION));
    }
    // A defect is not an outcome of booking, and the other endpoints' codes cannot answer one.
    for (ProblemCode notCounted :
        EnumSet.of(
            ProblemCode.INTERNAL_ERROR,
            ProblemCode.APPOINTMENT_NOT_FOUND,
            ProblemCode.MESSAGE_NOT_FOUND,
            ProblemCode.TOO_LATE_TO_CANCEL,
            ProblemCode.LOOKUP_RATE_LIMITED)) {
      refusals.put(notCounted, Optional.empty());
    }
    return refusals;
  }

  @Test
  void theTableNamesEveryProblemCode() {
    assertThat(REFUSALS.keySet()).isEqualTo(EnumSet.allOf(ProblemCode.class));
  }

  @Test
  void everyProblemCodeMapsAsDocumented() {
    for (ProblemCode code : ProblemCode.values()) {
      assertThat(BookingOutcome.of(code)).as("%s", code).isEqualTo(REFUSALS.get(code));
    }
  }

  @Test
  void aBookingIsConfirmedAndARepeatIsReplayed() {
    assertThat(BookingOutcome.of(new BookingResult.Created(null))).isEqualTo(CONFIRMED);
    assertThat(BookingOutcome.of(new BookingResult.Replayed(null))).isEqualTo(REPLAYED);
  }

  /** No outcome is dead: each is what at least one result or refusal is counted as. */
  @Test
  void everyOutcomeIsReachable() {
    Set<BookingOutcome> reached =
        Stream.concat(
                Stream.of(CONFIRMED, REPLAYED),
                REFUSALS.values().stream().flatMap(Optional::stream))
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(BookingOutcome.class)));

    assertThat(reached).isEqualTo(EnumSet.allOf(BookingOutcome.class));
  }

  /** The labels are the plan's exact words, and the only values the outcome tag ever has. */
  @Test
  void labelsAreTheLowerCaseNames() {
    assertThat(Arrays.stream(BookingOutcome.values()).map(BookingOutcome::label))
        .containsExactly(
            "confirmed",
            "replayed",
            "slot_full",
            "duplicate",
            "customer_overlap",
            "validation",
            "try_again");
  }
}
