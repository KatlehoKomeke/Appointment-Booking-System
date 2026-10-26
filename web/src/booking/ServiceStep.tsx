/**
 * Step 2 of the booking: the chosen branch, with its address and hours, then the services it offers.
 *
 * Each service has its own Choose button and its own "What to bring" list, so the documents can be read without
 * choosing the service. Choosing is a button because it acts on the booking (it sets `service`); the URL change
 * still adds a history entry, so Back returns here.
 *
 * @packageDocumentation
 */
import { useId } from 'react';
import { Link } from 'react-router';

import type { ProblemMessage } from '../api/problemCopy';
import { type BranchDetail, type BranchService, useBranch } from '../api/queries';
import { addressLine } from '../lib/text';
import { weekdayName } from '../lib/time';
import { Alert } from '../ui/Alert';
import { Button } from '../ui/Button';
import { Card } from '../ui/Card';
import { ChevronRightIcon, ClockIcon } from '../ui/icons';
import { PageHeading } from '../ui/PageHeading';
import { ProblemAlert } from '../ui/ProblemAlert';
import { stepDocTitle, useBookingParams } from './bookingParams';
import styles from './ServiceStep.module.css';

/** Props for {@link ServiceStep}. */
export interface ServiceStepProps {
  /** Why the customer is back on this step, such as a service in the URL that the branch does not offer. */
  problem?: ProblemMessage;
}

type OperatingHours = BranchDetail['operatingHours'][number];

/** A run of weekdays with the same hours: "Mon to Fri, 08:00 to 17:00", or one day, "Sunday, Closed". */
interface HoursRow {
  days: string;
  hours: string;
}

/**
 * The week's hours, Monday first, with consecutive days that keep the same hours folded into one row and the days
 * the branch does not open said as closed.
 *
 * @param operatingHours - the branch's hours; a weekday that is missing is a closed day
 */
function hoursByDay(operatingHours: readonly OperatingHours[]): HoursRow[] {
  const hoursOn = (weekday: number) => {
    const day = operatingHours.find((h) => h.weekday === weekday);
    return day === undefined ? 'Closed' : `${day.opensAt} to ${day.closesAt}`;
  };
  const rows: { first: number; last: number; hours: string }[] = [];
  for (let weekday = 1; weekday <= 7; weekday += 1) {
    const hours = hoursOn(weekday);
    const previous = rows.at(-1);
    if (previous?.hours === hours) {
      previous.last = weekday;
    } else {
      rows.push({ first: weekday, last: weekday, hours });
    }
  }
  return rows.map(({ first, last, hours }) => ({
    days:
      first === last
        ? weekdayName(first)
        : `${weekdayName(first).slice(0, 3)} to ${weekdayName(last).slice(0, 3)}`,
    hours,
  }));
}

// "Update personal details (FICA)" is read as "Choose update personal details (FICA)".
function lowerFirst(text: string): string {
  return text.charAt(0).toLowerCase() + text.slice(1);
}

/** The service step: the branch's address and hours, then each service with what to bring and a Choose button. */
export function ServiceStep({ problem }: ServiceStepProps) {
  const { branch, setParams, hrefForStep } = useBookingParams();
  const branchQuery = useBranch(branch);
  const detail = branchQuery.data;

  return (
    <div className={styles.step}>
      <PageHeading title="Choose a service" docTitle={stepDocTitle('service')} focusKey="service" />

      {problem !== undefined && (
        <Alert tone="error" title={problem.title}>
          {problem.body}
        </Alert>
      )}

      {branchQuery.isPending && <p className={styles.muted}>Loading the branch…</p>}

      {branchQuery.isError && (
        <ProblemAlert
          error={branchQuery.error}
          onRetry={() => {
            void branchQuery.refetch();
          }}
        />
      )}

      {detail !== undefined && (
        <>
          <BranchCard detail={detail} changeHref={hrefForStep('branch')} />
          <ul className={styles.services} aria-label={`Services at ${detail.name}`}>
            {detail.services.map((service) => (
              <ServiceCard
                key={service.code}
                service={service}
                onChoose={() => {
                  setParams({ service: service.code });
                }}
              />
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

function BranchCard({ detail, changeHref }: { detail: BranchDetail; changeHref: string }) {
  const headingId = useId();
  return (
    <Card as="section" aria-labelledby={headingId}>
      <div className={styles.cardTop}>
        <h2 id={headingId} className={styles.cardHeading}>
          {detail.name}
        </h2>
        <Link to={changeHref} className={styles.change} aria-label="Change branch">
          Change
        </Link>
      </div>
      <p className={styles.address}>{addressLine(detail.address)}</p>
      <dl className={styles.hours} aria-label="Opening hours">
        {hoursByDay(detail.operatingHours).map((row) => (
          <div key={row.days} className={styles.hoursRow}>
            <dt className={styles.days}>{row.days}</dt>
            <dd className="tabular">{row.hours}</dd>
          </div>
        ))}
      </dl>
    </Card>
  );
}

function ServiceCard({ service, onChoose }: { service: BranchService; onChoose: () => void }) {
  return (
    <Card as="li" shadow>
      <div className={styles.cardTop}>
        <div className={styles.serviceText}>
          <h2 className={styles.cardHeading}>{service.name}</h2>
          <p className={styles.duration}>
            <ClockIcon size={18} />
            About {String(service.durationMinutes)} minutes
          </p>
        </div>
        <Button
          variant="secondary"
          size="compact"
          className={styles.choose}
          aria-label={`Choose ${lowerFirst(service.name)}`}
          onClick={onChoose}
        >
          Choose
        </Button>
      </div>
      {service.documentsNeeded.length > 0 && (
        <details className={styles.bring}>
          <summary className={styles.bringSummary}>
            What to bring
            <ChevronRightIcon size={16} className={styles.bringChevron} />
          </summary>
          <ul className={styles.documents}>
            {service.documentsNeeded.map((document) => (
              <li key={document}>{document}</li>
            ))}
          </ul>
        </details>
      )}
    </Card>
  );
}
