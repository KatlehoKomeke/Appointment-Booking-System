/**
 * The confirmation page, at `/appointments/:reference`: a real page rather than a toast, so the customer can come
 * back to it, refresh it and copy the reference from it.
 *
 * It opens the appointment with the digits this tab remembered when it booked or looked the appointment up
 * (`lookupDigits`), and polls while the confirmation SMS is still being sent, so its status moves from Sending to
 * Sent by itself. A tab that has not seen the appointment, such as a link opened on another device, asks for the
 * last four digits once.
 *
 * Until the appointment starts, the customer can cancel it (Tier 2). Cancelling asks first, inline, naming the
 * appointment; once cancelled, the page says so calmly and shows the cancellation SMS being sent, with the
 * confirmation that was stopped shown as not sent rather than as a failure.
 *
 * @packageDocumentation
 */
import { zodResolver } from '@hookform/resolvers/zod';
import { type ReactNode, useCallback, useEffect, useId, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router';
import { z } from 'zod';

import { ApiProblem } from '../api/client';
import { problemMessage } from '../api/problemCopy';
import {
  type Appointment,
  isSupersededByCancellation,
  isTooLateToCancel,
  type Notification,
  useAppointment,
  useCancelAppointment,
} from '../api/queries';
import { lookupDigits } from '../booking/bookingSession';
import { plural } from '../lib/text';
import { formatDateLong, formatTime, formatTimeRange } from '../lib/time';
import { Alert } from '../ui/Alert';
import { Badge } from '../ui/Badge';
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

type CancelMutation = ReturnType<typeof useCancelAppointment>;

function Confirmation({ reference }: { reference: string }) {
  const [digits, setDigits] = useState(() => lookupDigits(reference));
  const appointment = useAppointment(reference, digits, { poll: true });
  // Here rather than in the cancel section, which is gone once the appointment is cancelled, so the heading can take
  // the focus that the confirm button had.
  const cancel = useCancelAppointment();
  // A failed poll keeps the appointment already shown; only a first load that fails shows the error.
  const opened = appointment.data;

  let title = 'Your appointment';
  let lead: ReactNode = undefined;
  let body: ReactNode = <p className={styles.muted}>Opening your appointment…</p>;
  if (digits === undefined) {
    title = 'Open your appointment';
    lead = (
      <>
        To open{' '}
        <span className={styles.inlineReference}>{canonicalReference(reference) ?? reference}</span>
        , enter the last four digits of the cellphone number it was booked with.
      </>
    );
    body = <DigitsForm reference={reference} onFound={setDigits} />;
  } else if (opened !== undefined) {
    title = HEADING[opened.status];
    lead = opened.status === 'CANCELLED' ? cancelledLead(opened) : undefined;
    body = (
      <AppointmentView appointment={opened} reference={reference} digits={digits} cancel={cancel} />
    );
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
        One heading for every state, so that when the digits form gives way to the appointment, or the confirm
        button to the cancelled appointment, the focus the button had moves to the heading instead of being lost,
        even in a tab that was opened on this page.
      */}
      <PageHeading
        title={title}
        focusKey={digits === undefined ? 'digits' : cancel.isSuccess ? 'cancelled' : 'opened'}
        lead={lead}
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

// The latest message of a kind, if there were several.
function latestMessage(
  appointment: Appointment,
  kind: Notification['kind'],
): Notification | undefined {
  return appointment.notifications.findLast((n) => n.kind === kind);
}

// Under the heading of a cancelled appointment: when, in SAST, and that its time is free again.
function cancelledLead({ cancelledAt }: Appointment): string {
  const released = 'Its time is free again for someone else to book.';
  return cancelledAt === null
    ? released
    : `Cancelled at ${formatTime(cancelledAt)} SAST on ${formatDateLong(cancelledAt)}. ${released}`;
}

// The booking flow with this branch and service already chosen, so it opens on the date-and-time step. A branch
// that no longer offers the service is caught there, with the API's own words for it.
function bookAgainHref({ branch, service }: Appointment): string {
  const search = new URLSearchParams({ branch: branch.code, service: service.code });
  return `/book?${search.toString()}`;
}

function AppointmentView({
  appointment,
  reference,
  digits,
  cancel,
}: {
  appointment: Appointment;
  reference: string;
  digits: string;
  cancel: CancelMutation;
}) {
  const confirmation = latestMessage(appointment, 'CONFIRMATION');
  const cancellation = latestMessage(appointment, 'CANCELLATION');
  // The message the customer is waiting on: the cancellation once there is one.
  const latest = cancellation ?? confirmation;

  return (
    <>
      <ReferenceCard reference={appointment.reference} />
      {cancellation !== undefined && <SmsCard notification={cancellation} />}
      {confirmation !== undefined && <SmsCard notification={confirmation} />}
      <AppointmentCard appointment={appointment} />
      <ButtonLink
        to={bookAgainHref(appointment)}
        variant="secondary"
        fullWidth
        // The visible words start the name, as they must; the rest says which service and where, for a screen
        // reader's list of links, which reads each link without the card above it.
        aria-label={`Book this service again: ${appointment.service.name} at ${appointment.branch.name}`}
      >
        Book this service again
      </ButtonLink>
      <ButtonLink to="/book" variant="secondary" fullWidth>
        Book another appointment
      </ButtonLink>
      {latest !== undefined && latest.status !== 'FAILED' && (
        <p className={styles.inboxLink}>
          <Link to="/messages">See the SMS in the simulated inbox</Link>
        </p>
      )}
      {appointment.status === 'CONFIRMED' && (
        <CancelSection
          appointment={appointment}
          reference={reference}
          digits={digits}
          cancel={cancel}
        />
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
  if (isSupersededByCancellation(notification)) {
    return <p>Not sent: the appointment was cancelled before this SMS went out.</p>;
  }
  const why =
    notification.failureReason === 'DELIVERY_FAILED'
      ? `We could not send the SMS after ${plural(notification.attempts, 'attempt')}.`
      : 'We could not send the SMS.';
  return notification.kind === 'CANCELLATION' ? (
    <p>
      {why} <strong>Your appointment is still cancelled.</strong>
    </p>
  ) : (
    <p>
      {why} <strong>Your booking still stands;</strong> keep your reference.
    </p>
  );
}

const SMS_TITLE: Record<Notification['kind'], string> = {
  CONFIRMATION: 'Confirmation SMS',
  CANCELLATION: 'Cancellation SMS',
};

// What is already saved while the SMS is on its way.
const SAVED: Record<Notification['kind'], string> = {
  CONFIRMATION: 'your booking is already saved',
  CANCELLATION: 'your cancellation is already saved',
};

function SmsCard({ notification }: { notification: Notification }) {
  const [shown, setShown] = useState(true);
  const headingId = useId();
  const messageId = useId();
  const superseded = isSupersededByCancellation(notification);

  return (
    <Card as="section" aria-labelledby={headingId}>
      <h2 id={headingId} className={styles.cardHeading}>
        {SMS_TITLE[notification.kind]}
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
          This usually takes a few seconds. You can leave this page: {SAVED[notification.kind]}.
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
          {!superseded && (
            <p className={styles.failureLink}>
              <Link to="/messages">See what happened in the simulated inbox</Link>
            </p>
          )}
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
      <div className={styles.cardHead}>
        <h2 id={headingId} className={styles.cardHeading}>
          Your appointment
        </h2>
        {appointment.status === 'CANCELLED' && <Badge tone="neutral">Cancelled</Badge>}
      </div>
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
            {appointment.status === 'CANCELLED' && (
              <span className={styles.detail}>Cancelled: this time is free for someone else.</span>
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

/**
 * Cancelling, under the appointment while it can still be cancelled. "Cancel appointment" opens an inline step that
 * names the appointment and says what cancelling does; nothing is sent until "Yes, cancel it". The step is a region
 * whose heading takes focus when it opens; "Keep my appointment" and Escape close it and give focus back to
 * "Cancel appointment". A native modal `<dialog>` was not used because jsdom, where the tests run, does not
 * implement `showModal()`.
 *
 * The buttons are marked `aria-disabled` rather than `disabled` while the request is out, so the focused one keeps
 * focus and a refusal can be read from where the customer is.
 */
function CancelSection({
  appointment,
  reference,
  digits,
  cancel,
}: {
  appointment: Appointment;
  reference: string;
  digits: string;
  cancel: CancelMutation;
}) {
  const [confirming, setConfirming] = useState(false);
  const headingId = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const region = useRef<HTMLElement>(null);
  const refusal = useRef<HTMLDivElement>(null);
  const wasConfirming = useRef(false);
  const pending = cancel.isPending;
  const tooLate = isTooLateToCancel(cancel.error);
  const { reset } = cancel;

  const keep = useCallback(() => {
    setConfirming(false);
    reset();
  }, [reset]);

  // The heading when the step opens; back to "Cancel appointment" when it closes.
  useEffect(() => {
    if (confirming) {
      heading.current?.focus();
    } else if (wasConfirming.current) {
      trigger.current?.focus();
    }
    wasConfirming.current = confirming;
  }, [confirming]);

  // Too late: the step gives way to the refusal, which takes the focus its button had.
  useEffect(() => {
    if (tooLate) {
      refusal.current?.focus();
    }
  }, [tooLate]);

  // Escape keeps the appointment, from anywhere in the step, unless the cancel is already on its way. The step's
  // element is looked up again whenever it can have been swapped: on opening, and when a refusal or a refetch
  // replaces it.
  const { canCancel } = appointment;
  useEffect(() => {
    const element = region.current;
    if (!confirming || element === null) {
      return undefined;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !pending) {
        event.preventDefault();
        keep();
      }
    };
    element.addEventListener('keydown', onKeyDown);
    return () => {
      element.removeEventListener('keydown', onKeyDown);
    };
  }, [confirming, pending, keep, tooLate, canCancel]);

  if (tooLate) {
    const message = problemMessage(cancel.error);
    return (
      <div ref={refusal} tabIndex={-1}>
        <Alert tone="error" title={message.title}>
          {message.body}
        </Alert>
      </div>
    );
  }
  if (!appointment.canCancel) {
    return null;
  }
  if (!confirming) {
    return (
      <Button
        ref={trigger}
        variant="secondary"
        destructive
        fullWidth
        onClick={() => {
          setConfirming(true);
        }}
      >
        Cancel appointment
      </Button>
    );
  }

  const failure = cancel.isError ? problemMessage(cancel.error) : undefined;
  const { service, branch } = appointment;
  return (
    <section ref={region} aria-labelledby={headingId} className={styles.confirmCancel}>
      <h2 id={headingId} ref={heading} tabIndex={-1} className={styles.cardHeading}>
        Cancel this appointment?
      </h2>
      <p className={styles.confirmWhat}>
        <span className={styles.value}>{service.name}</span>
        <span>at {branch.name}</span>
        <span>
          {formatDateLong(appointment.startsAt)},{' '}
          {formatTimeRange(appointment.startsAt, appointment.endsAt)}
        </span>
      </p>
      <p>
        Cancelling releases the time for someone else to book. <strong>It cannot be undone.</strong>
      </p>
      {failure !== undefined && (
        <Alert tone="error" title={failure.title}>
          {failure.body}
        </Alert>
      )}
      <div className={styles.confirmActions}>
        <Button
          destructive
          fullWidth
          aria-disabled={pending || undefined}
          onClick={() => {
            if (!pending) {
              cancel.mutate({ reference, last4: digits });
            }
          }}
        >
          Yes, cancel it
        </Button>
        <Button
          variant="secondary"
          fullWidth
          aria-disabled={pending || undefined}
          onClick={() => {
            if (!pending) {
              keep();
            }
          }}
        >
          Keep my appointment
        </Button>
      </div>
    </section>
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
