/**
 * Where the customer is in the five booking steps, with a link back to the one before.
 *
 * @packageDocumentation
 */
import { Link, type To } from 'react-router';

import { cx } from './cx';
import { ChevronLeftIcon } from './icons';
import styles from './Stepper.module.css';

/**
 * The steps' names, in order. They are only the fallback for the Back link and the screen-reader names of the
 * segments; the booking flow's own labels (`STEP_LABEL` in `booking/bookingParams.ts`) are passed as `backLabel`.
 */
const STEP_NAMES = ['Branch', 'Service', 'Date and time', 'Your details', 'Review'] as const;

/** A step's number, 1 to 5. */
export type StepNumber = 1 | 2 | 3 | 4 | 5;

/** Props for {@link Stepper}. */
export interface StepperProps {
  /** The step the customer is on. */
  current: StepNumber;
  /** Where Back goes: the previous step's URL. Step 1 goes home (`/`) when this is not given. */
  backTo?: To;
  /** The previous step's name, shown beside the chevron: "Service". Step 1's is "Home". */
  backLabel?: string;
}

function lowerFirst(text: string) {
  return text.charAt(0).toLowerCase() + text.slice(1);
}

/**
 * The stepper: a `nav` named "Booking steps" with a Back link naming the previous step, "Step 3 of 5", and five
 * segments, the current one marked `aria-current="step"`.
 *
 * The Back link shows only the step's name, "‹ Service", and is read as "Back to service", which still contains
 * the visible words, so speech control finds it by what it says.
 */
export function Stepper({ current, backTo, backLabel }: StepperProps) {
  const to = backTo ?? (current === 1 ? '/' : undefined);
  const label = backLabel ?? (current === 1 ? 'Home' : STEP_NAMES[current - 2]);

  return (
    <nav aria-label="Booking steps" className={styles.stepper}>
      <div className={styles.row}>
        {to !== undefined && label !== undefined ? (
          <Link to={to} className={styles.back} aria-label={`Back to ${lowerFirst(label)}`}>
            <ChevronLeftIcon />
            {label}
          </Link>
        ) : (
          <span />
        )}
        <p className={cx(styles.count, 'tabular')}>
          Step {String(current)} of {String(STEP_NAMES.length)}
        </p>
      </div>
      <ol className={styles.segments}>
        {STEP_NAMES.map((name, index) => {
          const step = index + 1;
          return (
            <li
              key={name}
              className={cx(styles.segment, step <= current && styles.reached)}
              aria-current={step === current ? 'step' : undefined}
            >
              {/* The current step is announced by aria-current; the ones before it say so in words. */}
              <span className="visually-hidden">{step < current ? `${name}, done` : name}</span>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
