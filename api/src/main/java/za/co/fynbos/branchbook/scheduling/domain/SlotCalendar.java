package za.co.fynbos.branchbook.scheduling.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import za.co.fynbos.branchbook.catalogue.domain.BookingWindow;
import za.co.fynbos.branchbook.catalogue.domain.BranchCalendar;
import za.co.fynbos.branchbook.catalogue.domain.OperatingHours;
import za.co.fynbos.branchbook.catalogue.domain.ServiceOffer;

/**
 * The slots one service can be booked in at one branch, and the check that a requested start is one
 * of them. Availability lists the slots; the booking transaction will run the same check, so the
 * two can never disagree.
 *
 * <p>The rules, each with its identifier in docs/domain-rules.md:
 *
 * <ul>
 *   <li>BR-1: slots start at opening time and step by the service's duration.
 *   <li>BR-2: a slot must start at or after opening and end at or before closing.
 *   <li>BR-3: a closed day of the week or a public holiday has no slots.
 *   <li>BR-4: a slot must start at least the lead time after now.
 *   <li>BR-5: the date must lie in the bookable window, which starts today in the branch's zone.
 *   <li>BR-21: when a start breaks several rules, {@link #validate} reports one, by the order of
 *       {@link SlotProblem}.
 * </ul>
 *
 * <p>The class is pure: the branch's hours, the service, "now" and the settings are all passed in,
 * so it is tested at fixed moments. Trading hours are wall-clock times, converted to instants only
 * through the branch's time zone, so the result is the same whatever the JVM's default zone.
 */
public final class SlotCalendar {

  private final BranchCalendar branch;
  private final ServiceOffer service;
  private final ZoneId zone;
  private final Instant earliestStart;
  private final BookingWindow window;

  /**
   * Creates the calendar of one service at one branch, as it stands at a moment.
   *
   * @param branch the branch's trading days and hours
   * @param service how the branch offers the service: its duration and capacity
   * @param now the current instant, from the application's clock
   * @param leadTime how long after now the earliest slot may start
   * @param horizon how many days are bookable, counting today
   */
  public SlotCalendar(
      BranchCalendar branch, ServiceOffer service, Instant now, Duration leadTime, Period horizon) {
    this.branch = Objects.requireNonNull(branch, "branch");
    this.service = Objects.requireNonNull(service, "service");
    this.zone = branch.branch().timezone();
    this.earliestStart = now.plus(leadTime);
    this.window = BookingWindow.startingOn(LocalDate.ofInstant(now, zone), horizon);
  }

  /**
   * Returns the dates that can be booked.
   *
   * @return the window, starting today in the branch's time zone
   */
  public BookingWindow window() {
    return window;
  }

  /**
   * Lists the slots of a date, each with the seats still free.
   *
   * @param date the date, in the branch's time zone
   * @param booked the intervals that active appointments of this service at this branch hold;
   *     intervals on other days are ignored
   * @return the slots in time order; empty if the date is outside the window or the branch is
   *     closed. Slots starting within the lead time are left out, and full slots are kept with 0
   *     seats available.
   */
  public List<Slot> slotsOn(LocalDate date, Collection<BookedInterval> booked) {
    List<Slot> slots = new ArrayList<>();
    Optional<OperatingHours> hours =
        window.contains(date) ? branch.hoursOn(date) : Optional.empty();
    if (hours.isEmpty()) {
      return slots;
    }
    LocalDateTime closes = date.atTime(hours.get().closesAt());
    Duration duration = service.duration();
    for (LocalDateTime start = date.atTime(hours.get().opensAt());
        !start.plus(duration).isAfter(closes);
        start = start.plus(duration)) {
      Instant startsAt = start.atZone(zone).toInstant();
      if (!startsAt.isBefore(earliestStart)) {
        Instant endsAt = startsAt.plus(duration);
        slots.add(new Slot(startsAt, endsAt, seatsFree(startsAt, endsAt, booked)));
      }
    }
    return slots;
  }

  /**
   * Lists the slots after a start, on the same date, that still have a free seat: the alternatives
   * offered when the requested slot has just filled up (BR-7). Slots within the lead time are left
   * out, as in {@link #slotsOn}.
   *
   * @param startsAt the requested start; only later slots of its date in the branch's zone count
   * @param booked the intervals that active appointments of this service at this branch hold
   * @param limit the most slots to return
   * @return the free slots in time order, at most {@code limit}; empty when none is left that day
   */
  public List<Slot> freeSlotsAfter(Instant startsAt, Collection<BookedInterval> booked, int limit) {
    return slotsOn(LocalDate.ofInstant(startsAt, zone), booked).stream()
        .filter(slot -> slot.startsAt().isAfter(startsAt) && slot.seatsAvailable() > 0)
        .limit(limit)
        .toList();
  }

  /**
   * Checks that a requested start is one of the slots, ignoring whether a seat is free.
   *
   * @param startsAt the requested start
   * @return empty if it is a slot; otherwise the one problem that applies first (BR-21)
   */
  public Optional<SlotProblem> validate(Instant startsAt) {
    LocalDateTime local = LocalDateTime.ofInstant(startsAt, zone);
    LocalDate date = local.toLocalDate();
    if (!window.contains(date)) {
      return Optional.of(SlotProblem.DATE_OUTSIDE_BOOKING_WINDOW);
    }
    Optional<OperatingHours> hours = branch.hoursOn(date);
    if (hours.isEmpty()) {
      return Optional.of(SlotProblem.SLOT_ON_CLOSED_DAY);
    }
    LocalDateTime opens = date.atTime(hours.get().opensAt());
    LocalDateTime closes = date.atTime(hours.get().closesAt());
    Duration duration = service.duration();
    if (local.isBefore(opens) || local.plus(duration).isAfter(closes)) {
      return Optional.of(SlotProblem.SLOT_OUTSIDE_HOURS);
    }
    if (Duration.between(opens, local).toNanos() % duration.toNanos() != 0) {
      return Optional.of(SlotProblem.SLOT_OFF_GRID);
    }
    if (startsAt.isBefore(earliestStart)) {
      return Optional.of(SlotProblem.SLOT_WITHIN_LEAD_TIME);
    }
    return Optional.empty();
  }

  /** The seats with no active appointment overlapping the period, never fewer than none. */
  private int seatsFree(Instant start, Instant end, Collection<BookedInterval> booked) {
    long taken =
        booked.stream()
            .filter(interval -> interval.overlaps(start, end))
            .mapToInt(BookedInterval::seatNo)
            .distinct()
            .count();
    return (int) Math.max(0, service.capacity() - taken);
  }
}
