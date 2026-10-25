package za.co.fynbos.branchbook.scheduling.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.fynbos.branchbook.scheduling.application.AvailabilityQueries;
import za.co.fynbos.branchbook.shared.web.OpenApiConfig;

/**
 * The availability endpoint, which feeds the booking flow's slot grid: one day, one service, one
 * branch per call, so a response never exceeds 36 slots.
 */
@RestController
@Tag(name = "Availability", description = "The slots a service can be booked in at a branch.")
class AvailabilityController {

  private final AvailabilityQueries queries;

  AvailabilityController(AvailabilityQueries queries) {
    this.queries = queries;
  }

  /**
   * Lists one day's slots for one service at one branch.
   *
   * @param code the branch code; lower case is accepted
   * @param service the service code; lower case is accepted
   * @param date the date, as {@code yyyy-MM-dd}, in the branch's time zone
   * @return the day's slots with the seats still free, or why the branch is closed that day
   */
  @Operation(
      operationId = "getAvailability",
      summary = "List one day's slots for one service at one branch",
      description =
          "Slots start at opening time and step by the service's duration, end by closing time, and"
              + " start at least two hours from now. Full slots are listed with seatsAvailable 0. A"
              + " closed day returns no slots and a closedReason.")
  @ApiResponse(responseCode = "200", description = "The day's slots")
  @ApiResponse(
      responseCode = "400",
      description = "VALIDATION_FAILED: the code, the service or the date is missing or malformed",
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
      responseCode = "422",
      description =
          "SERVICE_NOT_OFFERED_AT_BRANCH: the branch does not offer the service;"
              + " DATE_OUTSIDE_BOOKING_WINDOW: the date is not one of the bookable days",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(ref = OpenApiConfig.PROBLEM)))
  @GetMapping(
      path = "/api/v1/branches/{code}/availability",
      produces = MediaType.APPLICATION_JSON_VALUE)
  AvailabilityResponse availability(
      @Parameter(description = "The branch code", example = "SAN")
          @PathVariable
          @Pattern(regexp = "^[A-Za-z]{2,8}$", message = "must be 2 to 8 letters")
          String code,
      @Parameter(description = "The service code", example = "FICA")
          @RequestParam
          @Pattern(regexp = "^[A-Za-z]{2,12}$", message = "must be 2 to 12 letters")
          String service,
      @Parameter(description = "The date, in the branch's time zone", example = "2026-10-27")
          @RequestParam
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    return AvailabilityResponse.from(
        queries.availability(
            code.toUpperCase(Locale.ROOT), service.toUpperCase(Locale.ROOT), date));
  }
}
