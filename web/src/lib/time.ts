/**
 * Dates and times as the customer reads them: always in South African Standard Time, whatever zone the device is
 * in (BR-20), on a 24-hour clock, day first.
 *
 * Two kinds of value come from the API. A calendar date such as `2026-10-27` is already the branch's local date, so
 * it is handled as text and plain arithmetic on its parts, never as a `Date` in the device's zone. An instant such
 * as `2026-10-27T09:30:00+02:00` is turned into its SAST date and clock time with `Intl`, so a device set to New York
 * still shows 09:30. Day and month names come from fixed English lists rather than the browser's locale data, so
 * "Tue 27 Oct" reads the same in every browser.
 *
 * @packageDocumentation
 */

/** The zone every branch trades in, and the only one the app shows times in. */
export const SAST_TIME_ZONE = 'Africa/Johannesburg';

/** A calendar date in the form the API uses: `YYYY-MM-DD`. */
export type IsoDate = string;

const WEEKDAYS = [
  'Monday',
  'Tuesday',
  'Wednesday',
  'Thursday',
  'Friday',
  'Saturday',
  'Sunday',
] as const;

const MONTHS = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
] as const;

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

const SAST_PARTS = new Intl.DateTimeFormat('en-GB', {
  timeZone: SAST_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

interface CalendarDate {
  year: number;
  /** 1 to 12. */
  month: number;
  day: number;
}

/**
 * The SAST date and clock time of an instant.
 *
 * @param instant - an ISO-8601 date and time with an offset, or a `Date`
 */
function sastParts(instant: string | Date) {
  const parts = SAST_PARTS.formatToParts(typeof instant === 'string' ? new Date(instant) : instant);
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((p) => p.type === type)?.value ?? '';
  return {
    date: `${part('year')}-${part('month')}-${part('day')}`,
    time: `${part('hour')}:${part('minute')}`,
    hour: Number(part('hour')),
  };
}

/**
 * Reads a calendar date; an instant is read as its SAST date.
 *
 * @param value - `YYYY-MM-DD`, or an ISO-8601 instant
 */
function calendarDate(value: string): CalendarDate {
  const match = ISO_DATE.exec(value) ?? ISO_DATE.exec(sastParts(value).date);
  if (match === null) {
    throw new RangeError('Not a date');
  }
  return { year: Number(match[1]), month: Number(match[2]), day: Number(match[3]) };
}

/** Days since 1970-01-01 of a calendar date: arithmetic on the date alone, in no time zone. */
function epochDay({ year, month, day }: CalendarDate): number {
  return Math.round(Date.UTC(year, month - 1, day) / 86_400_000);
}

function fromEpochDay(days: number): IsoDate {
  return new Date(days * 86_400_000).toISOString().slice(0, 10);
}

// 1 for Monday to 7 for Sunday, as the API numbers `OperatingHours.weekday`.
function isoWeekday(date: CalendarDate): number {
  // 1970-01-01 was a Thursday, ISO weekday 4.
  return ((((epochDay(date) + 3) % 7) + 7) % 7) + 1;
}

function weekdayOf(date: CalendarDate): string {
  return WEEKDAYS[isoWeekday(date) - 1] ?? '';
}

function monthOf(date: CalendarDate): string {
  return MONTHS[date.month - 1] ?? '';
}

/**
 * The full date, for a summary.
 *
 * @example `formatDateLong('2026-10-27')` is "Tuesday 27 October 2026"
 * @param value - `YYYY-MM-DD`, or an instant, which is shown as its SAST date
 */
export function formatDateLong(value: string): string {
  const date = calendarDate(value);
  return `${weekdayOf(date)} ${String(date.day)} ${monthOf(date)} ${String(date.year)}`;
}

/**
 * The date without its year, for a heading under a week's range that already names the year.
 *
 * @example `formatDateNoYear('2026-10-27')` is "Tuesday 27 October"
 * @param value - `YYYY-MM-DD`, or an instant, which is shown as its SAST date
 */
export function formatDateNoYear(value: string): string {
  const date = calendarDate(value);
  return `${weekdayOf(date)} ${String(date.day)} ${monthOf(date)}`;
}

/**
 * The date where space is short: the action bar and the SMS.
 *
 * @example `formatDateShort('2026-10-27')` is "Tue 27 Oct"
 * @param value - `YYYY-MM-DD`, or an instant, which is shown as its SAST date
 */
export function formatDateShort(value: string): string {
  const date = calendarDate(value);
  return `${weekdayOf(date).slice(0, 3)} ${String(date.day)} ${monthOf(date).slice(0, 3)}`;
}

/**
 * A run of dates, as the date strip heads its week.
 *
 * @example `formatDateRange('2026-10-25', '2026-10-31')` is "25 to 31 October 2026", and a range across a month end
 *   is "29 October to 4 November 2026"
 * @param from - the first date, `YYYY-MM-DD`
 * @param to - the last date, `YYYY-MM-DD`
 */
export function formatDateRange(from: IsoDate, to: IsoDate): string {
  const start = calendarDate(from);
  const end = calendarDate(to);
  const endText = `${String(end.day)} ${monthOf(end)} ${String(end.year)}`;
  if (start.year !== end.year) {
    return `${String(start.day)} ${monthOf(start)} ${String(start.year)} to ${endText}`;
  }
  if (start.month !== end.month) {
    return `${String(start.day)} ${monthOf(start)} to ${endText}`;
  }
  return `${String(start.day)} to ${endText}`;
}

/**
 * The three-letter weekday, for a date tile.
 *
 * @example `weekdayShort('2026-10-27')` is "Tue"
 * @param value - `YYYY-MM-DD`, or an instant
 */
export function weekdayShort(value: string): string {
  return weekdayOf(calendarDate(value)).slice(0, 3);
}

/**
 * The day of the month without a leading zero, for a date tile.
 *
 * @example `dayOfMonth('2026-11-04')` is "4"
 * @param value - `YYYY-MM-DD`, or an instant
 */
export function dayOfMonth(value: string): string {
  return String(calendarDate(value).day);
}

/**
 * The weekday as the API numbers it in `OperatingHours.weekday`: 1 for Monday to 7 for Sunday.
 *
 * @param value - `YYYY-MM-DD`, or an instant
 */
export function weekdayNumber(value: string): number {
  return isoWeekday(calendarDate(value));
}

/**
 * The name of a weekday the API numbers 1 (Monday) to 7 (Sunday), for a branch's hours.
 *
 * @example `weekdayName(6)` is "Saturday"
 * @param weekday - 1 to 7
 */
export function weekdayName(weekday: number): string {
  return WEEKDAYS[weekday - 1] ?? '';
}

/**
 * The SAST clock time of an instant, on a 24-hour clock.
 *
 * @example `formatTime('2026-10-27T07:30:00Z')` is "09:30"
 * @param instant - an ISO-8601 date and time with an offset
 */
export function formatTime(instant: string): string {
  return sastParts(instant).time;
}

/**
 * An appointment's time, labelled with the zone.
 *
 * @example `formatTimeRange('2026-10-27T09:30:00+02:00', '2026-10-27T09:45:00+02:00')` is "09:30 to 09:45 SAST"
 * @param startsAt - when it starts
 * @param endsAt - when it ends
 */
export function formatTimeRange(startsAt: string, endsAt: string): string {
  return `${formatTime(startsAt)} to ${formatTime(endsAt)} SAST`;
}

/**
 * Tells whether an instant falls before noon SAST, to group times into Morning and Afternoon.
 *
 * @param instant - an ISO-8601 date and time with an offset
 */
export function isMorning(instant: string): boolean {
  return sastParts(instant).hour < 12;
}

/**
 * The SAST calendar date of an instant.
 *
 * @example `sastDate('2026-10-26T23:30:00Z')` is "2026-10-27"
 * @param instant - an ISO-8601 date and time with an offset
 */
export function sastDate(instant: string): IsoDate {
  return sastParts(instant).date;
}

/**
 * The instant of a SAST date and clock time, in the form the API takes for `startsAt`.
 *
 * South Africa has kept UTC+2 all year since 1944, so the offset is fixed.
 *
 * @example `sastInstant('2026-10-27', '09:30')` is "2026-10-27T09:30:00+02:00"
 * @param date - `YYYY-MM-DD`
 * @param time - `HH:MM`, as the booking URL's `slot` holds it
 */
export function sastInstant(date: IsoDate, time: string): string {
  return `${date}T${time}:00+02:00`;
}

/**
 * An instant some minutes after another, such as the end of a slot from its start and the service's length.
 *
 * @example `addMinutes('2026-10-27T09:30:00+02:00', 15)` is "2026-10-27T07:45:00.000Z" (09:45 SAST)
 * @param instant - an ISO-8601 date and time with an offset
 * @param minutes - how many minutes later
 */
export function addMinutes(instant: string, minutes: number): string {
  return new Date(Date.parse(instant) + minutes * 60_000).toISOString();
}

/**
 * Today's date in SAST, which is the date the branches and the API's booking window count from.
 *
 * @param now - the moment to read; defaults to the device's clock
 */
export function todayInSast(now: Date = new Date()): IsoDate {
  return sastParts(now).date;
}

/**
 * A date some days before or after another, on the calendar alone.
 *
 * @example `addDays('2026-10-30', 3)` is "2026-11-02"
 * @param date - `YYYY-MM-DD`
 * @param days - how many days to move; negative moves back
 */
export function addDays(date: IsoDate, days: number): IsoDate {
  return fromEpochDay(epochDay(calendarDate(date)) + days);
}

/**
 * Every date from one to another, both included, in order.
 *
 * @param from - the first date, `YYYY-MM-DD`
 * @param to - the last date, `YYYY-MM-DD`; before `from` gives an empty list
 */
export function datesBetween(from: IsoDate, to: IsoDate): IsoDate[] {
  const first = epochDay(calendarDate(from));
  const last = epochDay(calendarDate(to));
  return Array.from({ length: Math.max(0, last - first + 1) }, (_, i) => fromEpochDay(first + i));
}
