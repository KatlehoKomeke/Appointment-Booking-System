package za.co.fynbos.branchbook.booking.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.fynbos.branchbook.booking.application.BookAppointmentHandler;
import za.co.fynbos.branchbook.booking.application.BookingResult;
import za.co.fynbos.branchbook.booking.application.IdempotencyKeyRequiredException;
import za.co.fynbos.branchbook.booking.application.LookupAppointmentHandler;
import za.co.fynbos.branchbook.booking.domain.IdempotencyKey;
import za.co.fynbos.branchbook.shared.config.BookingProperties;
import za.co.fynbos.branchbook.shared.web.OpenApiConfig;

/** The appointment endpoints: booking one, and opening it again with its reference. */
@RestController
@RequestMapping(path = "/api/v1/appointments", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Appointments", description = "Book an appointment, and open it again.")
class AppointmentController {

  /** The response header that marks a replay of an earlier booking (BR-12). */
  static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

  private static final String UUID_PATTERN =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  private static final Logger log = LoggerFactory.getLogger(AppointmentController.class);

  private final BookAppointmentHandler booking;
  private final LookupAppointmentHandler lookups;

  /** Whether this deployment takes only the published demo numbers (ADR-0006). */
  private final boolean demoNumbersOnly;

  AppointmentController(
      BookAppointmentHandler booking,
      LookupAppointmentHandler lookups,
      BookingProperties bookingProperties) {
    this.booking = booking;
    this.lookups = lookups;
    this.demoNumbersOnly = bookingProperties.demoNumbersOnly();
  }

  /**
   * Says, once the application is ready, which numbers bookings take, so a deployment's start-up
   * log shows whether {@code branchbook.booking.demo-numbers-only} is on. A hosted demo whose log
   * says any mobile was started without {@code BRANCHBOOK_BOOKING_DEMO_NUMBERS_ONLY}, and would
   * store a real person's number (ADR-0006). The line names the setting, never a number.
   */
  @EventListener(ApplicationReadyEvent.class)
  void reportWhichNumbersBookingsTake() {
    if (demoNumbersOnly) {
      log.info(
          "Bookings take only the published demo numbers (branchbook.booking.demo-numbers-only is"
              + " on).");
    } else {
      log.info(
          "Bookings take any South African mobile number (branchbook.booking.demo-numbers-only is"
              + " off).");
    }
  }

  /**
   * Books an appointment, or returns the one this {@code Idempotency-Key} already booked.
   *
   * @param idempotencyKey the client's key, a UUID sent again unchanged on a retry; checked here
   *     rather than by Spring, so a malformed body is reported first
   * @param request the booking
   * @return 201 with the appointment and its {@code Location}; a replay adds {@code
   *     Idempotent-Replayed: true}
   */
  @Operation(
      operationId = "bookAppointment",
      summary = "Book an appointment",
      description =
          "Books the slot, or answers why not. The confirmation SMS is written in the same"
              + " transaction and shows as PENDING in notifications. Retrying with the same"
              + " Idempotency-Key and the same details returns the same appointment.")
  @ApiResponse(
      responseCode = "201",
      description = "Booked, or replayed",
      headers = {
        @Header(
            name = "Location",
            description = "Where the appointment can be opened again",
            schema = @Schema(type = "string")),
        @Header(
            name = IDEMPOTENT_REPLAYED,
            description = "true when an earlier request with this key booked it; absent otherwise",
            schema = @Schema(type = "string", allowableValues = "true"))
      })
  @ApiResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED: a field is missing or malformed, or not a field of this request;"
              + " IDEMPOTENCY_KEY_REQUIRED: the header is missing or not a UUID",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(ref = OpenApiConfig.PROBLEM)))
  @ApiResponse(
      responseCode = "404",
      description = "BRANCH_NOT_FOUND: no active branch has that code",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(ref = OpenApiConfig.PROBLEM)))
  @ApiResponse(
      responseCode = "409",
      description =
          "DUPLICATE_APPOINTMENT: an upcoming appointment for this service exists; CUSTOMER_OVERLAP:"
              + " another appointment at that time exists; SLOT_FULL: every seat was just taken,"
              + " with up to three alternatives",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(ref = OpenApiConfig.PROBLEM)))
  @ApiResponse(
      responseCode = "422",
      description =
          "SERVICE_NOT_OFFERED_AT_BRANCH; DATE_OUTSIDE_BOOKING_WINDOW, SLOT_ON_CLOSED_DAY,"
              + " SLOT_OUTSIDE_HOURS, SLOT_OFF_GRID, SLOT_WITHIN_LEAD_TIME: the start is not a"
              + " slot; MOBILE_NUMBER_REQUIRED: not a South African mobile; DEMO_NUMBER_REQUIRED:"
              + " a demo that takes only its published demo numbers was sent another;"
              + " IDEMPOTENCY_KEY_REUSED: the key booked different details",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(ref = OpenApiConfig.PROBLEM)))
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<AppointmentResponse> book(
      @Parameter(
              in = ParameterIn.HEADER,
              name = "Idempotency-Key",
              required = true,
              description = "A UUID that identifies this booking; send it again on a retry",
              example = "8c4f1a2e-1b3d-4e5f-9a6b-7c8d9e0f1a2b",
              schema = @Schema(type = "string", format = "uuid", pattern = UUID_PATTERN))
          @RequestHeader(name = "Idempotency-Key", required = false)
          @Nullable String idempotencyKey,
      @Valid @RequestBody BookAppointmentRequest request) {
    IdempotencyKey key =
        IdempotencyKey.parse(idempotencyKey).orElseThrow(IdempotencyKeyRequiredException::new);
    BookingResult result = booking.handle(request.toCommand(key, demoNumbersOnly));
    AppointmentResponse body = AppointmentResponse.from(result.appointment());
    ResponseEntity.BodyBuilder response =
        ResponseEntity.created(URI.create("/api/v1/appointments/" + body.reference()));
    if (result instanceof BookingResult.Replayed) {
      response.header(IDEMPOTENT_REPLAYED, "true");
    }
    return response.body(body);
  }

  /**
   * Opens an appointment with its reference and the last four digits of the mobile number.
   *
   * @param reference the reference; case and hyphens do not matter
   * @param mobileLast4 the last four digits of the mobile number used to book
   * @return the appointment
   */
  @Operation(
      operationId = "getAppointment",
      summary = "Open an appointment",
      description =
          "Needs the reference and the last four digits of the mobile number used to book. Any"
              + " mismatch is 404, never 403, so the answer never confirms that a reference"
              + " exists. Five wrong tries in an hour block the reference, even for the right"
              + " digits, until an hour after the first. Each lookup counts as a try while it is"
              + " being checked, so lookups of one reference that arrive together can also be"
              + " refused briefly.")
  @ApiResponse(responseCode = "200", description = "The appointment")
  @ApiResponse(
      responseCode = "400",
      description = "VALIDATION_FAILED: X-Mobile-Last4 is missing or not four digits",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(ref = OpenApiConfig.PROBLEM)))
  @ApiResponse(
      responseCode = "404",
      description = "APPOINTMENT_NOT_FOUND: the reference and the digits match no appointment",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(ref = OpenApiConfig.PROBLEM)))
  @ApiResponse(
      responseCode = "429",
      description =
          "LOOKUP_RATE_LIMITED: five wrong tries for this reference, or too many of its lookups"
              + " at once",
      headers =
          @Header(
              name = "Retry-After",
              description =
                  "Seconds until the reference can be looked up again: what is left of the hour"
                      + " after five wrong tries, or 1 when other lookups of it are being checked",
              schema = @Schema(type = "integer")),
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(ref = OpenApiConfig.PROBLEM)))
  @GetMapping("/{reference}")
  AppointmentResponse lookup(
      @Parameter(
              description = "The reference; case and hyphens do not matter",
              example = "APT-7K3M-9Q2X")
          @PathVariable
          String reference,
      @Parameter(
              description = "The last four digits of the mobile number used to book",
              example = "0101")
          @RequestHeader("X-Mobile-Last4")
          @Pattern(regexp = "^[0-9]{4}$", message = "must be 4 digits")
          String mobileLast4) {
    return AppointmentResponse.from(lookups.lookup(reference, mobileLast4));
  }
}
