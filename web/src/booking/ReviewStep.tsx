/**
 * Step 5, Review: every fact of the booking once, each with an Edit link back to its step, what to bring, and the one
 * button that books (mockups docs/assets/ui/06-review.png and 08-slot-taken.png).
 *
 * Confirm sends the booking with the idempotency key `useIdempotencyKey` gives this exact request, so a double tap, a
 * retry or a refresh can never book twice, and any change to the request, such as choosing another time, gets a new
 * key. A refusal is worded by its code (`problemCopy.ts`) and answered where the customer can act on it: a time that
 * was just taken offers the later times on the same day, here; a refused detail goes back to the details step with
 * the error on its field; a slot the server refuses links to the times.
 *
 * @packageDocumentation
 */
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Link, Navigate, useNavigate } from 'react-router';

import { ApiProblem, type Problem } from '../api/client';
import { fieldErrors, problemMessage, type ProblemMessage } from '../api/problemCopy';
import {
  type BookingRequest,
  type BranchDetail,
  type BranchService,
  queryKeys,
  useBookAppointment,
  useBranch,
} from '../api/queries';
import { parseSaMobile } from '../lib/mobile';
import { addressLine } from '../lib/text';
import {
  addMinutes,
  formatDateLong,
  formatDateNoYear,
  formatTime,
  formatTimeRange,
  sastDate,
  sastInstant,
} from '../lib/time';
import { Alert } from '../ui/Alert';
import { Badge } from '../ui/Badge';
import { Button } from '../ui/Button';
import { Card } from '../ui/Card';
import { PageHeading } from '../ui/PageHeading';
import { ProblemAlert } from '../ui/ProblemAlert';
import {
  type BookingParamChanges,
  type BookingStepId,
  stepDocTitle,
  useBookingParams,
} from './bookingParams';
import { clearBooking, type CustomerDetails, loadDetails } from './bookingSession';
import { type DetailsHandOff, detailsErrors, MOBILE_ERROR, normaliseName } from './detailsForm';
import styles from './ReviewStep.module.css';
import { useIdempotencyKey } from './useIdempotencyKey';

/** The most later times offered after `SLOT_FULL`, as the mockup draws them. */
const MAX_ALTERNATIVES = 3;

type Alternative = NonNullable<Problem['alternatives']>[number];

interface Choices {
  branch: string;
  service: string;
  date: string;
  slot: string;
}

// The request the API takes: the start as a SAST instant, the names as the API stores them, and the number in E.164
// when it reads as one. A number that does not is sent as typed, so the API's MOBILE_NUMBER_REQUIRED brings the
// customer back to the field.
function bookingRequest(choices: Choices, details: CustomerDetails): BookingRequest {
  const mobile = parseSaMobile(details.mobile);
  return {
    branchCode: choices.branch,
    serviceCode: choices.service,
    startsAt: sastInstant(choices.date, choices.slot),
    customer: {
      firstName: normaliseName(details.firstName),
      lastName: normaliseName(details.lastName),
      mobile: mobile.ok ? mobile.e164 : details.mobile.trim(),
    },
  };
}

// The id support needs, added where the copy does not already quote it.
function withReference(message: ProblemMessage, correlationId: string | undefined): ProblemMessage {
  if (correlationId === undefined || message.body?.includes(correlationId) === true) {
    return message;
  }
  const quote = `If it keeps happening, quote reference ${correlationId}.`;
  return {
    title: message.title,
    body: message.body === undefined ? quote : `${message.body} ${quote}`,
  };
}

// What the details step is sent when the server refused one of the customer's details, or `undefined` when the
// refusal is about something else. A number the API does not read as a cellphone always belongs on its field.
function detailsHandOff(error: unknown): DetailsHandOff | undefined {
  if (
    !(error instanceof ApiProblem) ||
    (error.code !== 'VALIDATION_FAILED' && error.code !== 'MOBILE_NUMBER_REQUIRED')
  ) {
    return undefined;
  }
  const serverErrors = detailsErrors(fieldErrors(error));
  if (error.code === 'MOBILE_NUMBER_REQUIRED') {
    serverErrors.mobile ??= MOBILE_ERROR;
  }
  return Object.keys(serverErrors).length === 0
    ? undefined
    : { serverErrors, message: problemMessage(error) };
}

/** A refusal as this step shows it: the copy, and a link to where the customer can put it right. */
interface Refusal {
  message: ProblemMessage;
  action?: { to: string; label: string };
}

type Links = Pick<ReturnType<typeof useBookingParams>, 'hrefFor' | 'hrefForStep'>;

// Every refusal but SLOT_FULL, which has its own alert, and the detail errors that went back to the details step.
function refusalOf(error: unknown, { hrefFor, hrefForStep }: Links): Refusal {
  const message = problemMessage(error);
  if (!(error instanceof ApiProblem)) {
    return { message };
  }
  const anotherTime = { to: hrefFor({ slot: null, step: null }), label: 'Choose another time' };
  switch (error.code) {
    case 'DUPLICATE_APPOINTMENT':
      return { message, action: { to: '/manage', label: 'Find your appointment' } };
    case 'CUSTOMER_OVERLAP':
    case 'SLOT_OUTSIDE_HOURS':
    case 'SLOT_OFF_GRID':
    case 'SLOT_WITHIN_LEAD_TIME':
      return { message, action: anotherTime };
    case 'DATE_OUTSIDE_BOOKING_WINDOW':
    case 'SLOT_ON_CLOSED_DAY':
      return { message, action: { to: hrefFor({ date: null }), label: 'Choose another date' } };
    case 'BRANCH_NOT_FOUND':
      return { message, action: { to: hrefForStep('branch'), label: 'Choose a branch' } };
    case 'SERVICE_NOT_OFFERED_AT_BRANCH':
      return { message, action: { to: hrefForStep('service'), label: 'Choose a service' } };
    case 'TRY_AGAIN':
      return { message: withReference(message, error.correlationId) };
    case 'VALIDATION_FAILED':
    case 'MOBILE_NUMBER_REQUIRED': {
      // A refused detail has gone back to its field; what is left is the start, or a request the app built wrongly.
      const slotError = fieldErrors(error).slot;
      return slotError === undefined
        ? { message: withReference(problemMessage(undefined), error.correlationId) }
        : { message: { title: slotError }, action: anotherTime };
    }
    default:
      return { message };
  }
}

/** Props for {@link Fact}. */
interface FactProps {
  label: string;
  value: ReactNode;
  detail: ReactNode;
  /** The step its Edit link goes back to. */
  step: BookingStepId;
  /** What the Edit link edits, completing its accessible name: "Edit service". */
  editName: string;
  hrefForStep: (step: BookingStepId) => string;
}

// One fact of the summary: its label, the value, a line under it, and the Edit link.
function Fact({ label, value, detail, step, editName, hrefForStep }: FactProps) {
  return (
    <div className={styles.fact}>
      <dt className={styles.label}>{label}</dt>
      <dd className={styles.value}>{value}</dd>
      <dd className={styles.detail}>{detail}</dd>
      <dd className={styles.edit}>
        <Link to={hrefForStep(step)} className={styles.editLink} aria-label={`Edit ${editName}`}>
          Edit
        </Link>
      </dd>
    </div>
  );
}

/** The review step. */
export function ReviewStep() {
  const { branch, service, date, slot, setParams, hrefFor, hrefForStep } = useBookingParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  // Read once on arrival: the details change only on the details step, which this step replaces.
  const [details] = useState(loadDetails);
  const branchQuery = useBranch(branch);
  const book = useBookAppointment();

  const request = useMemo(
    () =>
      branch === undefined ||
      service === undefined ||
      date === undefined ||
      slot === undefined ||
      details === undefined
        ? undefined
        : bookingRequest({ branch, service, date, slot }, details),
    [branch, service, date, slot, details],
  );
  const idempotencyKey = useIdempotencyKey(request);

  // A refusal belongs to the request it answered: once another time is chosen, it no longer applies.
  const failure =
    book.error !== null && book.variables.request.startsAt === request?.startsAt
      ? book.error
      : undefined;
  const slotFull =
    failure instanceof ApiProblem && failure.code === 'SLOT_FULL' ? failure : undefined;
  const busy = book.isPending;
  // The synchronous guard against a second tap before React has re-rendered the button as busy.
  const sending = useRef(false);

  const confirmButton = useRef<HTMLButtonElement>(null);
  const refusalBox = useRef<HTMLDivElement>(null);
  const shownError = book.error;
  useEffect(() => {
    // Bring the answer into view, and to a screen reader's attention, from the button at the foot of the page.
    if (shownError !== null) {
      refusalBox.current?.focus();
    }
  }, [shownError]);

  const heading = (
    <PageHeading title="Check and confirm" docTitle={stepDocTitle('review')} focusKey="review" />
  );

  if (details === undefined) {
    return <Navigate to={hrefForStep('details')} replace />;
  }
  if (request === undefined || date === undefined || branchQuery.isPending) {
    return (
      <>
        {heading}
        <p className={styles.muted}>Loading your booking…</p>
      </>
    );
  }
  if (branchQuery.isError) {
    return (
      <>
        {heading}
        <ProblemAlert
          error={branchQuery.error}
          onRetry={() => {
            void branchQuery.refetch();
          }}
        />
      </>
    );
  }
  const branchDetail: BranchDetail = branchQuery.data;
  const serviceDetail: BranchService | undefined = branchDetail.services.find(
    (s) => s.code === service,
  );
  if (serviceDetail === undefined) {
    return <Navigate to={hrefForStep('service')} replace />;
  }

  const endsAt = addMinutes(request.startsAt, serviceDetail.durationMinutes);
  const mobile = parseSaMobile(details.mobile);
  const mobileShown = mobile.ok ? mobile.display : details.mobile;
  const alternatives = (slotFull?.problem?.alternatives ?? [])
    .filter((alternative) => sastDate(alternative.startsAt) === date)
    .slice(0, MAX_ALTERNATIVES);

  const confirm = () => {
    if (idempotencyKey === undefined || busy || slotFull !== undefined || sending.current) {
      return;
    }
    sending.current = true;
    book.mutate(
      { request, idempotencyKey },
      {
        onSuccess: ({ appointment }) => {
          clearBooking();
          void navigate(`/appointments/${appointment.reference}`, { replace: true });
        },
        onError: (error) => {
          // Only a refusal frees the button: after a booking the page is on its way to the confirmation.
          sending.current = false;
          if (!(error instanceof ApiProblem)) {
            return;
          }
          if (error.code === 'SLOT_FULL') {
            // The times shown on step 3 are out of date now; See all times fetches them again.
            void queryClient.invalidateQueries({ queryKey: queryKeys.allAvailability() });
            return;
          }
          const handOff = detailsHandOff(error);
          if (handOff !== undefined) {
            void navigate(hrefForStep('details'), { state: handOff });
          }
        },
      },
    );
  };

  const chooseAlternative = (alternative: Alternative) => {
    const changes: BookingParamChanges = { slot: formatTime(alternative.startsAt) };
    setParams(changes, { replace: true });
    // The alert and its buttons go; Confirm is the next thing to do.
    confirmButton.current?.focus();
  };

  let alert: ReactNode = null;
  if (slotFull !== undefined) {
    const message = problemMessage(slotFull);
    alert = (
      <Alert
        tone="error"
        title={message.title}
        actions={
          <div className={styles.slotActions}>
            {alternatives.length > 0 && (
              <div className={styles.alternatives}>
                {alternatives.map((alternative) => (
                  <Button
                    key={alternative.startsAt}
                    variant="secondary"
                    className="tabular"
                    aria-label={`${formatTime(alternative.startsAt)} SAST instead`}
                    onClick={() => {
                      chooseAlternative(alternative);
                    }}
                  >
                    {formatTime(alternative.startsAt)}
                  </Button>
                ))}
              </div>
            )}
            <Link to={hrefFor({ slot: null, step: null })} className={styles.actionLink}>
              See all times
            </Link>
          </div>
        }
      >
        The last seat at {formatTime(request.startsAt)} was booked while you were filling in your
        details. Your details are saved.{' '}
        {alternatives.length > 0
          ? `Choose another time on ${formatDateNoYear(date)}:`
          : 'Choose another time.'}
      </Alert>
    );
  } else if (failure !== undefined && detailsHandOff(failure) === undefined) {
    const { message, action } = refusalOf(failure, { hrefFor, hrefForStep });
    alert = (
      <Alert
        tone="error"
        title={message.title}
        actions={
          action === undefined ? undefined : (
            <Link to={action.to} className={styles.actionLink}>
              {action.label}
            </Link>
          )
        }
      >
        {message.body}
      </Alert>
    );
  }

  return (
    <>
      {heading}
      <div className={styles.body}>
        {alert !== null && (
          <div ref={refusalBox} tabIndex={-1}>
            {alert}
          </div>
        )}
        <Card as="section" shadow flush aria-label="Your appointment">
          <dl className={styles.facts}>
            <Fact
              label="Service"
              value={serviceDetail.name}
              detail={`About ${String(serviceDetail.durationMinutes)} minutes`}
              step="service"
              editName="service"
              hrefForStep={hrefForStep}
            />
            <Fact
              label="Branch"
              value={branchDetail.name}
              detail={addressLine(branchDetail.address)}
              step="branch"
              editName="branch"
              hrefForStep={hrefForStep}
            />
            <Fact
              label="Date and time"
              value={formatDateLong(date)}
              detail={
                <span className={styles.time}>
                  <span className="tabular">{formatTimeRange(request.startsAt, endsAt)}</span>
                  {slotFull !== undefined && (
                    <Badge tone="unavailable" size="label">
                      No longer free
                    </Badge>
                  )}
                </span>
              }
              step="datetime"
              editName="date and time"
              hrefForStep={hrefForStep}
            />
            <Fact
              label="Your details"
              value={`${request.customer.firstName} ${request.customer.lastName}`}
              detail={<span className="tabular">{mobileShown}</span>}
              step="details"
              editName="your details"
              hrefForStep={hrefForStep}
            />
          </dl>
        </Card>
        {serviceDetail.documentsNeeded.length > 0 && (
          <section className={styles.bring} aria-labelledby="review-bring">
            <h2 id="review-bring" className={styles.bringHeading}>
              What to bring
            </h2>
            <ul className={styles.bringList}>
              {serviceDetail.documentsNeeded.map((document) => (
                <li key={document}>{document}</li>
              ))}
            </ul>
            <p className={styles.bringNote}>Please arrive 10 minutes early.</p>
          </section>
        )}
        <div className={styles.confirm}>
          <Button
            ref={confirmButton}
            fullWidth
            aria-disabled={busy || slotFull !== undefined ? true : undefined}
            aria-describedby="review-confirm-note"
            onClick={confirm}
          >
            {busy ? 'Confirming…' : 'Confirm appointment'}
          </Button>
          <p id="review-confirm-note" className={styles.note}>
            {slotFull === undefined ? (
              <>
                We will SMS your confirmation to <span className="tabular">{mobileShown}</span>.
              </>
            ) : (
              'Choose a new time to confirm.'
            )}
          </p>
          <p role="status" className="visually-hidden">
            {busy ? 'Confirming your appointment.' : ''}
          </p>
        </div>
      </div>
    </>
  );
}
