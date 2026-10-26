/**
 * The dates a branch can be booked on, seven at a time, as buttons under the week's range.
 *
 * The window is the server's (`bookableFrom` to `bookableTo` on the branch), so the strip never offers a date the API
 * would refuse. A closed date stays in its place, disabled, so the week reads as a week, and its reason is written
 * under the strip and in the date's accessible name.
 *
 * @packageDocumentation
 */
import { useEffect, useId, useRef, useState } from 'react';

import type { BranchDetail } from '../api/queries';
import {
  addDays,
  datesBetween,
  dayOfMonth,
  formatDateNoYear,
  formatDateRange,
  formatDateShort,
  type IsoDate,
  weekdayName,
  weekdayNumber,
  weekdayShort,
} from '../lib/time';
import { cx } from '../ui/cx';
import { CalendarClosedIcon, ChevronLeftIcon, ChevronRightIcon } from '../ui/icons';
import styles from './DateStrip.module.css';

/** A date the branch does not trade, with why. */
export type ClosedDate = BranchDetail['closedDates'][number];

/** How many dates a page of the strip shows. */
const DATES_PER_PAGE = 7;

/** Props for {@link DateStrip}. */
export interface DateStripProps {
  /** The first bookable date, `YYYY-MM-DD`: the branch's `bookableFrom`. */
  from: IsoDate;
  /** The last bookable date: the branch's `bookableTo`. */
  to: IsoDate;
  /** The dates in the window the branch is closed. */
  closedDates: readonly ClosedDate[];
  /** The chosen date, if any. */
  selected: IsoDate | undefined;
  /** Today in SAST, which is marked. */
  today: IsoDate;
  /** Called with the date the customer chooses. */
  onSelect: (date: IsoDate) => void;
}

/**
 * Why a date is closed, as a clause after "closed": "public holiday, Local Government Elections", or for a day the
 * branch never trades, "the branch does not open on Sundays".
 *
 * @param closed - the closed date
 */
function closedClause(closed: ClosedDate): string {
  if (closed.reason === 'PUBLIC_HOLIDAY') {
    return closed.name === null ? 'public holiday' : `public holiday, ${closed.name}`;
  }
  return `the branch does not open on ${weekdayName(weekdayNumber(closed.date))}s`;
}

/**
 * The line under the strip for a closed date: "Wed 4 Nov: closed. Public holiday: Local Government Elections."
 *
 * @param closed - the closed date
 */
function closedLine(closed: ClosedDate): string {
  const when = `${formatDateShort(closed.date)}: closed.`;
  if (closed.reason === 'PUBLIC_HOLIDAY') {
    return closed.name === null
      ? `${when} Public holiday.`
      : `${when} Public holiday: ${closed.name}.`;
  }
  return `${when} The branch does not open on ${weekdayName(weekdayNumber(closed.date))}s.`;
}

/**
 * The date's full accessible name: "Wednesday 4 November, closed: public holiday, Local Government Elections".
 *
 * @param date - the date
 * @param today - whether it is today
 * @param closed - why it is closed, if it is
 */
function dateName(date: IsoDate, today: boolean, closed: ClosedDate | undefined): string {
  const parts = [formatDateNoYear(date)];
  if (today) {
    parts.push('today');
  }
  if (closed !== undefined) {
    parts.push(`closed: ${closedClause(closed)}`);
  }
  return parts.join(', ');
}

// The page a date is on, counting from the window's first date; a date outside the window opens the first page.
function pageOf(dates: readonly IsoDate[], date: IsoDate | undefined): number {
  const index = date === undefined ? -1 : dates.indexOf(date);
  return index === -1 ? 0 : Math.floor(index / DATES_PER_PAGE);
}

/**
 * The date strip: the week's range as a heading, Previous 7 days and Next 7 days, and seven date buttons.
 *
 * It opens on the page holding the chosen date and follows the date when it changes from outside, for example from
 * the "See" button under a fully booked day. The pager stays focusable at the window's edges, marked
 * `aria-disabled`, so a keyboard user who pages to the end is not dropped to the top of the page.
 */
export function DateStrip({ from, to, closedDates, selected, today, onSelect }: DateStripProps) {
  const headingId = useId();
  const chosenButton = useRef<HTMLButtonElement>(null);
  const previousSelected = useRef(selected);
  const dates = datesBetween(from, to);

  // A date chosen from outside the strip, such as "See Wednesday 28 October", takes away the control that had focus
  // when that day's times replace the old ones; give focus to the date rather than drop it to the top of the page.
  // Not on the first date, chosen for the customer on arrival, where focus is the page heading's or the browser's.
  useEffect(() => {
    const previous = previousSelected.current;
    previousSelected.current = selected;
    if (previous === undefined || previous === selected) {
      return;
    }
    const focused = document.activeElement;
    if (focused === null || focused === document.body) {
      chosenButton.current?.focus();
    }
  }, [selected]);
  const lastPage = Math.max(0, Math.ceil(dates.length / DATES_PER_PAGE) - 1);

  // A page the customer turned to is kept only while the chosen date is the one it was turned from.
  const [turned, setTurned] = useState<{ from: IsoDate | undefined; page: number } | null>(null);
  const page = Math.min(
    turned !== null && turned.from === selected ? turned.page : pageOf(dates, selected),
    lastPage,
  );
  const shown = dates.slice(page * DATES_PER_PAGE, (page + 1) * DATES_PER_PAGE);
  const closedByDate = new Map(closedDates.map((closed) => [closed.date, closed]));
  const closedShown = shown.flatMap((date) => {
    const closed = closedByDate.get(date);
    return closed === undefined ? [] : [closed];
  });

  const turnTo = (next: number) => {
    if (next >= 0 && next <= lastPage) {
      setTurned({ from: selected, page: next });
    }
  };

  const first = shown[0] ?? from;
  const last = shown[shown.length - 1] ?? addDays(first, DATES_PER_PAGE - 1);

  return (
    <section className={styles.strip} aria-labelledby={headingId}>
      <div className={styles.header}>
        {/* Polite, so paging is announced: the range is the only thing on the page that says where the strip is. */}
        <h2 id={headingId} className={styles.range} aria-live="polite">
          {formatDateRange(first, last)}
        </h2>
        <div className={styles.pager}>
          <button
            type="button"
            className={styles.pagerButton}
            aria-label="Previous 7 days"
            aria-disabled={page === 0}
            onClick={() => {
              turnTo(page - 1);
            }}
          >
            <ChevronLeftIcon />
          </button>
          <button
            type="button"
            className={styles.pagerButton}
            aria-label="Next 7 days"
            aria-disabled={page === lastPage}
            onClick={() => {
              turnTo(page + 1);
            }}
          >
            <ChevronRightIcon />
          </button>
        </div>
      </div>

      <ul className={styles.dates}>
        {shown.map((date) => {
          const closed = closedByDate.get(date);
          const isToday = date === today;
          const chosen = date === selected && closed === undefined;
          let marker: string | undefined;
          if (closed !== undefined) {
            marker = closed.reason === 'PUBLIC_HOLIDAY' ? 'Holiday' : 'Closed';
          } else if (isToday) {
            marker = 'Today';
          }
          return (
            <li key={date}>
              <button
                ref={chosen ? chosenButton : undefined}
                type="button"
                className={cx(
                  styles.date,
                  chosen && styles.chosen,
                  closed !== undefined && styles.closed,
                )}
                aria-pressed={chosen}
                aria-label={dateName(date, isToday, closed)}
                disabled={closed !== undefined}
                onClick={() => {
                  onSelect(date);
                }}
              >
                <span className={styles.weekday}>{weekdayShort(date)}</span>
                <span className={cx(styles.day, 'tabular')}>{dayOfMonth(date)}</span>
                {marker !== undefined && <span className={styles.marker}>{marker}</span>}
              </button>
            </li>
          );
        })}
      </ul>

      {closedShown.length > 0 && (
        <ul className={styles.reasons}>
          {closedShown.map((closed) => (
            <li key={closed.date} className={styles.reason}>
              <CalendarClosedIcon />
              <span>{closedLine(closed)}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
