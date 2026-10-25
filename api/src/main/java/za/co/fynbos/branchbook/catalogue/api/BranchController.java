package za.co.fynbos.branchbook.catalogue.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.fynbos.branchbook.catalogue.application.BranchQueries;
import za.co.fynbos.branchbook.shared.web.OpenApiConfig;

/**
 * The branch endpoints, which feed the first steps of the booking flow: choosing a branch, then a
 * service and a date.
 *
 * <p>Both are read-only and need no authentication. Errors follow docs/errors.md: an unknown or
 * inactive branch is {@code 404 BRANCH_NOT_FOUND}, and a code that is not two to eight letters is
 * {@code 400 VALIDATION_FAILED}.
 */
@RestController
@RequestMapping(path = "/api/v1/branches", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Branches", description = "The branches customers can book at, and what each offers.")
class BranchController {

  private final BranchQueries queries;

  BranchController(BranchQueries queries) {
    this.queries = queries;
  }

  /**
   * Lists the active branches, ordered by name.
   *
   * @return the branches
   */
  @Operation(
      operationId = "listBranches",
      summary = "List the active branches",
      description = "Ordered by name.")
  @ApiResponse(responseCode = "200", description = "The active branches")
  @GetMapping
  BranchListResponse list() {
    return BranchListResponse.from(queries.activeBranches());
  }

  /**
   * Describes one branch, including the bookable dates and the closed days among them.
   *
   * @param code the branch code; lower case is accepted
   * @return the branch's details
   */
  @Operation(
      operationId = "getBranch",
      summary = "Describe one branch",
      description =
          "Its address, weekly hours and services, the dates that can be booked (from today in the"
              + " branch's time zone, for 30 days), and the closed days among them.")
  @ApiResponse(responseCode = "200", description = "The branch")
  @ApiResponse(
      responseCode = "400",
      description = "VALIDATION_FAILED: the code is not 2 to 8 letters",
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
  @GetMapping("/{code}")
  BranchDetailResponse detail(
      @Parameter(description = "The branch code", example = "SAN")
          @PathVariable
          @Pattern(regexp = "^[A-Za-z]{2,8}$", message = "must be 2 to 8 letters")
          String code) {
    return BranchDetailResponse.from(queries.branchDetail(code.toUpperCase(Locale.ROOT)));
  }
}
