/**
 * The confirmation page, at `/appointments/:reference`: a real page rather than a toast, so the customer can come
 * back to it, refresh it and copy the reference from it.
 *
 * It opens the appointment with the digits this tab remembered when it booked or looked the appointment up
 * (`lookupDigits`), and polls while the confirmation SMS is still being sent, so its status moves from Sending to
 * Sent by itself. A tab that has not seen the appointment, such as a link opened on another device, asks for the
 * last four digits once.
 *
 * @packageDocumentation
 */
import { zodResolver } from '@hookform/resolvers/zod';
import { type ReactNode, useId, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router';
import { z } from 'zod';

import { ApiProblem } from '../api/client';
import { problemMessage } from '../api/problemCopy';
import { type Appointment, type Notification, useAppointment } from '../api/queries';
import { lookupDigits } from '../booking/bookingSession';
import { plural } from '../lib/text';
import { formatDateLong, formatTime, formatTimeRange } from '../lib/time';
import { Alert } from '../ui/Alert';
import { Button, ButtonLink } from '../ui/Button';
import { Card } from '../ui/Card';
import { cx } from '../ui/cx';
import { Field } from '../ui/Field';
import { CheckIcon, ChevronRightIcon, CopyIcon, PhoneIcon } from '../ui/icons';
import { PageHeading } from '../ui/PageHeading';
import styles from './ConfirmationPage.module.css';
import { DeliveryStatus } from './DeliveryStatus';
import { canonicalReference, last4Field, useLookupAppointment } from './ManagePage.lookup';
import { SmsBubble } from './SmsBubble';

/** The confirmation page, at `/appointments/:reference`. */
export function ConfirmationPage() {
  const { reference = '' } = useParams();
  // Keyed, so moving to another appointment starts again with that appointment's remembered digits.
  return <Confirmation key={reference} reference={reference} />;
}

function Confirmation({ reference }: { reference: string }) {
  const [digits, setDigits] = useState(() => lookupDigits(reference));
  const appointment = useAppointment(reference, digits, { poll: true });
  // A failed poll keeps the appointment already shown; only a first load that fails shows the error.
  const opened = appointment.data;

  let title = 'Your appointment';
  let body: ReactNode = <p className={styles.muted}>Opening your appointment…</p>;
  if (digits === undefined) {
    title = 'Open your appointment';
    body = <DigitsForm reference={reference} onFound={setDigits} />;
  } else if (opened !== undefined) {
    title = HEADING[opened.status];
    body = <AppointmentView appointment={opened} />;
  } else if (appointment.isError) {
    body = (
      <LookupFailed
        error={appointment.error}
        onRetry={() => {
          void appointment.refetch();
        }}
      />
    );
  }

  return (
    <div className={styles.page}>
      {opened?.status === 'CONFIRMED' && (
        <span className={styles.mark}>
          <CheckIcon size={24} />
        </span>
      )}
      {/*
        One heading for every state, so that when the digits form gives way to the appointment, the focus its
        button had moves to the heading instead of being lost, even in a tab that was opened on this page.
      */}
      <PageHeading
        title={title}
        focusKey={digits === undefined ? 'digits' : 'opened'}
        lead={
          digits === undefined ? (
            <>
              To open{' '}
              <span className={styles.inlineReference}>
                {canonicalReference(reference) ?? reference}
              </span>
              , enter the last four digits of the cellphone number it was booked with.
            </>
          ) : undefined
        }
      />
      {body}
    </div>
  );
}

const HEADING: Record<Appointment['status'], string> = {
  CONFIRMED: 'Your appointment is confirmed',
  CHECKED_IN: 'Your appointment',
  COMPLETED: 'Your appointment',
  NO_SHOW: 'Your appointment',
  CANCELLED: 'This appointment was cancelled',
};

// The confirmation SMS, the latest one if there were several; a cancellation message is not what this page shows.
function confirmationMessage(appointment: Appointment): Notification | undefined {
  return appointment.notifications.findLast((n) => n.kind === 'CONFIRMATION');
}

function AppointmentView({ appointment }: { appointment: Appointment }) {
  const sms = confirmationMessage(appointment);

  return (
    <>
      <ReferenceCard reference={appointment.reference} />
      {sms !== undefined && <SmsCard notification={sms} />}
      <AppointmentCard appointment={appointment} />
      <ButtonLink to="/book" variant="secondary" fullWidth>
        Book another appointment
      </ButtonLink>
      {sms !== undefined && sms.status !== 'FAILED' && (
        <p className={styles.inboxLink}>
          <Link to="/messages">See the SMS in the simulated inbox</Link>
        </p>
      )}
    </>
  );
}

type CopyState = 'idle' | 'copied' | 'failed';

const COPY_STATUS: Record<CopyState, string> = {
  idle: '',
  copied: 'Reference copied.',
  failed: "Couldn't copy the reference here. Select it and copy it yourself.",
};

function ReferenceCard({ reference }: { reference: string }) {
  const [copy, setCopy] = useState<CopyState>('idle');
  const headingId = useId();

  const copyReference = async () => {
    try {
      // The clipboard is missing outside a secure context; reaching for it then throws, and lands below.
      await navigator.clipboard.writeText(reference);
      setCopy('copied');
    } catch {
      setCopy('failed');
    }
  };

  return (
    <Card as="section" shadow aria-labelledby={headingId}>
      <h2 id={headingId} className={styles.label}>
        Your reference
      </h2>
      <div className={styles.referenceRow}>
        <p className={styles.reference}>{reference}</p>
        <Button
          variant="secondary"
          size="compact"
          className={cx(copy === 'copied' && styles.copied)}
          // "Copy" is completed for a screen reader; the visible word starts the name, as it must.
          aria-label={copy === 'copied' ? undefined : 'Copy reference'}
          onClick={() => {
            void copyReference();
          }}
        >
          {copy === 'copied' ? (
            <>
              <CheckIcon size={18} />
              Copied
            </>
          ) : (
            <>
              <CopyIcon size={18} />
              Copy
            </>
          )}
        </Button>
      </div>
      <p className={styles.note}>
        Keep it. With the last four digits of your cellphone number, it opens this appointment
        again.
      </p>
      {/* On the page, empty, from the start, so the copy is announced; a failure is shown as well as said. */}
      <p role="status" className={cx(copy === 'failed' ? styles.copyFailed : 'visually-hidden')}>
        {COPY_STATUS[copy]}
      </p>
    </Card>
  );
}

// Why the SMS was not delivered, and what still holds, in the calm words of the design.
function FailureNote({ notification }: { notification: Notification }) {
  if (notification.failureReason === 'SUPERSEDED_BY_CANCELLATION') {
    return <p>The SMS was not sent, because the appointment was cancelled.</p>;
  }
  const why =
    notification.failureReason === 'DELIVERY_FAILED'
      ? `We could not send the SMS after ${plural(notification.attempts, 'attempt')}.`
      : 'We could not send the SMS.';
  return (
    <p>
      {why} <strong>Your booking still stands;</strong> keep your reference.
    </p>
  );
}

function SmsCard({ notification }: { notification: Notification }) {
  const [shown, setShown] = useState(true);
  const headingId = useId();
  const messageId = useId();

  return (
    <Card as="section" aria-labelledby={headingId}>
      <h2 id={headingId} className={styles.cardHeading}>
        Confirmation SMS
      </h2>
      <div className={styles.smsRow}>
        <span className={styles.recipient}>
          <PhoneIcon />
          To {notification.recipientMasked}
        </span>
        <DeliveryStatus notification={notification} />
      </div>

      {notification.status === 'PENDING' && (
        <p className={styles.note}>
          This usually takes a few seconds. You can leave this page: your booking is already saved.
        </p>
      )}

      {notification.status === 'SENT' && (
        <>
          <button
            type="button"
            className={styles.disclosure}
            aria-expanded={shown}
            aria-controls={messageId}
            onClick={() => {
              setShown((current) => !current);
            }}
          >
            {shown ? 'Hide the message' : 'Show the message'}
            <ChevronRightIcon size={18} className={cx(styles.chevron, shown && styles.open)} />
          </button>
          <div id={messageId} hidden={!shown}>
            <SmsBubble
              body={notification.body}
              meta={
                notification.sentAt === null
                  ? 'BRANCHBOOK · simulated'
                  : `BRANCHBOOK · ${formatTime(notification.sentAt)} · simulated`
              }
            />
          </div>
        </>
      )}

      {notification.status === 'FAILED' && (
        <>
          <div className={styles.failure}>
            <FailureNote notification={notification} />
          </div>
          <p className={styles.failureLink}>
            <Link to="/messages">See what happened in the simulated inbox</Link>
          </p>
        </>
      )}
    </Card>
  );
}

function AppointmentCard({ appointment }: { appointment: Appointment }) {
  const headingId = useId();
  const { service, branch } = appointment;

  return (
    <Card as="section" aria-labelledby={headingId}>
      <h2 id={headingId} className={styles.cardHeading}>
        Your appointment
      </h2>
      <dl className={styles.facts}>
        <div className={styles.fact}>
          <dt className={styles.term}>Service</dt>
          <dd className={styles.value}>{service.name}</dd>
        </div>
        <div className={styles.fact}>
          <dt className={styles.term}>Branch</dt>
          <dd>
            <span className={styles.value}>{branch.name}</span>
            <span className={styles.detail}>{branch.address}</span>
          </dd>
        </div>
        <div className={styles.fact}>
          <dt className={styles.term}>Date and time</dt>
          <dd>
            <span className={styles.value}>{formatDateLong(appointment.startsAt)}</span>
            <span className={styles.detail}>
              {formatTimeRange(appointment.startsAt, appointment.endsAt)}
            </span>
            {appointment.status === 'CONFIRMED' && (
              <span className={styles.detail}>Please arrive 10 minutes early.</span>
            )}
          </dd>
        </div>
        {service.documentsNeeded.length > 0 && (
          <div className={styles.fact}>
            <dt className={styles.term}>What to bring</dt>
            <dd>
              <ul className={styles.documents}>
                {service.documentsNeeded.map((document) => (
                  <li key={document}>{document}</li>
                ))}
              </ul>
            </dd>
          </div>
        )}
      </dl>
    </Card>
  );
}

function LookupFailed({ error, onRetry }: { error: Error; onRetry: () => void }) {
  const message = problemMessage(error);
  const notFound = error instanceof ApiProblem && error.code === 'APPOINTMENT_NOT_FOUND';

  return (
    <Alert
      tone="error"
      title={message.title}
      actions={
        notFound ? (
          <ButtonLink to="/manage" variant="secondary" size="compact">
            Find your appointment
          </ButtonLink>
        ) : (
          <Button variant="secondary" size="compact" onClick={onRetry}>
            Try again
          </Button>
        )
      }
    >
      {message.body}
    </Alert>
  );
}

const digitsForm = z.object({ last4: last4Field });

/**
 * The last four digits, asked once in a tab that has not booked or looked up this appointment, under the page's
 * heading and its lead. It is not in the mockups, so it keeps to the manage page's parts and words.
 */
function DigitsForm({
  reference,
  onFound,
}: {
  reference: string;
  onFound: (last4: string) => void;
}) {
  const lookup = useLookupAppointment();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm({
    resolver: zodResolver(digitsForm),
    mode: 'onTouched',
    defaultValues: { last4: '' },
  });

  const onSubmit = handleSubmit(({ last4 }) => {
    lookup.mutate(
      // The link may carry the reference in any case or without hyphens; the API is asked as /manage asks it.
      { reference: canonicalReference(reference) ?? reference, last4 },
      {
        onSuccess: () => {
          onFound(last4);
        },
      },
    );
  });

  const failure = lookup.isError ? problemMessage(lookup.error) : undefined;

  return (
    <>
      {failure !== undefined && (
        <Alert tone="error">
          <strong>{failure.title}</strong> {failure.body}
        </Alert>
      )}
      <form
        className={styles.form}
        noValidate
        onSubmit={(event) => {
          void onSubmit(event);
        }}
      >
        <Field
          id="last4"
          label="Last four digits of your cellphone number"
          className={styles.digits}
          inputMode="numeric"
          autoComplete="off"
          maxLength={4}
          error={errors.last4?.message}
          {...register('last4')}
        />
        <Button type="submit" fullWidth disabled={lookup.isPending}>
          Open appointment
        </Button>
      </form>
      <p className={styles.note}>
        Looking for another appointment? <Link to="/manage">Find your appointment</Link> with its
        reference.
      </p>
    </>
  );
}
