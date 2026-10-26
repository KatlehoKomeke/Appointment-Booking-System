/**
 * Step 3 of the booking: a date from the branch's window, then a time on it.
 *
 * Choosing a date or a time replaces the URL's entry rather than adding one, so Back leaves the step instead of
 * stepping through every tile tried; Continue adds `step=details`. Choosing a time does not move on by itself: the
 * choice is repeated above Continue, so the customer sees it before leaving the grid.
 *
 * @packageDocumentation
 */
import { type ReactNode, useCallback, useEffect, useId, useRef, useState } from 'react';
import { flushSync } from 'react-dom';

import { type Availability, type Slot, useAvailability, useBranch } from '../api/queries';
import {
  datesBetween,
  formatDateNoYear,
  formatDateShort,
  formatTime,
  type IsoDate,
  todayInSast,
} from '../lib/time';
import { Alert } from '../ui/Alert';
import { Button } from '../ui/Button';
import { PageHeading } from '../ui/PageHeading';
import { ProblemAlert } from '../ui/ProblemAlert';
import { BookingSoFar } from './BookingSoFar';
import { stepDocTitle, useBookingParams } from './bookingParams';
import { DateStrip } from './DateStrip';
import styles from './DateTimeStep.module.css';
import { TimeGrid } from './TimeGrid';

/** How long the step waits without a tap or a key before it says the times may have changed. */
export const STALE_AFTER_MS = 10 * 60_000;

const ACTIVITY_EVENTS = ['pointerdown', 'keydown'] as const;

/**
 * Whether the times on screen may be out of date: true once {@link STALE_AFTER_MS} pass with no tap or key press.
 * New data clears it, as does the returned `clear`.
 *
 * @param dataVersion - changes whenever fresh times arrive, such as the query's `dataUpdatedAt`
 */
function useStaleAfterInactivity(dataVersion: number): [boolean, () => void] {
  const [stale, setStale] = useState(false);
  const [seenVersion, setSeenVersion] = useState(dataVersion);
  if (seenVersion !== dataVersion) {
    setSeenVersion(dataVersion);
    setStale(false);
  }

  useEffect(() => {
    if (stale) {
      return;
    }
    let timer = window.setTimeout(() => {
      setStale(true);
    }, STALE_AFTER_MS);
    const restart = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(() => {
        setStale(true);
      }, STALE_AFTER_MS);
    };
    for (const event of ACTIVITY_EVENTS) {
      document.addEventListener(event, restart, { passive: true });
    }
    return () => {
      window.clearTimeout(timer);
      for (const event of ACTIVITY_EVENTS) {
        document.removeEventListener(event, restart);
      }
    };
  }, [stale, dataVersion]);

  const clear = useCallback(() => {
    setStale(false);
  }, []);
  return [stale, clear];
}

// The slot that starts at a time, if it still has a seat.
function freeSlot(availability: Availability | undefined, time: string | undefined) {
  if (availability === undefined || time === undefined) {
    return undefined;
  }
  return availability.slots.find(
    (slot) => slot.seatsAvailable > 0 && formatTime(slot.startsAt) === time,
  );
}

/** The date and time step. */
export function DateTimeStep() {
  const { branch, service, date, slot, setParams } = useBookingParams();
  const branchQuery = useBranch(branch);
  const availabilityQuery = useAvailability(branch, service, date);
  const availability = availabilityQuery.data;
  const today = todayInSast();
  const [stale, clearStale] = useStaleAfterInactivity(availabilityQuery.dataUpdatedAt);
  const inlineRefresh = useRef<HTMLButtonElement>(null);

  const detail = branchQuery.data;
  const openDates =
    detail === undefined
      ? []
      : datesBetween(detail.bookableFrom, detail.bookableTo).filter(
          (d) => !detail.closedDates.some((closed) => closed.date === d),
        );
  const firstOpen = openDates[0];

  // With no date in the URL, open on the first one the branch trades. Replace, so Back does not return here only
  // to be sent forward again.
  useEffect(() => {
    if (date === undefined && firstOpen !== undefined) {
      setParams({ date: firstOpen }, { replace: true });
    }
  }, [date, firstOpen, setParams]);

  const chooseDate = (next: IsoDate) => {
    if (next !== date) {
      setParams({ date: next }, { replace: true });
    }
  };

  const refresh = () => {
    void availabilityQuery.refetch();
    // The banner's Refresh goes away with the banner, so focus moves to the one beside "Times as of".
    flushSync(clearStale);
    inlineRefresh.current?.focus();
  };

  const chosen = freeSlot(availability, slot);
  const laterOpenDate = date === undefined ? undefined : openDates.find((d) => d > date);

  return (
    <div className={styles.step}>
      <PageHeading
        title="Choose a date and time"
        docTitle={stepDocTitle('datetime')}
        focusKey="datetime"
      />
      <BookingSoFar />

      {/* Present and empty until the times go stale, so the banner is announced when it appears. A closed day has no
          times to go stale, and no Refresh beside "Times as of" to take focus once the banner goes. */}
      <div role="status" className={styles.staleRegion}>
        {stale && availability?.closedReason === null && (
          <Alert
            tone="status"
            actions={
              <Button variant="secondary" size="compact" onClick={refresh}>
                Refresh
              </Button>
            }
          >
            Times may have changed. Refresh to see the latest.
          </Alert>
        )}
      </div>

      {branchQuery.isPending && branch !== undefined && (
        <p className={styles.muted}>Loading dates…</p>
      )}
      {branchQuery.isError && (
        <ProblemAlert
          error={branchQuery.error}
          className={styles.problem}
          onRetry={() => {
            void branchQuery.refetch();
          }}
        />
      )}

      {detail !== undefined && (
        <DateStrip
          from={detail.bookableFrom}
          to={detail.bookableTo}
          closedDates={detail.closedDates}
          selected={date}
          today={today}
          onSelect={chooseDate}
        />
      )}

      {date !== undefined && (
        <div className={styles.times}>
          {availabilityQuery.isPending && <p className={styles.muted}>Loading times…</p>}
          {availabilityQuery.isError && (
            <ProblemAlert
              error={availabilityQuery.error}
              className={styles.problem}
              onRetry={() => {
                void availabilityQuery.refetch();
              }}
            />
          )}
          {availability !== undefined && (
            <Day
              availability={availability}
              selected={chosen === undefined ? undefined : slot}
              laterOpenDate={laterOpenDate}
              onSelectTime={(time) => {
                setParams({ slot: time }, { replace: true });
              }}
              onSelectDate={chooseDate}
            >
              <p className={styles.asOf}>
                <span>Times as of {formatTime(availability.generatedAt)}.</span>
                {!stale && (
                  <button
                    ref={inlineRefresh}
                    type="button"
                    className={styles.textButton}
                    onClick={refresh}
                  >
                    Refresh
                  </button>
                )}
              </p>
            </Day>
          )}
        </div>
      )}

      <div className={styles.actionBar}>
        <p className={styles.choice}>
          {chosen !== undefined && date !== undefined ? (
            <ChosenTime date={date} slot={chosen} />
          ) : (
            'Choose a time to continue.'
          )}
        </p>
        <Button
          fullWidth
          disabled={chosen === undefined}
          onClick={() => {
            setParams({ step: 'details' });
          }}
        >
          Continue
        </Button>
      </div>
    </div>
  );
}

// "Tue 27 Oct, 09:30 to 09:45 SAST", the times in bold.
function ChosenTime({ date, slot }: { date: IsoDate; slot: Slot }) {
  return (
    <>
      {formatDateShort(date)},{' '}
      <strong className="tabular">
        {formatTime(slot.startsAt)} to {formatTime(slot.endsAt)}
      </strong>{' '}
      SAST
    </>
  );
}

interface DayProps {
  availability: Availability;
  selected: string | undefined;
  laterOpenDate: IsoDate | undefined;
  onSelectTime: (time: string) => void;
  onSelectDate: (date: IsoDate) => void;
  children: ReactNode;
}

// One day: the reason it is closed, or its times, with a pointer onwards when none is free.
function Day({
  availability,
  selected,
  laterOpenDate,
  onSelectTime,
  onSelectDate,
  children,
}: DayProps) {
  const closedHeadingId = useId();
  const day = formatDateNoYear(availability.date);
  if (availability.closedReason !== null) {
    const why =
      availability.closedReason === 'PUBLIC_HOLIDAY'
        ? `It is a public holiday${availability.holidayName === null ? '' : `: ${availability.holidayName}`}.`
        : 'The branch does not open that day.';
    return (
      <section className={styles.closed} aria-labelledby={closedHeadingId}>
        <h2 id={closedHeadingId} className={styles.dayHeading}>
          The branch is closed on {day}
        </h2>
        <p>{why} Choose another date.</p>
      </section>
    );
  }

  const free = availability.slots.some((s) => s.seatsAvailable > 0);
  return (
    <>
      {!free && (
        <div className={styles.noTimes}>
          <p>
            {availability.slots.length === 0
              ? `There are no times left to book on ${day}.`
              : `Every time on ${day} is taken.`}{' '}
            {laterOpenDate === undefined
              ? 'Try another date.'
              : 'The next days may have free times.'}
          </p>
          {laterOpenDate !== undefined && (
            <Button
              variant="secondary"
              size="compact"
              onClick={() => {
                onSelectDate(laterOpenDate);
              }}
            >
              See {formatDateNoYear(laterOpenDate)}
            </Button>
          )}
        </div>
      )}
      {availability.slots.length > 0 && (
        <TimeGrid
          date={availability.date}
          slots={availability.slots}
          selected={selected}
          onSelect={onSelectTime}
        >
          {children}
        </TimeGrid>
      )}
      {availability.slots.length === 0 && children}
    </>
  );
}
