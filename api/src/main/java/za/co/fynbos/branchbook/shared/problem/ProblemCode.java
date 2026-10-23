package za.co.fynbos.branchbook.shared.problem;

/**
 * Every error code the API can return, with its HTTP status and title.
 *
 * <p>The codes are a public contract: the web app chooses its wording from the code, never from the
 * detail text. docs/errors.md describes when each one happens and what the customer sees, and
 * {@code ProblemCodeCatalogueTest} fails the build if this enum and that table disagree.
 *
 * <p>The HTTP status is a plain number so that domain code can throw a {@link ProblemException}
 * without depending on Spring.
 */
public enum ProblemCode {
  /** The request is malformed: a missing or badly shaped field, parameter or header. */
  VALIDATION_FAILED(400, "Some details need attention"),
  /** A booking request arrived without an {@code Idempotency-Key} header holding a UUID. */
  IDEMPOTENCY_KEY_REQUIRED(400, "Idempotency-Key header required"),
  /** The branch code is unknown, or the branch is inactive. */
  BRANCH_NOT_FOUND(404, "Branch not found"),
  /** The reference is unknown or the last four digits are wrong; the two are indistinguishable. */
  APPOINTMENT_NOT_FOUND(404, "Appointment not found"),
  /** A simulated message id does not exist. */
  MESSAGE_NOT_FOUND(404, "Message not found"),
  /** The branch does not offer the requested service. */
  SERVICE_NOT_OFFERED_AT_BRANCH(422, "Service not offered at this branch"),
  /** The date is outside the bookable window. */
  DATE_OUTSIDE_BOOKING_WINDOW(422, "Date outside the booking window"),
  /** The branch is closed that day, or it is a public holiday. */
  SLOT_ON_CLOSED_DAY(422, "The branch is closed that day"),
  /** The slot would start before opening or end after closing. */
  SLOT_OUTSIDE_HOURS(422, "Outside trading hours"),
  /** The start time is not on the service's slot grid. */
  SLOT_OFF_GRID(422, "Not a bookable start time"),
  /** The slot starts less than the lead time from now. */
  SLOT_WITHIN_LEAD_TIME(422, "That time is too soon"),
  /** The number is not a South African mobile number. */
  MOBILE_NUMBER_REQUIRED(422, "We need a cellphone number"),
  /** The same {@code Idempotency-Key} was sent with a different request body. */
  IDEMPOTENCY_KEY_REUSED(422, "Idempotency-Key reused with different details"),
  /** The mobile number already has an upcoming appointment for this service. */
  DUPLICATE_APPOINTMENT(409, "You already have this appointment"),
  /** The mobile number already has an appointment at an overlapping time. */
  CUSTOMER_OVERLAP(409, "You already have an appointment at that time"),
  /** The last seat was taken while the customer was completing their details. */
  SLOT_FULL(409, "That time was just taken"),
  /** Cancellation was requested at or after the appointment's start. */
  TOO_LATE_TO_CANCEL(409, "This appointment has already started"),
  /** Too many wrong lookups for one reference within an hour. */
  LOOKUP_RATE_LIMITED(429, "Too many attempts"),
  /** An unexpected failure; only the correlation id is returned. */
  INTERNAL_ERROR(500, "Something went wrong"),
  /** A temporary database problem; the request can be retried. */
  TRY_AGAIN(503, "Please try again");

  private static final String TYPE_PREFIX = "urn:branchbook:problem:";

  private final int status;
  private final String title;

  ProblemCode(int status, String title) {
    this.status = status;
    this.title = title;
  }

  /**
   * Returns the HTTP status this code is always returned with.
   *
   * @return the HTTP status code, for example 404
   */
  public int status() {
    return status;
  }

  /**
   * Returns the short, plain-language title of the problem.
   *
   * @return the problem body's {@code title}
   */
  public String title() {
    return title;
  }

  /**
   * Returns the problem type that identifies this code, as RFC 9457 requires.
   *
   * @return for example {@code urn:branchbook:problem:SLOT_FULL}
   */
  public String type() {
    return TYPE_PREFIX + name();
  }
}
