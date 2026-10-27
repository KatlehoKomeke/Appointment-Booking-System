/**
 * The simulated inbox, at `/messages`: every message the outbox has produced, newest first, with its status,
 * attempts and last error, so a reviewer can watch a confirmation go from Pending to Sent, or to Failed for a
 * number ending 0000.
 *
 * It is a demo operator view. The API switches it off in production, answering a plain 404, and the page then says
 * calmly that the inbox is not available here. Recipients are always redacted (`082 *** ****`), and the server
 * may redact references too (`APT-****-****`), which are then plain text rather than links. A message the
 * dispatcher gave up on, and that may still be sent, has a Retry button (Tier 2); the outcome is said in a polite
 * status region in its card.
 *
 * @packageDocumentation
 */
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';

import { type InboxMessage, isLinkableReference, useInbox, useRetryMessage } from '../api/inbox';
import { problemMessage } from '../api/problemCopy';
import { plural } from '../lib/text';
import { formatTime } from '../lib/time';
import { Alert } from '../ui/Alert';
import { Badge } from '../ui/Badge';
import { Button, ButtonLink } from '../ui/Button';
import { Card } from '../ui/Card';
import { cx } from '../ui/cx';
import { CheckIcon, CrossIcon, MessageIcon, PendingIcon } from '../ui/icons';
import { PageHeading } from '../ui/PageHeading';
import { ProblemAlert } from '../ui/ProblemAlert';
import styles from './InboxPage.module.css';
import { SmsBubble } from './SmsBubble';

const TITLE = 'Simulated inbox';

/** The inbox page, at `/messages`. */
export function InboxPage() {
  const inbox = useInbox();

  if (inbox.data?.available === false) {
    return (
      <div className={styles.page}>
        <PageHeading
          title={TITLE}
          lead="The simulated inbox is switched off on this deployment, so other visitors cannot see your booking. It is a demo operator view of every booking's messages."
        />
        <p>
          Your own SMS and its delivery status are on your confirmation page. You can open it again
          from <Link to="/manage">Find your appointment</Link> with its reference and the last four
          digits of your cellphone number.
        </p>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <Alert tone="info" title="Demo operator view.">
        It lists every booking&apos;s messages, recipients redacted, and is switched off in
        production. Here every message is simulated, as on any local run.
      </Alert>
      <PageHeading
        title={TITLE}
        lead="Newest first. Updates every 2 seconds while a message is sending."
      />
      <InboxBody inbox={inbox} />
    </div>
  );
}

function InboxBody({ inbox }: { inbox: ReturnType<typeof useInbox> }) {
  if (inbox.data?.available === true) {
    const { messages } = inbox.data;
    if (messages.length === 0) {
      return (
        <div className={styles.empty}>
          <p>No messages yet. When an appointment is booked, its confirmation SMS appears here.</p>
          <ButtonLink to="/book" fullWidth>
            Book an appointment
          </ButtonLink>
        </div>
      );
    }
    return (
      <ul className={styles.list} aria-label="Messages">
        {messages.map((message) => (
          <MessageCard key={message.id} message={message} />
        ))}
      </ul>
    );
  }
  if (inbox.isError) {
    return (
      <ProblemAlert
        error={inbox.error}
        onRetry={() => {
          void inbox.refetch();
        }}
      />
    );
  }
  return <p className={styles.muted}>Loading messages…</p>;
}

const KIND: Record<InboxMessage['kind'], string> = {
  CONFIRMATION: 'Confirmation',
  CANCELLATION: 'Cancellation',
};

const CHANNEL: Record<InboxMessage['channel'], string> = {
  SMS: 'SMS',
  EMAIL: 'email',
};

// Why a message stopped, in words; the API's codes are for operators who read the logs.
function failureReasonText(message: InboxMessage): string | undefined {
  switch (message.failureReason) {
    case 'DELIVERY_FAILED':
      return `Delivery failed after ${plural(message.attempts, 'attempt')}`;
    case 'SUPERSEDED_BY_CANCELLATION':
      return 'Not sent: the appointment was cancelled first';
    case 'RECIPIENT_DAILY_CAP':
      return 'Not sent: the number reached its limit of messages for the day';
    case 'DISPATCH_ERROR':
      return 'Stopped: the result of a send could not be recorded';
    case null:
      return undefined;
  }
}

// The short codes the simulated gateway and the dispatcher write as the last error, in words.
const LAST_ERROR: Readonly<Record<string, string>> = {
  SIMULATED_FAILURE: 'The simulated gateway refuses numbers ending 0000',
  MARK_FAILED: 'The send could not be marked as done',
};

function lastErrorText(lastError: string): string {
  return LAST_ERROR[lastError] ?? lastError;
}

function StatusBadge({ status }: { status: InboxMessage['status'] }) {
  switch (status) {
    case 'PENDING':
      return (
        <Badge tone="pending">
          <PendingIcon size={16} />
          Pending
        </Badge>
      );
    case 'SENT':
      return (
        <Badge tone="sent">
          <CheckIcon size={16} />
          Sent
        </Badge>
      );
    case 'FAILED':
      return (
        <Badge tone="failed">
          <CrossIcon size={16} />
          Failed
        </Badge>
      );
  }
}

// "Created 10:42 SAST · sent 10:42 SAST · 1 attempt", with the next try while one is due after a failed attempt.
function metaLine(message: InboxMessage): string {
  const parts = [`Created ${formatTime(message.createdAt)} SAST`];
  if (message.sentAt !== null) {
    parts.push(`sent ${formatTime(message.sentAt)} SAST`);
  }
  parts.push(plural(message.attempts, 'attempt'));
  // Before the first attempt the next one is due at once, so it is said only once a try has failed.
  if (message.status === 'PENDING' && message.attempts > 0 && message.nextAttemptAt !== null) {
    parts.push(`next attempt ${formatTime(message.nextAttemptAt)} SAST`);
  }
  return parts.join(' · ');
}

// The reference as a link to its appointment, or as plain text when the server has redacted it.
function Reference({ reference }: { reference: string }) {
  return isLinkableReference(reference) ? (
    <Link to={`/appointments/${reference}`} className={styles.reference}>
      {reference}
    </Link>
  ) : (
    <span className={styles.reference}>{reference}</span>
  );
}

// The Retry button's full name, which names the message, since every card has one: "Retry the confirmation SMS to
// 082 *** ****, APT-H4WQ-8RZD". A redacted reference does not tell messages apart, so the time it was made is added.
function retryName(message: InboxMessage): string {
  const what = `${KIND[message.kind].toLowerCase()} ${CHANNEL[message.channel]}`;
  const which = isLinkableReference(message.reference)
    ? message.reference
    : `${message.reference}, created ${formatTime(message.createdAt)} SAST`;
  return `Retry the ${what} to ${message.recipientMasked}, ${which}`;
}

type RetryOutcome = { tone: 'done' | 'refused'; text: string } | undefined;

// What a retry's answer means, in words. The API answers 200 either way, so the message itself says what happened.
function retryOutcome(message: InboxMessage): RetryOutcome {
  switch (message.status) {
    case 'PENDING':
      return { tone: 'done', text: 'Queued to send again. Its status updates here.' };
    case 'SENT':
      return { tone: 'done', text: 'This message has already been sent.' };
    case 'FAILED':
      return {
        tone: 'refused',
        text: message.retryable
          ? 'This message was not queued again. Try again in a moment.'
          : 'This message can no longer be retried: its appointment was cancelled or has already started.',
      };
  }
}

function MessageCard({ message }: { message: InboxMessage }) {
  const reason = failureReasonText(message);
  const title = `${KIND[message.kind]} ${CHANNEL[message.channel]}`;
  const retry = useRetryMessage();
  const [outcome, setOutcome] = useState<RetryOutcome>(undefined);
  const heading = useRef<HTMLHeadingElement>(null);
  const pending = retry.isPending;
  const offered = message.retryable;
  // The region is on the page, empty, from when the button is, and stays while a retry is out or has answered, even
  // once the answer has taken the button away, so what it says is announced.
  const hasRegion = offered || !retry.isIdle || outcome !== undefined;

  // When an answer takes the button away, its focus goes to the card's heading rather than being lost.
  const wasOffered = useRef(offered);
  useEffect(() => {
    if (wasOffered.current && !offered && document.activeElement === document.body) {
      heading.current?.focus();
    }
    wasOffered.current = offered;
  }, [offered]);

  const onRetry = () => {
    if (pending) {
      return;
    }
    setOutcome(undefined);
    retry.mutate(message.id, {
      onSuccess: (answer) => {
        setOutcome(retryOutcome(answer));
      },
      onError: (error) => {
        const failure = problemMessage(error);
        setOutcome({ tone: 'refused', text: `${failure.title} ${failure.body ?? ''}`.trim() });
      },
    });
  };

  return (
    <Card as="li" shadow className={styles.card}>
      <div className={styles.cardHead}>
        <h2 ref={heading} tabIndex={-1} className={styles.cardHeading}>
          <MessageIcon />
          {title}
        </h2>
        <StatusBadge status={message.status} />
      </div>
      <p className={styles.to}>
        To {message.recipientMasked} · <Reference reference={message.reference} />
      </p>
      {message.subject !== null && <p className={styles.subject}>{message.subject}</p>}
      <SmsBubble body={message.body} />
      {(reason !== undefined || message.lastError !== null) && (
        <dl className={message.status === 'FAILED' ? styles.failure : styles.problem}>
          {reason !== undefined && (
            <div className={styles.problemRow}>
              <dt>Reason</dt>
              <dd>{reason}</dd>
            </div>
          )}
          {message.lastError !== null && (
            <div className={styles.problemRow}>
              <dt>Last error</dt>
              <dd>{lastErrorText(message.lastError)}</dd>
            </div>
          )}
        </dl>
      )}
      <p className={styles.meta}>{metaLine(message)}</p>
      {offered && (
        <div className={styles.retry}>
          <Button
            variant="secondary"
            size="compact"
            aria-label={retryName(message)}
            aria-disabled={pending || undefined}
            onClick={onRetry}
          >
            Retry
          </Button>
        </div>
      )}
      {hasRegion && (
        <p
          role="status"
          className={cx(
            outcome === undefined && 'visually-hidden',
            outcome?.tone === 'done' && styles.retryDone,
            outcome?.tone === 'refused' && styles.retryRefused,
          )}
        >
          {outcome?.text}
        </p>
      )}
    </Card>
  );
}
