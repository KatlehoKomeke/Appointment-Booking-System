package za.co.fynbos.branchbook.catalogue.api;

import jakarta.validation.constraints.Pattern;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.fynbos.branchbook.catalogue.application.BranchQueries;

/**
 * The branch endpoints, which feed the first steps of the booking flow: choosing a branch, then a
 * service and a date.
 *
 * <p>Both are read-only and need no authentication. Errors follow docs/errors.md: an unknown or
 * inactive branch is {@code 404 BRANCH_NOT_FOUND}, and a code that is not two to eight letters is
 * {@code 400 VALIDATION_FAILED}.
 */
@RestController
@RequestMapping("/api/v1/branches")
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
  @GetMapping("/{code}")
  BranchDetailResponse detail(
      @PathVariable @Pattern(regexp = "[A-Za-z]{2,8}", message = "must be 2 to 8 letters")
          String code) {
    return BranchDetailResponse.from(queries.branchDetail(code.toUpperCase(Locale.ROOT)));
  }
}
