package za.co.fynbos.branchbook.catalogue.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.catalogue.domain.BookingWindow;
import za.co.fynbos.branchbook.catalogue.domain.Branch;
import za.co.fynbos.branchbook.catalogue.domain.BranchCalendar;
import za.co.fynbos.branchbook.catalogue.domain.ClosedDay;
import za.co.fynbos.branchbook.catalogue.domain.OperatingHours;
import za.co.fynbos.branchbook.catalogue.domain.ServiceType;
import za.co.fynbos.branchbook.shared.config.BookingProperties;

/**
 * Answers the questions the booking flow's first steps ask: which branches exist, and, for one
 * branch, what it offers and on which days it can be booked.
 */
@Service
public class BranchQueries {

  private final CatalogueReadPort catalogue;
  private final BranchCalendarProvider calendars;
  private final Clock clock;
  private final BookingProperties booking;

  BranchQueries(
      CatalogueReadPort catalogue,
      BranchCalendarProvider calendars,
      Clock clock,
      BookingProperties booking) {
    this.catalogue = catalogue;
    this.calendars = calendars;
    this.clock = clock;
    this.booking = booking;
  }

  /**
   * Lists the active branches for the branch picker.
   *
   * @return the branches, ordered by name
   */
  @Transactional(readOnly = true)
  public List<BranchSummary> activeBranches() {
    Map<UUID, List<OperatingHours>> hours = catalogue.operatingHoursOfActiveBranches();
    return catalogue.activeBranches().stream()
        .map(
            branch ->
                new BranchSummary(
                    branch, opensOnSunday(hours.getOrDefault(branch.id(), List.of()))))
        .toList();
  }

  /**
   * Describes one branch: its address, hours and services, the dates that can be booked today, and
   * the closed days among them. The window starts today in the branch's time zone, by the injected
   * clock, so the answer never depends on the caller's device (BR-5).
   *
   * @param code the branch code, in upper case
   * @return the branch's details
   * @throws BranchNotFoundException if no active branch has that code
   */
  @Transactional(readOnly = true)
  public BranchDetail branchDetail(String code) {
    Branch branch = catalogue.activeBranch(code).orElseThrow(BranchNotFoundException::new);
    BranchCalendar calendar = calendars.calendarFor(branch);
    LocalDate today = LocalDate.now(clock.withZone(branch.timezone()));
    BookingWindow window = BookingWindow.startingOn(today, booking.horizon());
    return new BranchDetail(
        branch,
        calendar.weeklyHours(),
        catalogue.servicesOfferedAt(branch.id()),
        window,
        calendar.closuresIn(window));
  }

  private static boolean opensOnSunday(List<OperatingHours> hours) {
    return hours.stream().anyMatch(day -> day.weekday() == DayOfWeek.SUNDAY);
  }

  /**
   * A branch in the branch picker.
   *
   * @param branch the branch
   * @param opensSunday whether it trades on Sundays, which few branches do
   */
  public record BranchSummary(Branch branch, boolean opensSunday) {}

  /**
   * Everything the booking flow needs to know about one branch before it asks for a time slot.
   *
   * @param branch the branch
   * @param weeklyHours its trading hours, Monday first
   * @param services the services it offers, in display order
   * @param window the dates that can be booked, starting today
   * @param closedDays the days in the window on which the branch takes no appointments
   */
  public record BranchDetail(
      Branch branch,
      List<OperatingHours> weeklyHours,
      List<ServiceType> services,
      BookingWindow window,
      List<ClosedDay> closedDays) {}
}
