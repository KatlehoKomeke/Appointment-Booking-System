/**
 * The rules of the details form, shared by the details step, which checks them as the customer types, and the review
 * step, which sends what they allow and hands the server's field errors back.
 *
 * The rules repeat the API's, so a value the form accepts is not refused at the last step: a name is read as
 * `CustomerName.parse` reads it (NAME_INVALID in docs/errors.md), and a number as `parseSaMobile` reads it (BR-19). The
 * server remains the authority; when it disagrees, the review step sends the customer back here with its errors.
 *
 * @packageDocumentation
 */
import { z } from 'zod';

import type { BookingFormField, ProblemMessage } from '../api/problemCopy';
import { parseSaMobile } from '../lib/mobile';

/** The longest name the API accepts, in characters. */
export const NAME_MAX_LENGTH = 60;

/** The error under the cellphone field, whatever is wrong with the number (README "Copy"). */
export const MOBILE_ERROR = 'We need a cellphone number to SMS your confirmation.';

// The API's pattern: a letter first, then letters, combining marks, spaces, hyphens and apostrophes.
const NAME_PATTERN = /^\p{L}[\p{L}\p{M} '-]*$/u;

/**
 * A name as the API stores it: composed (NFC), curly apostrophes made straight, every run of spaces one ordinary
 * space, and no spaces at either end.
 *
 * @example `normaliseName('  Mary-Jane  O’Brien ')` is "Mary-Jane O'Brien"
 * @param value - the name as typed
 */
export function normaliseName(value: string): string {
  return value
    .normalize('NFC')
    .replace(/[‘’ʼ]/g, "'")
    .replace(/\p{Zs}+/gu, ' ')
    .replace(/^ +| +$/g, '');
}

// The length as the API counts it (Java's codePointCount): a character outside the Basic Multilingual Plane is one,
// not the two UTF-16 units of `length`.
function codePoints(text: string): number {
  return text.length - (text.match(/[\uD800-\uDBFF][\uDC00-\uDFFF]/g)?.length ?? 0);
}

// The rules for one name field, in the order the customer meets them.
function nameField(which: 'first' | 'last') {
  return z.string().superRefine((value, context) => {
    const name = normaliseName(value);
    if (name === '') {
      context.addIssue({ code: 'custom', message: `Enter your ${which} name.` });
    } else if (codePoints(name) > NAME_MAX_LENGTH) {
      context.addIssue({
        code: 'custom',
        message: `Enter your ${which} name in ${String(NAME_MAX_LENGTH)} characters or fewer.`,
      });
    } else if (!NAME_PATTERN.test(name)) {
      context.addIssue({
        code: 'custom',
        message: `Enter your ${which} name using letters, spaces, hyphens or apostrophes.`,
      });
    }
  });
}

/** The details form's schema: two names and a South African cellphone number. */
export const detailsSchema = z.object({
  firstName: nameField('first'),
  lastName: nameField('last'),
  mobile: z.string().refine((value) => parseSaMobile(value).ok, { error: MOBILE_ERROR }),
});

/** The details form's values, as typed. */
export type DetailsFormValues = z.infer<typeof detailsSchema>;

/** The fields of the details form. */
export type DetailsField = keyof DetailsFormValues;

/** The server's errors for the details form's fields, in the customer's words. */
export type DetailsServerErrors = Partial<Record<DetailsField, string>>;

const DETAILS_FIELDS: readonly DetailsField[] = ['firstName', 'lastName', 'mobile'];

/**
 * Keeps the errors that belong on the details form, out of `fieldErrors(problem)`.
 *
 * @param errors - the errors for every form field the problem named
 * @returns the details form's errors; empty when the problem named none of its fields
 */
export function detailsErrors(
  errors: Partial<Record<BookingFormField, string>>,
): DetailsServerErrors {
  const result: DetailsServerErrors = {};
  for (const field of DETAILS_FIELDS) {
    const message = errors[field];
    if (message !== undefined) {
      result[field] = message;
    }
  }
  return result;
}

/**
 * What the review step puts in the router's state when the server refuses a detail, so the details step shows the
 * error on its field. Router state, unlike the URL, never holds the details themselves; it holds only the copy.
 */
export interface DetailsHandOff {
  /** The error for each refused field. */
  serverErrors: DetailsServerErrors;
  /** The alert above the fields: `problemMessage` of the refusal. */
  message: ProblemMessage;
}

function isText(value: unknown): value is string {
  return typeof value === 'string' && value !== '';
}

/**
 * Reads the review step's hand-off from the router's state, which may hold anything a history entry was given.
 *
 * @param state - `useLocation().state`
 * @returns the hand-off, or `undefined` when the state holds no field errors
 */
export function readHandOff(state: unknown): DetailsHandOff | undefined {
  if (
    typeof state !== 'object' ||
    state === null ||
    !('serverErrors' in state) ||
    !('message' in state)
  ) {
    return undefined;
  }
  const { serverErrors: errors, message } = state as Record<string, unknown>;
  if (
    typeof errors !== 'object' ||
    errors === null ||
    typeof message !== 'object' ||
    message === null
  ) {
    return undefined;
  }
  const serverErrors: DetailsServerErrors = {};
  for (const field of DETAILS_FIELDS) {
    const text: unknown = (errors as Record<string, unknown>)[field];
    if (isText(text)) {
      serverErrors[field] = text;
    }
  }
  const { title, body } = message as Record<string, unknown>;
  if (Object.keys(serverErrors).length === 0 || !isText(title)) {
    return undefined;
  }
  return { serverErrors, message: isText(body) ? { title, body } : { title } };
}
