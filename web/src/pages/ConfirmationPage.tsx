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
 * appointment. The server deletes the appointment as it cancels it, so the cancel's answer is the last it says: the
 * page stops asking, and gives way to a final cancelled view that says the details are deleted and the cancellation
 * SMS is on its way, names what was cancelled and offers to book again. The tab remembers the cancellation without
 * the name or the number (`rememberedCancellation`), so a refresh shows the same view; any other tab finds nothing.
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
  isTooLateToCancel,
  type Notification,
  useAppointment,
  useCancelAppointment,
} from '../api/queries';
import {
  lookupDigits,
  type NamedCode,
  type RememberedCancellation,
  rememberedCancellation,
} from '../booking/bookingSession';
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
  // A cancel made in this tab before, such as before a refresh. One made on this page is the mutation's answer.
  const [remembered] = useState(() => rememberedCancellation(reference));
  // Here rather than in the cancel section, which gives way to the cancelled view, so the heading can take the
  // focus that the confirm button had.
  const cancel = useCancelAppointment();
  // The server deletes the appointment as it cancels it, so from the moment the cancel is sent the page stops
  // asking: a poll could only find nothing. It asks again if the cancel fails.
  const appointment = useAppointment(reference, digits, {
    poll: true,
    enabled: !cancel.isPending && !cancel.isSuccess,
  });
  // A failed poll keeps the appointment already shown; only a first load that fails shows the error.
  const opened = appointment.data;

  let cancelled: Cancelled | undefined;
  if (cancel.data !== undefined) {
    cancelled = fromAnswer(cancel.data);
  } else if (opened?.status === 'CANCELLED') {
    cancelled = fromAnswer(opened);
  } else if (
    remembered !== undefined &&
    opened === undefined &&
    (digits === undefined || isAppointmentNotFound(appointment.error))
  ) {
    cancelled = fromMemory(remembered);
  }

  let title = 'Your appointment';
  let lead: ReactNode = undefined;
  let body: ReactNode = <p className={styles.muted}>Opening your appointment…</p>;
  if (cancelled !== undefined) {
    title = HEADING.CANCELLED;
    lead = `Its details have been deleted. ${cancelled.sms}`;
    body = <CancelledView cancelled={cancelled} />;
  } else if (digits === undefined) {
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
      {cancelled === undefined && opened?.status === 'CONFIRMED' && (
        <span className={styles.mark}>
          <CheckIcon size={24} />
        </span>
      )}
      {/*
        One heading for every state, so that when the digits form gives way to the appointment, or the confirm
        button to the cancelled view, the focus the button had moves to the heading instead of being lost, even in
        a tab that was opened on this page.
      */}
      <PageHeading
        title={title}
        focusKey={
          cancelled !== undefined ? 'cancelled' : digits === undefined ? 'digits' : 'opened'
        }
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

function isAppointmentNotFound(error: unknown): boolean {
  return error instanceof ApiProblem && error.code === 'APPOINTMENT_NOT_FOUND';
}

// The latest message of a kind, if there were several.
function latestMessage(
  appointment: Appointment,
  kind: Notification['kind'],
): Notification | undefined {
  return appointment.notifications.findLast((n) => n.kind === kind);
}

/** What the cancelled view shows: the server's last answer, or what this tab remembers after a refresh. */
interface Cancelled extends RememberedCancellation {
  /** Where the cancellation SMS is going, in a sentence. */
  sms: string;
}

// The cancel's answer, or an appointment the server still calls cancelled. The cancellation SMS has just been
// queued, so it is on its way; only the answer knows the masked number it goes to.
function fromAnswer(appointment: Appointment): Cancelled {
  const recipient = latestMessage(appointment, 'CANCELLATION')?.recipientMasked;
  return {
    cancelledAt: appointment.cancelledAt,
    branch: appointment.branch,
    service: appointment.service,
    slot: { startsAt: appointment.startsAt, endsAt: appointment.endsAt },
    sms: `A cancellation SMS is on its way to ${recipient ?? 'the number you booked with'}.`,
  };
}

// After a refresh: the tab kept no number, not even masked, and the server can no longer say how the SMS went.
function fromMemory(remembered: RememberedCancellation): Cancelled {
  return { ...remembered, sms: 'A cancellation SMS was sent to the number you booked with.' };
}

/**
 * Booking again: the same service at the same branch, opening the booking flow on the date-and-time step, or
 * anything else. A branch that no longer offers the service is caught on that step, with the API's own words for
 * it.
 */
function BookAgain({ branch, service }: { branch: NamedCode; service: NamedCode }) {
  const search = new URLSearchParams({ branch: branch.code, service: service.code });
  return (
    <>
      <ButtonLink
        to={`/book?${search.toString()}`}
        variant="secondary"
        fullWidth
        // The visible words start the name, as they must; the rest says which service and where, for a screen
        // reader's list of links, which reads each link without the card above it.
        aria-label={`Book this service again: ${service.name} at ${branch.name}`}
      >
        Book this service again
      </ButtonLink>
      <ButtonLink to="/book" variant="secondary" fullWidth>
        Book another appointment
      </ButtonLink>
    </>
  );
}

/**
 * The final view of an appointment once it is cancelled: what it was for, when it was cancelled, and the ways to
 * book again. Nothing on it changes, so it has no live region: the server keeps nothing more to say about the
 * appointment, and the cancellation SMS's delivery can no longer be watched.
 */
function CancelledView({ cancelled }: { cancelled: Cancelled }) {
  const headingId = useId();
  const { service, branch, slot, cancelledAt } = cancelled;

  return (
    <>
      <Card as="section" aria-labelledby={headingId}>
        <div className={styles.cardHead}>
          <h2 id={headingId} className={styles.cardHeading}>
            Your appointment
          </h2>
          <Badge tone="neutral">Cancelled</Badge>
        </div>
        <dl className={styles.facts}>
          <div className={styles.fact}>
            <dt className={styles.term}>Service</dt>
            <dd className={styles.value}>{service.name}</dd>
          </div>
          <div className={styles.fact}>
            <dt className={styles.term}>Branch</dt>
            <dd className={styles.value}>{branch.name}</dd>
          </div>
          {slot !== null && (
            <div className={styles.fact}>
              <dt className={styles.term}>Date and time</dt>
              <dd>
                <span className={styles.value}>{formatDateLong(slot.startsAt)}</span>
                <span className={styles.detail}>{formatTimeRange(slot.startsAt, slot.endsAt)}</span>
              </dd>
            </div>
          )}
          {cancelledAt !== null && (
            <div className={styles.fact}>
              <dt className={styles.term}>Cancelled</dt>
              <dd>
                <span className={styles.value}>
                  {formatTime(cancelledAt)} SAST on {formatDateLong(cancelledAt)}
                </span>
                <span className={styles.detail}>
                  Its time is free again for someone else to book.
                </span>
              </dd>
            </div>
          )}
        </dl>
      </Card>
      <BookAgain branch={branch} service={service} />
    </>
  );
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

  return (
    <>
      <ReferenceCard reference={appointment.reference} />
      {confirmation !== undefined && <SmsCard notification={confirmation} />}
      <AppointmentCard appointment={appointment} />
      <BookAgain branch={appointment.branch} service={appointment.service} />
      {confirmation !== undefined && confirmation.status !== 'FAILED' && (
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

// The confirmation SMS. A cancelled appointment's messages are never shown: the cancelled view replaces this page.
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
                  ? 'BRANCHBOOK'
                  : `BRANCHBOOK · ${formatTime(notification.sentAt)} SAST`
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

  return (
    <Alert
      tone="error"
      title={message.title}
      actions={
        isAppointmentNotFound(error) ? (
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
