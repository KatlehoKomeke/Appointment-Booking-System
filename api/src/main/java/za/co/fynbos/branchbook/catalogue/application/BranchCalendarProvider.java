package za.co.fynbos.branchbook.catalogue.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.catalogue.domain.Branch;
import za.co.fynbos.branchbook.catalogue.domain.BranchCalendar;
import za.co.fynbos.branchbook.catalogue.domain.PublicHolidayCalendar;

/**
 * Builds a branch's {@link BranchCalendar}: its weekly hours together with the public holidays.
 * This is how scheduling and booking learn on which days, and between which hours, a branch takes
 * appointments.
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
}
