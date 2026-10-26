package za.co.fynbos.branchbook.booking.application;

import java.util.Locale;
import java.util.Optional;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;

/**
 * How a booking request ended, as the {@code appointments.booked} counter labels it (FR-30): one
 * outcome per answer the booking endpoint gives, so a dashboard can tell demand, contention and
 * client mistakes apart.
 *
 * <p>This class is the one place that decides the outcome. {@link #of(BookingResult)} covers the
 * answers that booked, and {@link #of(ProblemCode)} every refusal. Both are switches without a
 * default, so a new kind of result or a new problem code does not compile until it is given an
 * outcome here. The label is the only tag the counter has, and it is always one of these seven
 * fixed words, never a value from the request.
 */
public enum BookingOutcome {

  /** A new appointment was booked: {@code 201}. */
  CONFIRMED,

  /** The request repeated one that had booked, and got the same appointment back: {@code 201}. */
  REPLAYED,

  /** Every seat of the slot was taken while the customer was booking: {@code 409 SLOT_FULL}. */
  SLOT_FULL,

  /**
   * The customer already has an upcoming appointment for this service: {@code 409
   * DUPLICATE_APPOINTMENT}.
   */
  DUPLICATE,

  /** The customer already has an appointment at that time: {@code 409 CUSTOMER_OVERLAP}. */
  CUSTOMER_OVERLAP,

  /**
   * The request could never have booked as sent: a {@code 400} for a malformed body, field or
   * header or a missing {@code Idempotency-Key}; a {@code 404} for an unknown branch; or a {@code
   * 422} for a service the branch does not offer, a start that is not a slot, a number that is not
   * a South African mobile, a number outside the published demo numbers where a deployment takes
   * only those, or a key reused with different details. So this count is not the count of {@code
   * 400}s in {@code http_server_requests_seconds}.
   */
  VALIDATION,

  /**
   * A temporary failure the client may retry: {@code 503 TRY_AGAIN}, for a lock not granted in 2
   * seconds, no free connection in 3, a statement over 5, or a second deadlock or serialization
   * conflict.
   */
  TRY_AGAIN;

  /**
   * Returns the value of the counter's {@code outcome} tag.
   *
   * @return the name in lower case, for example {@code slot_full}
   */
  public String label() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Returns the outcome of a request that booked.
   *
   * @param result what the booking returned
   * @return {@link #CONFIRMED} for a new appointment, {@link #REPLAYED} for a replay
   */
  public static BookingOutcome of(BookingResult result) {
    return switch (result) {
      case BookingResult.Created _ -> CONFIRMED;
      case BookingResult.Replayed _ -> REPLAYED;
    };
  }

  /**
   * Returns the outcome of a booking request that was answered with a problem.
   *
   * <p>Two kinds of answer are deliberately not counted. {@code INTERNAL_ERROR} is a defect, not an
   * outcome of booking; it is logged with its correlation id and shows in {@code
   * http_server_requests_seconds} as a 500. And the codes of other endpoints, such as {@code
   * LOOKUP_RATE_LIMITED} (the booking endpoint has no 429) or {@code TOO_LATE_TO_CANCEL}, cannot
   * answer a booking; should one ever do so, it is left out rather than counted as something it is
   * not.
   *
   * @param problem the code the request was answered with
   * @return the outcome, or empty when the answer is not counted
   */
  public static Optional<BookingOutcome> of(ProblemCode problem) {
    return switch (problem) {
      case SLOT_FULL -> Optional.of(SLOT_FULL);
      case DUPLICATE_APPOINTMENT -> Optional.of(DUPLICATE);
      case CUSTOMER_OVERLAP -> Optional.of(CUSTOMER_OVERLAP);
      case TRY_AGAIN -> Optional.of(TRY_AGAIN);
      case VALIDATION_FAILED,
          IDEMPOTENCY_KEY_REQUIRED,
          IDEMPOTENCY_KEY_REUSED,
          BRANCH_NOT_FOUND,
          SERVICE_NOT_OFFERED_AT_BRANCH,
          DATE_OUTSIDE_BOOKING_WINDOW,
          SLOT_ON_CLOSED_DAY,
          SLOT_OUTSIDE_HOURS,
          SLOT_OFF_GRID,
          SLOT_WITHIN_LEAD_TIME,
          MOBILE_NUMBER_REQUIRED,
          DEMO_NUMBER_REQUIRED ->
          Optional.of(VALIDATION);
      case INTERNAL_ERROR,
          APPOINTMENT_NOT_FOUND,
          MESSAGE_NOT_FOUND,
          TOO_LATE_TO_CANCEL,
          LOOKUP_RATE_LIMITED ->
          Optional.empty();
    };
  }
}
