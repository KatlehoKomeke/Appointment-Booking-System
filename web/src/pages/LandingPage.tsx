/**
 * The landing page: what BranchBook does, the two ways in (book or manage), how booking works, and what to bring.
 *
 * @packageDocumentation
 */
import { ButtonLink } from '../ui/Button';
import { Card } from '../ui/Card';
import { PageHeading } from '../ui/PageHeading';
import styles from './LandingPage.module.css';

/** The three steps of "How it works", in the order the customer meets them. */
const HOW_IT_WORKS = [
  {
    title: 'Choose a branch and a service',
    body: 'Six branches across South Africa. Each service lists the documents to bring.',
  },
  {
    title: 'Pick a time',
    body: 'Any open day in the next 30 days, in South African Standard Time (SAST), wherever you are.',
  },
  {
    title: 'Get your reference by SMS',
    body: 'With the last four digits of your number, it opens your appointment again later.',
  },
] as const;

/** The landing page, at `/`. */
export function LandingPage() {
  return (
    <div className={styles.page}>
      <PageHeading
        title="Book a branch appointment"
        lead="Choose a branch, a service and a time that suits you, and get an SMS confirmation in seconds."
      />

      <div className={styles.actions}>
        <ButtonLink to="/book" fullWidth>
          Book an appointment
        </ButtonLink>
        <ButtonLink to="/manage" variant="secondary" fullWidth>
          Manage an appointment
        </ButtonLink>
      </div>

      <Card as="section" aria-labelledby="how-it-works" className={styles.section}>
        <h2 id="how-it-works" className={styles.sectionHeading}>
          How it works
        </h2>
        <ol className={styles.steps}>
          {HOW_IT_WORKS.map((step, index) => (
            <li key={step.title} className={styles.step}>
              {/* The list already numbers its items for a screen reader; the circle is the visible number. */}
              <span className={styles.number} aria-hidden="true">
                {String(index + 1)}
              </span>
              <div>
                <h3 className={styles.stepTitle}>{step.title}</h3>
                <p className={styles.stepBody}>{step.body}</p>
              </div>
            </li>
          ))}
        </ol>
      </Card>

      <Card as="section" aria-labelledby="before-you-visit" className={styles.section}>
        <h2 id="before-you-visit" className={styles.sectionHeading}>
          Before you visit
        </h2>
        <p className={styles.sectionBody}>
          Bring your ID and the documents your service lists, and arrive 10 minutes early. Booking
          is free.
        </p>
      </Card>
    </div>
  );
}
