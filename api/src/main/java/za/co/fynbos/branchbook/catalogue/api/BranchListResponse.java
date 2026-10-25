package za.co.fynbos.branchbook.catalogue.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import za.co.fynbos.branchbook.catalogue.application.BranchQueries.BranchSummary;

/**
 * The body of {@code GET /api/v1/branches}.
 *
 * @param branches the active branches, ordered by name
 */
@Schema(name = "BranchList")
public record BranchListResponse(List<Branch> branches) {

  static BranchListResponse from(List<BranchSummary> summaries) {
    return new BranchListResponse(summaries.stream().map(Branch::from).toList());
  }

  /**
   * One branch in the list.
   *
   * @param code the branch code, for example {@code SAN}
   * @param name the branch's name
   * @param suburb the suburb
   * @param city the city or town
   * @param province the province
   * @param opensSunday whether the branch trades on Sundays
   */
  @Schema(name = "BranchSummary")
  public record Branch(
      String code, String name, String suburb, String city, String province, boolean opensSunday) {

    static Branch from(BranchSummary summary) {
      var branch = summary.branch();
      var address = branch.address();
      return new Branch(
          branch.code(),
          branch.name(),
          address.suburb(),
          address.city(),
          address.province().displayName(),
          summary.opensSunday());
    }
  }
}
