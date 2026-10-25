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
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.fynbos.branchbook.booking.application.BookAppointmentHandler;
import za.co.fynbos.branchbook.booking.application.BookingResult;
import za.co.fynbos.branchbook.booking.application.IdempotencyKeyRequiredException;
import za.co.fynbos.branchbook.booking.domain.IdempotencyKey;
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

  private final BookAppointmentHandler booking;

  AppointmentController(BookAppointmentHandler booking) {
    this.booking = booking;
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
              + " slot; MOBILE_NUMBER_REQUIRED: not a South African mobile; IDEMPOTENCY_KEY_REUSED:"
              + " the key booked different details",
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
    BookingResult result = booking.handle(request.toCommand(key));
    AppointmentResponse body = AppointmentResponse.from(result.appointment());
    ResponseEntity.BodyBuilder response =
        ResponseEntity.created(URI.create("/api/v1/appointments/" + body.reference()));
    if (result instanceof BookingResult.Replayed) {
      response.header(IDEMPOTENT_REPLAYED, "true");
    }
    return response.body(body);
  }
}
