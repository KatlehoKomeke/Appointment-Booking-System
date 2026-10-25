package za.co.fynbos.branchbook.scheduling.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.catalogue.application.BranchCalendarProvider;
import za.co.fynbos.branchbook.catalogue.domain.Branch;
import za.co.fynbos.branchbook.catalogue.domain.BranchCalendar;
import za.co.fynbos.branchbook.catalogue.domain.ClosedDay;
import za.co.fynbos.branchbook.catalogue.domain.ServiceOffer;
import za.co.fynbos.branchbook.scheduling.domain.Slot;
import za.co.fynbos.branchbook.scheduling.domain.SlotCalendar;
import za.co.fynbos.branchbook.shared.config.BookingProperties;

/**
 * Answers the slot grid's question: which times can this service be booked at this branch on this
 * date, and how many seats are left in each?
 */
@Service
public class AvailabilityQueries {

  private final BranchCalendarProvider calendars;
  private final BookedIntervalsPort bookedIntervals;
  private final Clock clock;
  private final BookingProperties booking;

  AvailabilityQueries(
      BranchCalendarProvider calendars,
      BookedIntervalsPort bookedIntervals,
      Clock clock,
      BookingProperties booking) {
    this.calendars = calendars;
    this.bookedIntervals = bookedIntervals;
    this.clock = clock;
    this.booking = booking;
  }

  /**
   * Lists one day's slots for one service at one branch.
   *
   * <p>The checks run in a fixed order, so a request breaking several gets one answer: the branch
   * must exist, then offer the service, then the date must be bookable.
   *
   * @param branchCode the branch code, in upper case
   * @param serviceCode the service code, in upper case
   * @param date the date, in the branch's time zone
   * @return the day's slots, or why the branch is closed that day
   * @throws za.co.fynbos.branchbook.catalogue.application.BranchNotFoundException if no active
   *     branch has that code
   * @throws za.co.fynbos.branchbook.catalogue.application.ServiceNotOfferedAtBranchException if the
   *     branch does not offer the service (BR-6)
   * @throws DateOutsideBookingWindowException if the date is outside the bookable window (BR-5)
   */
  @Transactional(readOnly = true)
  public DayAvailability availability(String branchCode, String serviceCode, LocalDate date) {
    BranchCalendar calendar = calendars.calendarFor(branchCode);
    Branch branch = calendar.branch();
    ServiceOffer service = calendars.offerFor(branch, serviceCode);
    Instant now = clock.instant();
    SlotCalendar slots =
        new SlotCalendar(calendar, service, now, booking.leadTime(), booking.horizon());
    if (!slots.window().contains(date)) {
      throw new DateOutsideBookingWindowException();
    }
    Optional<ClosedDay> closure = calendar.closure(date);
    List<Slot> daySlots =
        closure.isPresent()
            ? List.<Slot>of()
            : slots.slotsOn(
                date,
                bookedIntervals.activeOn(
                    branch.id(), service.serviceId(), date, branch.timezone()));
    return new DayAvailability(
        branch, service, date, now.truncatedTo(ChronoUnit.SECONDS), closure.orElse(null), daySlots);
  }

  /**
   * One day of availability for one service at one branch.
   *
   * @param branch the branch
   * @param service how the branch offers the service
   * @param date the date, in the branch's time zone
   * @param generatedAt when the answer was computed, so the page can show how fresh it is
   * @param closure why the branch is closed that day, or {@code null} if it trades
   * @param slots the slots in time order; empty on a closed day or when every slot is past the lead
   *     time
   */
  public record DayAvailability(
      Branch branch,
      ServiceOffer service,
      LocalDate date,
      Instant generatedAt,
      @Nullable ClosedDay closure,
      List<Slot> slots) {}
}
