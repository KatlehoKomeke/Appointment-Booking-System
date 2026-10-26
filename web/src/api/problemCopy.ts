/**
 * What the customer reads when a call fails, chosen by the problem's `code` and never from its `detail`, which is
 * written for developers (docs/errors.md, "Codes"). The wording here is the "What the customer sees" column of that
 * table, completed with what to do next. None of it reveals another booking.
 *
 * @packageDocumentation
 */
import { ApiProblem, type Problem } from './client';

/** A failure as the customer reads it: a short title, and a sentence on what to do. */
export interface ProblemMessage {
  /** The alert's title, for example "That time was just taken". */
  title: string;
  /** What to do next, or what still holds. */
  body?: string;
}

/** Every code the API returns on purpose. */
export type ProblemCode = NonNullable<Problem['code']>;

// The copy for a failure the customer cannot fix, with the id support needs.
function somethingWentWrong(correlationId: string | undefined): ProblemMessage {
  return {
    title: 'Something went wrong on our side',
    body:
      correlationId === undefined
        ? 'Please try again in a few minutes.'
        : `Please try again in a few minutes. If it keeps happening, quote reference ${correlationId}.`,
  };
}

/**
 * The copy for each code. A function, so a code that only a bug in the app could cause (a missing or reused
 * idempotency key) still shows the generic copy with the correlation id to quote.
 */
const COPY: Record<ProblemCode, (correlationId: string | undefined) => ProblemMessage> = {
  VALIDATION_FAILED: () => ({
    title: 'Some details need checking',
    body: 'Check the fields marked below, then try again.',
  }),
  IDEMPOTENCY_KEY_REQUIRED: somethingWentWrong,
  BRANCH_NOT_FOUND: () => ({
    title: "We couldn't find that branch.",
    body: 'Choose a branch from the list.',
  }),
  APPOINTMENT_NOT_FOUND: () => ({
    title: "We couldn't find an appointment with those details.",
    body: 'Check the reference and the last four digits, then try again.',
  }),
  MESSAGE_NOT_FOUND: () => ({
    title: "We couldn't find that message.",
    body: 'Refresh the inbox and try again.',
  }),
  SERVICE_NOT_OFFERED_AT_BRANCH: () => ({
    title: "This branch doesn't offer that service.",
    body: 'Choose another service, or another branch.',
  }),
  DATE_OUTSIDE_BOOKING_WINDOW: () => ({
    title: "That date can't be booked",
    body: 'Choose one of the dates shown. Bookings open up to 30 days ahead.',
  }),
  SLOT_ON_CLOSED_DAY: () => ({
    title: 'The branch is closed that day',
    body: 'Choose another date.',
  }),
  SLOT_OUTSIDE_HOURS: () => ({
    title: "That time isn't available",
    body: 'Choose one of the times shown.',
  }),
  SLOT_OFF_GRID: () => ({
    title: "That time isn't available",
    body: 'Choose one of the times shown.',
  }),
  SLOT_WITHIN_LEAD_TIME: () => ({
    title: 'That time is too soon.',
    body: 'Please choose a later slot.',
  }),
  MOBILE_NUMBER_REQUIRED: () => ({
    title: 'We need a cellphone number to SMS your confirmation.',
    body: 'Enter a South African cellphone number, for example 082 555 0101.',
  }),
  IDEMPOTENCY_KEY_REUSED: somethingWentWrong,
  DUPLICATE_APPOINTMENT: () => ({
    title: 'You already have an upcoming appointment for this service.',
    body: 'You can find it with the reference from your SMS.',
  }),
  CUSTOMER_OVERLAP: () => ({
    title: 'You already have an appointment at that time.',
    body: 'Choose another time.',
  }),
  SLOT_FULL: () => ({
    title: 'That time was just taken',
    body: 'The last seat was booked while you were filling in your details. Your details are saved. Choose another time.',
  }),
  TOO_LATE_TO_CANCEL: () => ({
    title: 'This appointment has already started.',
    body: 'It can no longer be cancelled.',
  }),
  LOOKUP_RATE_LIMITED: () => ({
    title: 'Too many attempts. Try again later.',
    body: 'After 5 wrong tries, lookups for that reference pause for up to an hour, even with the right digits.',
  }),
  INTERNAL_ERROR: somethingWentWrong,
  TRY_AGAIN: () => ({
    title: "We couldn't finish that just now",
    body: 'Nothing was booked twice. Please try again in a moment.',
  }),
};

/** The copy when the request never reached the API. */
const OFFLINE: ProblemMessage = {
  title: "We couldn't reach BranchBook",
  body: 'Check your internet connection, then try again.',
};

/**
 * The copy for any error a call can throw: an {@link ApiProblem} by its code, a network failure as being offline,
 * and anything else, a 5xx without a code included, as a failure on our side with the correlation id to quote.
 *
 * @param error - what the call threw, as TanStack Query passes it on
 */
export function problemMessage(error: unknown): ProblemMessage {
  if (error instanceof ApiProblem) {
    if (error.code !== undefined) {
      return COPY[error.code](error.correlationId);
    }
    return somethingWentWrong(error.correlationId);
  }
  // fetch rejects with a TypeError when the network fails: offline, DNS, a dropped connection.
  if (error instanceof TypeError) {
    return OFFLINE;
  }
  return somethingWentWrong(undefined);
}

/** The form fields a problem's `errors[]` can name, by the name the API gives them. */
const FIELD_NAMES: Readonly<Record<string, BookingFormField>> = {
  'customer.firstName': 'firstName',
  'customer.lastName': 'lastName',
  'customer.mobile': 'mobile',
  startsAt: 'slot',
  'X-Mobile-Last4': 'last4',
};

/** The fields of the details form and the lookup form that a server error can be shown on. */
export type BookingFormField = 'firstName' | 'lastName' | 'mobile' | 'slot' | 'last4';

// A problem body passed directly, recognised by its `errors` list; anything else has no field errors.
function asProblem(value: unknown): Pick<Problem, 'errors'> | undefined {
  return typeof value === 'object' &&
    value !== null &&
    'errors' in value &&
    Array.isArray(value.errors)
    ? (value as Pick<Problem, 'errors'>)
    : undefined;
}

// The customer's copy for one field's rule, as the details and lookup forms word them.
function fieldCopy(field: BookingFormField, code: string): string {
  switch (field) {
    case 'firstName':
      return code === 'NAME_INVALID'
        ? 'Enter your first name using letters, spaces, hyphens or apostrophes.'
        : 'Enter your first name.';
    case 'lastName':
      return code === 'NAME_INVALID'
        ? 'Enter your last name using letters, spaces, hyphens or apostrophes.'
        : 'Enter your last name.';
    case 'mobile':
      return 'We need a cellphone number to SMS your confirmation.';
    case 'slot':
      return code === 'SLOT_WITHIN_LEAD_TIME'
        ? 'That time is too soon. Please choose a later slot.'
        : 'That time is not available. Choose another time.';
    case 'last4':
      return 'Enter the last four digits of your cellphone number.';
  }
}

/**
 * Maps a problem's field errors onto the form's fields, with the customer's wording rather than the API's hint.
 *
 * @example a 400 naming `customer.firstName` with `NAME_INVALID` gives
 *   `{ firstName: 'Enter your first name using letters, spaces, hyphens or apostrophes.' }`
 * @param problem - the error a call threw, or its problem body
 * @returns the message for each field named, the first one winning where a field is named twice; empty when none
 */
export function fieldErrors(problem: unknown): Partial<Record<BookingFormField, string>> {
  const body = problem instanceof ApiProblem ? problem.problem : asProblem(problem);
  const result: Partial<Record<BookingFormField, string>> = {};
  for (const item of body?.errors ?? []) {
    const field = item.field === null ? undefined : FIELD_NAMES[item.field];
    if (field !== undefined && result[field] === undefined) {
      result[field] = fieldCopy(field, item.code);
    }
  }
  return result;
}
