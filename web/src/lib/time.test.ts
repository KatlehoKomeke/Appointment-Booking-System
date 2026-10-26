import { describe, expect, it } from 'vitest';

import {
  addDays,
  datesBetween,
  dayOfMonth,
  formatDateLong,
  formatDateNoYear,
  formatDateRange,
  formatDateShort,
  formatTime,
  formatTimeRange,
  isMorning,
  sastDate,
  sastInstant,
  todayInSast,
  weekdayName,
  weekdayNumber,
  weekdayShort,
} from './time';

// Run under TZ=UTC and TZ=America/New_York: every answer must be the SAST one whatever the device's zone.
describe('dates and times in SAST', () => {
  it('writes a date day first in its three lengths', () => {
    expect(formatDateLong('2026-10-27')).toBe('Tuesday 27 October 2026');
    expect(formatDateNoYear('2026-10-27')).toBe('Tuesday 27 October');
    expect(formatDateShort('2026-10-27')).toBe('Tue 27 Oct');
    expect(formatDateShort('2026-09-01')).toBe('Tue 1 Sep');
    expect(weekdayShort('2026-11-04')).toBe('Wed');
    expect(dayOfMonth('2026-11-04')).toBe('4');
  });

  it('shows an instant on its SAST date and clock, wherever the device is', () => {
    // 23:30 UTC on the 26th is 01:30 on the 27th in SAST, and 19:30 on the 26th in New York.
    expect(sastDate('2026-10-26T23:30:00Z')).toBe('2026-10-27');
    expect(formatDateLong('2026-10-26T23:30:00Z')).toBe('Tuesday 27 October 2026');
    expect(formatTime('2026-10-26T23:30:00Z')).toBe('01:30');
    expect(formatTime('2026-10-27T09:30:00+02:00')).toBe('09:30');
    expect(formatTimeRange('2026-10-27T09:30:00+02:00', '2026-10-27T09:45:00+02:00')).toBe(
      '09:30 to 09:45 SAST',
    );
  });

  it('splits the day at noon SAST', () => {
    expect(isMorning('2026-10-27T11:45:00+02:00')).toBe(true);
    expect(isMorning('2026-10-27T12:00:00+02:00')).toBe(false);
    expect(isMorning('2026-10-27T09:59:00Z')).toBe(true);
    expect(isMorning('2026-10-27T10:00:00Z')).toBe(false);
  });

  it('tells today from the SAST calendar, not the device', () => {
    expect(todayInSast(new Date('2026-10-26T22:30:00Z'))).toBe('2026-10-27');
    expect(todayInSast(new Date('2026-10-26T21:59:00Z'))).toBe('2026-10-26');
  });

  it('counts days on the calendar alone, across months, years and the US clock change', () => {
    expect(addDays('2026-10-30', 3)).toBe('2026-11-02');
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
    expect(addDays('2026-11-01', -1)).toBe('2026-10-31');
    expect(datesBetween('2026-10-30', '2026-11-02')).toEqual([
      '2026-10-30',
      '2026-10-31',
      '2026-11-01',
      '2026-11-02',
    ]);
    expect(datesBetween('2026-10-26', '2026-11-24')).toHaveLength(30);
    expect(datesBetween('2026-10-27', '2026-10-26')).toEqual([]);
  });

  it('numbers weekdays as the API does and names a week range', () => {
    expect(weekdayNumber('2026-10-26')).toBe(1);
    expect(weekdayNumber('2026-11-01')).toBe(7);
    expect(weekdayName(6)).toBe('Saturday');
    expect(formatDateRange('2026-10-25', '2026-10-31')).toBe('25 to 31 October 2026');
    expect(formatDateRange('2026-10-29', '2026-11-04')).toBe('29 October to 4 November 2026');
    expect(formatDateRange('2026-12-28', '2027-01-03')).toBe('28 December 2026 to 3 January 2027');
  });

  it('writes a booking start with the SAST offset', () => {
    expect(sastInstant('2026-10-27', '09:30')).toBe('2026-10-27T09:30:00+02:00');
  });
});
