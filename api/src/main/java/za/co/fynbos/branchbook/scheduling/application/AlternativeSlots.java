package za.co.fynbos.branchbook.scheduling.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.catalogue.application.BranchCalendarProvider;
import za.co.fynbos.branchbook.catalogue.domain.BranchCalendar;
import za.co.fynbos.branchbook.catalogue.domain.ServiceOffer;
import za.co.fynbos.branchbook.scheduling.domain.SlotCalendar;
import za.co.fynbos.branchbook.shared.config.BookingProperties;
import za.co.fynbos.branchbook.shared.time.TimeFormat;

/**
 * Finds what to offer a customer whose slot filled up while they were booking: up to {@value
 * #MAX_ALTERNATIVES} later slots on the same day that still have a free seat (BR-7).
 *
 * <p>The booking transaction calls it after its own transaction has rolled back, so the search
 * holds no lock and sees every booking that has committed since.
 */
@Service
public class AlternativeSlots {

  /** The most alternatives offered. */
  public static final int MAX_ALTERNATIVES = 3;

  private final BranchCalendarProvider calendars;
  private final BookedIntervalsPort bookedIntervals;
  private final Clock clock;
  private final BookingProperties booking;

  AlternativeSlots(
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
   * Lists the free slots after a requested start, on the same day.
   *
   * @param branchCode the branch code, in upper case
   * @param serviceCode the service code, in upper case
   * @param startsAt the start that was full
   * @return up to {@value #MAX_ALTERNATIVES} slots, earliest first, each with the branch's offset;
   *     empty when none is left that day
   */
  @Transactional(readOnly = true)
  public List<Alternative> after(String branchCode, String serviceCode, Instant startsAt) {
    BranchCalendar calendar = calendars.calendarFor(branchCode);
    ServiceOffer service = calendars.offerFor(calendar.branch(), serviceCode);
    ZoneId zone = calendar.branch().timezone();
    SlotCalendar slots =
        new SlotCalendar(calendar, service, clock.instant(), booking.leadTime(), booking.horizon());
    return slots
        .freeSlotsAfter(
            startsAt,
            bookedIntervals.activeOn(
                calendar.branch().id(),
                service.serviceId(),
                LocalDate.ofInstant(startsAt, zone),
                zone),
            MAX_ALTERNATIVES)
        .stream()
        .map(
            slot ->
                new Alternative(
                    TimeFormat.isoOffset(slot.startsAt(), zone),
                    TimeFormat.isoOffset(slot.endsAt(), zone)))
        .toList();
  }

  /**
   * One alternative slot, as the {@code SLOT_FULL} problem lists it.
   *
   * @param startsAt when it starts, with the branch's offset
   * @param endsAt when it ends, with the branch's offset
   */
  public record Alternative(String startsAt, String endsAt) {}
}
