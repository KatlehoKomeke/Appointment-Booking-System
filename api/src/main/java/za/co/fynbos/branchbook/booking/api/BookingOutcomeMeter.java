package za.co.fynbos.branchbook.booking.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import za.co.fynbos.branchbook.booking.application.BookingOutcome;
import za.co.fynbos.branchbook.booking.application.BookingResult;
import za.co.fynbos.branchbook.shared.problem.AnsweredProblem;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;

/**
 * Counts every {@code POST /api/v1/appointments} once, by how it ended: the counter {@value
 * #METER}, which Prometheus reads as {@code appointments_booked_total}, with one tag, {@code
 * outcome}, from {@link BookingOutcome} (FR-30).
 *
 * <p>It counts after the response is written, so it sees every way a booking request can end, not
 * only those that reach the controller: a body Spring could not read or validate is counted as
 * {@code validation}, and a lock or pool timeout that only the problem handler turns into {@code
 * 503 TRY_AGAIN} as {@code try_again}. A request that booked is counted from the result the
 * controller {@linkplain #noteBooked notes}; any other, from the problem it was answered with
 * ({@link AnsweredProblem}). The booking wins: once it has committed, the appointment exists and
 * holds its seat, so it is counted as {@code confirmed} even if writing its {@code 201} then fails,
 * as when the client has gone, and the request ends as a {@code 500}. A client that retries with
 * the same key is then counted once more, as {@code replayed}, so {@code confirmed} counts exactly
 * the appointments booked through the API.
 *
 * <p>Not counted: a {@code 500 INTERNAL_ERROR} that booked nothing (see {@link
 * BookingOutcome#of(ProblemCode)}), and a request Spring refuses before it reaches the endpoint,
 * such as a wrong method or media type ({@code 405}, {@code 415}). Bookings that do not come
 * through HTTP, such as the demo data, are not booking requests and are not counted either.
 *
 * <p>All seven series are registered at start-up at zero, so a dashboard sees each outcome from the
 * first scrape and a rate over a series that has never moved is 0, not missing.
 */
@Component
class BookingOutcomeMeter implements HandlerInterceptor, WebMvcConfigurer {

  /** The counter's name, in Micrometer's dotted form. */
  static final String METER = "appointments.booked";

  /** The counter's one tag. */
  static final String OUTCOME_TAG = "outcome";

  /** The request attribute that holds the outcome of a request that booked. */
  private static final String BOOKED = BookingOutcomeMeter.class.getName() + ".booked";

  private final Map<BookingOutcome, Counter> counters = new EnumMap<>(BookingOutcome.class);

  BookingOutcomeMeter(MeterRegistry registry) {
    for (BookingOutcome outcome : BookingOutcome.values()) {
      counters.put(
          outcome,
          Counter.builder(METER)
              .description("Booking requests, by how they ended")
              .tag(OUTCOME_TAG, outcome.label())
              .register(registry));
    }
  }

  /**
   * Notes on the request that it booked, for the count once the response is written.
   *
   * @param request the booking request
   * @param result what the booking returned
   */
  static void noteBooked(HttpServletRequest request, BookingResult result) {
    request.setAttribute(BOOKED, BookingOutcome.of(result));
  }

  /**
   * Runs this meter on the booking endpoint only. Lookup and any other path under it are not
   * matched, because the pattern has no wildcard.
   *
   * @param registry Spring MVC's interceptors
   */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this).addPathPatterns(AppointmentController.PATH);
  }

  /**
   * Counts the request once its response is written, whether it booked or was refused.
   *
   * @param request the request
   * @param response the response, already written
   * @param handler the controller method that handled it
   * @param failure an exception no handler resolved, or {@code null}; a refusal has been resolved
   *     into a problem by then, which {@link AnsweredProblem} holds
   */
  @Override
  public void afterCompletion(
      HttpServletRequest request,
      HttpServletResponse response,
      Object handler,
      @Nullable Exception failure) {
    outcome(request).map(counters::get).ifPresent(Counter::increment);
  }

  /**
   * The outcome to count: the booking the request noted, which a problem noted after it, such as a
   * response that could not be written, does not undo; otherwise the problem it was answered with.
   */
  private static Optional<BookingOutcome> outcome(HttpServletRequest request) {
    if (request.getAttribute(BOOKED) instanceof BookingOutcome booked) {
      return Optional.of(booked);
    }
    return AnsweredProblem.of(request).flatMap(BookingOutcome::of);
  }
}
