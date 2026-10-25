package za.co.fynbos.branchbook.scheduling.domain;

/**
 * Why a requested start time cannot be booked. The names are the API's error codes
 * (docs/errors.md), all returned as 422.
 *
 * <p>The constants are declared in precedence order: when several apply, {@link
 * SlotCalendar#validate} reports the first (BR-21). The window and the day decide whether there is
 * a slot grid at all; the lead time is last because it is the only one that changes while a page
 * sits open.
 */
public enum SlotProblem {
  /** The date is outside the bookable window (BR-5). */
  DATE_OUTSIDE_BOOKING_WINDOW,
  /** The branch is closed that day: a day of the week without hours, or a public holiday (BR-3). */
  SLOT_ON_CLOSED_DAY,
  /** The slot would start before opening or end after closing (BR-2). */
  SLOT_OUTSIDE_HOURS,
  /**
   * The start is not on the service's grid: opening time plus a whole number of durations (BR-1).
   */
  SLOT_OFF_GRID,
  /** The slot starts sooner than the lead time allows (BR-4). */
  SLOT_WITHIN_LEAD_TIME
}
