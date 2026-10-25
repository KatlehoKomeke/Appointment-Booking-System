package za.co.fynbos.branchbook.catalogue.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import za.co.fynbos.branchbook.catalogue.domain.Branch;
import za.co.fynbos.branchbook.catalogue.domain.OperatingHours;
import za.co.fynbos.branchbook.catalogue.domain.PublicHoliday;
import za.co.fynbos.branchbook.catalogue.domain.ServiceOffer;
import za.co.fynbos.branchbook.catalogue.domain.ServiceType;

/**
 * Reads the catalogue's reference data. The infrastructure layer implements it with SQL, so the
 * application layer never depends on how the data is stored.
 *
 * <p>The reads that list or look up by code see only active branches and services. {@link
 * #branchById} and {@link #serviceById} find a row whether or not it is active, so an appointment
 * booked before a branch closed or a service was withdrawn can still be shown.
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
   * Finds how a branch offers an active service.
   *
   * @param branchId the branch's id
   * @param serviceCode the service code, in upper case
   * @return the offer, or empty if the branch does not offer it or no active service has that code
   */
  Optional<ServiceOffer> serviceOffer(UUID branchId, String serviceCode);

  /**
   * Lists every stored public holiday. Observed Mondays are derived, not stored.
   *
   * @return the holidays, in date order
   */
  List<PublicHoliday> publicHolidays();

  /**
   * Finds a branch by id, whether or not it is still active, so an appointment booked before a
   * branch closed can still be shown.
   *
   * @param id the branch's id
   * @return the branch, or empty if no branch has that id
   */
  Optional<Branch> branchById(UUID id);

  /**
   * Finds a service by id, whether or not it is still offered, so an appointment booked before a
   * service was withdrawn can still be shown.
   *
   * @param id the service's id
   * @return the service, or empty if no service has that id
   */
  Optional<ServiceType> serviceById(UUID id);
}
