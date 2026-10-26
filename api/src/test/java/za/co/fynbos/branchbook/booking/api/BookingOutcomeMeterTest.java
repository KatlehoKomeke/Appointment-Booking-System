package za.co.fynbos.branchbook.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import za.co.fynbos.branchbook.booking.application.AppointmentView;
import za.co.fynbos.branchbook.booking.application.BookingOutcome;
import za.co.fynbos.branchbook.booking.application.BookingResult;
import za.co.fynbos.branchbook.shared.problem.AnsweredProblem;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;

/**
 * FR-30: the booking meter has every outcome as a series at 0 before the first request, and counts
 * each request once: by the booking it noted, which wins over a problem noted after it, or else by
 * the problem it was answered with. MetricsIT counts real requests through {@code
 * /actuator/prometheus}.
 */
class BookingOutcomeMeterTest {

  /**
   * The request attribute {@link AnsweredProblem} notes a code under, which only the problem
   * handler writes; {@link #refusalIsCountedByTheProblemItWasAnsweredWith} fails if it is renamed.
   */
  private static final String ANSWERED_PROBLEM = AnsweredProblem.class.getName() + ".code";

  private final MeterRegistry registry = new SimpleMeterRegistry();
  private final BookingOutcomeMeter meter = new BookingOutcomeMeter(registry);

  /**
   * A dashboard sees every outcome from the first scrape, and a rate over one that never moved is
   * 0, not missing.
   */
  @Test
  void everyOutcomeIsASeriesAtZeroBeforeAnyRequest() {
    assertThat(counts()).containsOnlyKeys(BookingOutcome.values());
    assertThat(counts().values()).containsOnly(0.0);
    assertThat(registry.find(BookingOutcomeMeter.METER).counters())
        .hasSize(BookingOutcome.values().length);
  }

  @Test
  void bookingIsCountedByWhatItReturned() {
    MockHttpServletRequest created = new MockHttpServletRequest();
    MockHttpServletRequest replayed = new MockHttpServletRequest();
    BookingOutcomeMeter.noteBooked(created, new BookingResult.Created(view()));
    BookingOutcomeMeter.noteBooked(replayed, new BookingResult.Replayed(view()));

    complete(created);
    complete(replayed);

    assertThat(counts())
        .containsEntry(BookingOutcome.CONFIRMED, 1.0)
        .containsEntry(BookingOutcome.REPLAYED, 1.0);
    assertThat(total()).isEqualTo(2.0);
  }

  @Test
  void refusalIsCountedByTheProblemItWasAnsweredWith() {
    MockHttpServletRequest refused = new MockHttpServletRequest();
    refused.setAttribute(ANSWERED_PROBLEM, ProblemCode.SLOT_FULL);

    complete(refused);

    assertThat(counts()).containsEntry(BookingOutcome.SLOT_FULL, 1.0);
    assertThat(total()).isEqualTo(1.0);
  }

  /**
   * A booking that committed is counted as booked even when writing its 201 then fails, as when the
   * client has gone, and the request ends as a 500: the appointment exists and holds its seat. A
   * client that retries with the same key is then counted once more, as replayed.
   */
  @Test
  void bookingThatCommittedIsCountedEvenWhenItsResponseThenFails() {
    MockHttpServletRequest booked = new MockHttpServletRequest();
    BookingOutcomeMeter.noteBooked(booked, new BookingResult.Created(view()));
    booked.setAttribute(ANSWERED_PROBLEM, ProblemCode.INTERNAL_ERROR);

    complete(booked);

    assertThat(counts()).containsEntry(BookingOutcome.CONFIRMED, 1.0);
    assertThat(total()).isEqualTo(1.0);
  }

  /**
   * A 500 that booked nothing is a defect, not an outcome; a request with no problem and no booking
   * was refused before it reached the endpoint, such as a 405. Neither is counted.
   */
  @Test
  void internalErrorAndARequestThatNeverReachedTheEndpointAreNotCounted() {
    MockHttpServletRequest failed = new MockHttpServletRequest();
    failed.setAttribute(ANSWERED_PROBLEM, ProblemCode.INTERNAL_ERROR);

    complete(failed);
    complete(new MockHttpServletRequest());

    assertThat(total()).isZero();
  }

  private void complete(MockHttpServletRequest request) {
    meter.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
  }

  /** Each outcome's count; an outcome with no series is missing from the map. */
  private Map<BookingOutcome, Double> counts() {
    Map<BookingOutcome, Double> counts = new EnumMap<>(BookingOutcome.class);
    for (BookingOutcome outcome : BookingOutcome.values()) {
      Counter counter =
          registry
              .find(BookingOutcomeMeter.METER)
              .tag(BookingOutcomeMeter.OUTCOME_TAG, outcome.label())
              .counter();
      if (counter != null) {
        counts.put(outcome, counter.count());
      }
    }
    return counts;
  }

  private double total() {
    return counts().values().stream().mapToDouble(Double::doubleValue).sum();
  }

  /** The appointment a booking returned; the meter only looks at the kind of result. */
  private static AppointmentView view() {
    return mock(AppointmentView.class);
  }
}
