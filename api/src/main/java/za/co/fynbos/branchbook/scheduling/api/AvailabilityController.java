package za.co.fynbos.branchbook.scheduling.api;

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

/**
 * The availability endpoint, which feeds the booking flow's slot grid: one day, one service, one
 * branch per call, so a response never exceeds 36 slots.
 */
@RestController
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
  @GetMapping(
      path = "/api/v1/branches/{code}/availability",
      produces = MediaType.APPLICATION_JSON_VALUE)
  AvailabilityResponse availability(
      @PathVariable @Pattern(regexp = "^[A-Za-z]{2,8}$", message = "must be 2 to 8 letters")
          String code,
      @RequestParam @Pattern(regexp = "^[A-Za-z]{2,12}$", message = "must be 2 to 12 letters")
          String service,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return AvailabilityResponse.from(
        queries.availability(
            code.toUpperCase(Locale.ROOT), service.toUpperCase(Locale.ROOT), date));
  }
}
