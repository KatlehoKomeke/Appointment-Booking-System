package za.co.fynbos.branchbook.catalogue.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.catalogue.domain.Branch;
import za.co.fynbos.branchbook.catalogue.domain.BranchCalendar;
import za.co.fynbos.branchbook.catalogue.domain.PublicHolidayCalendar;
import za.co.fynbos.branchbook.catalogue.domain.ServiceOffer;

/**
 * What scheduling and booking need to know about a branch: its {@link BranchCalendar} (weekly hours
 * together with the public holidays), and how it offers each service ({@link ServiceOffer}).
 */
@Service
public class BranchCalendarProvider {

  private final CatalogueReadPort catalogue;

  BranchCalendarProvider(CatalogueReadPort catalogue) {
    this.catalogue = catalogue;
  }

  /**
   * Returns the calendar of an active branch.
   *
   * @param code the branch code, in upper case
   * @return the branch's calendar
   * @throws BranchNotFoundException if no active branch has that code
   */
  @Transactional(readOnly = true)
  public BranchCalendar calendarFor(String code) {
    return calendarFor(catalogue.activeBranch(code).orElseThrow(BranchNotFoundException::new));
  }

  /**
   * Returns the calendar of a branch already read.
   *
   * @param branch the branch
   * @return the branch's calendar
   */
  @Transactional(readOnly = true)
  public BranchCalendar calendarFor(Branch branch) {
    PublicHolidayCalendar holidays = new PublicHolidayCalendar(catalogue.publicHolidays());
    return new BranchCalendar(branch, catalogue.operatingHours(branch.id()), holidays);
  }

  /**
   * Returns how a branch offers a service: the appointment's duration and the number of seats.
   *
   * @param branch the branch
   * @param serviceCode the service code, in upper case
   * @return the offer
   * @throws ServiceNotOfferedAtBranchException if the branch does not offer it, or no active
   *     service has that code (BR-6)
   */
  @Transactional(readOnly = true)
  public ServiceOffer offerFor(Branch branch, String serviceCode) {
    return catalogue
        .serviceOffer(branch.id(), serviceCode)
        .orElseThrow(ServiceNotOfferedAtBranchException::new);
  }
}
