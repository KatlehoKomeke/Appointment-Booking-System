/**
 * The choices made so far, from step 3 on: the branch, the service, and once the time step is done, the date and
 * time, each with a Change link back to its step.
 *
 * Changing a choice goes back to that step and clears what depended on it, as `bookingParams` does: a new branch
 * needs a new service, and a new service a new time. Changing the date and time keeps both, so the grid opens on the
 * time already chosen.
 *
 * @packageDocumentation
 */
import { Link } from 'react-router';

import { useBranch } from '../api/queries';
import { addMinutes, formatDateShort, formatTimeRange, sastInstant } from '../lib/time';
import { Card } from '../ui/Card';
import { useBookingParams } from './bookingParams';
import styles from './BookingSoFar.module.css';

/**
 * The booking so far. It reads the choices from the URL and the names from the branch's detail, which the earlier
 * steps have already fetched, and renders nothing until that detail is in. A step renders it right under its
 * heading, as the mockups draw it, so a screen reader that lands on the heading reads the summary next.
 */
export function BookingSoFar() {
  const { branch, service, date, slot, step, hrefForStep } = useBookingParams();
  const detail = useBranch(branch).data;
  const chosenService = detail?.services.find((s) => s.code === service);

  if (detail === undefined || chosenService === undefined) {
    return null;
  }

  // On the time step the chosen time is repeated above Continue, so it joins this summary only after that step.
  const startsAt =
    step !== 'datetime' && date !== undefined && slot !== undefined
      ? sastInstant(date, slot)
      : undefined;

  return (
    <Card as="section" aria-label="Your booking so far">
      <dl className={styles.rows}>
        <div className={styles.row}>
          <dt className="visually-hidden">Branch</dt>
          <dd className={styles.value}>
            <strong>{detail.name}</strong>
          </dd>
          <dd className={styles.action}>
            <Link to={hrefForStep('branch')} className={styles.change} aria-label="Change branch">
              Change
            </Link>
          </dd>
        </div>
        <div className={styles.row}>
          <dt className="visually-hidden">Service</dt>
          <dd className={styles.value}>
            {chosenService.name}
            {startsAt === undefined && (
              <span className={styles.meta}>
                About {String(chosenService.durationMinutes)} minutes
              </span>
            )}
          </dd>
          <dd className={styles.action}>
            <Link to={hrefForStep('service')} className={styles.change} aria-label="Change service">
              Change
            </Link>
          </dd>
        </div>
        {startsAt !== undefined && date !== undefined && (
          <div className={styles.row}>
            <dt className="visually-hidden">Date and time</dt>
            <dd className={styles.value}>
              {formatDateShort(date)},{' '}
              <span className="tabular">
                {formatTimeRange(startsAt, addMinutes(startsAt, chosenService.durationMinutes))}
              </span>
            </dd>
            <dd className={styles.action}>
              <Link
                to={hrefForStep('datetime')}
                className={styles.change}
                aria-label="Change date and time"
              >
                Change
              </Link>
            </dd>
          </div>
        )}
      </dl>
    </Card>
  );
}
