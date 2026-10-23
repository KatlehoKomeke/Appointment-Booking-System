package za.co.fynbos.branchbook.catalogue.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import za.co.fynbos.branchbook.catalogue.domain.Branch;
import za.co.fynbos.branchbook.catalogue.domain.OperatingHours;
import za.co.fynbos.branchbook.catalogue.domain.PublicHoliday;
import za.co.fynbos.branchbook.catalogue.domain.ServiceType;

/**
 * Reads the catalogue's reference data. The infrastructure layer implements it with SQL, so the
 * application layer never depends on how the data is stored.
 *
 * <p>Inactive branches and services are invisible through this port.
 */
public interface CatalogueReadPort {

  /**
   * Lists the active branches.
   *
   * @return the branches, ordered by name
   */
  List<Branch> activeBranches();

  /**
   * Finds an active branch by its code.
   *
   * @param code the branch code, in upper case
   * @return the branch, or empty if no active branch has that code
   */
  Optional<Branch> activeBranch(String code);

  /**
   * Returns one branch's weekly trading hours.
   *
   * @param branchId the branch's id
   * @return one entry per day of the week the branch trades, Monday first
   */
  List<OperatingHours> operatingHours(UUID branchId);

  /**
   * Returns the weekly trading hours of every active branch, in one read.
   *
   * @return each branch's hours by branch id; a branch without hours has no entry
   */
  Map<UUID, List<OperatingHours>> operatingHoursOfActiveBranches();

  /**
   * Lists the active services a branch offers.
   *
   * @param branchId the branch's id
   * @return the services, in display order
   */
  List<ServiceType> servicesOfferedAt(UUID branchId);

  /**
   * Lists every stored public holiday. Observed Mondays are derived, not stored.
   *
   * @return the holidays, in date order
   */
  List<PublicHoliday> publicHolidays();
}
