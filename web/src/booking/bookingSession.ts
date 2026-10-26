/**
 * What the booking keeps in the tab's session storage rather than the URL: the customer's name and number, which
 * must never reach a URL, a history entry or a server log (BR-19); the idempotency key (`useIdempotencyKey.ts`);
 * and the last four digits that open an appointment the tab has just booked or looked up, so the confirmation page
 * can keep polling after a refresh.
 *
 * Session storage ends with the tab, so nothing outlives the visit, and nothing here is sent anywhere.
 *
 * @packageDocumentation
 */
import { getJson, removeJson, setJson } from '../lib/storage';

/** The customer's details as the details step collects them. */
export interface CustomerDetails {
  /** As typed, for example "Nomvula". */
  firstName: string;
  /** As typed, for example "Dlamini". */
  lastName: string;
  /** As typed, for example "082 555 0101"; `parseSaMobile` reads it. */
  mobile: string;
}

const DETAILS_KEY = 'branchbook.details';
const LOOKUPS_KEY = 'branchbook.lookups';

/** The storage key of the idempotency key; `useIdempotencyKey` owns its contents. */
export const IDEMPOTENCY_STORAGE_KEY = 'branchbook.idempotency';

/**
 * Reads the details saved earlier in this tab.
 *
 * @returns the details, or `undefined` when none are saved or what is saved is not details
 */
export function loadDetails(): CustomerDetails | undefined {
  const stored = getJson(DETAILS_KEY);
  if (
    typeof stored === 'object' &&
    stored !== null &&
    'firstName' in stored &&
    typeof stored.firstName === 'string' &&
    'lastName' in stored &&
    typeof stored.lastName === 'string' &&
    'mobile' in stored &&
    typeof stored.mobile === 'string'
  ) {
    return { firstName: stored.firstName, lastName: stored.lastName, mobile: stored.mobile };
  }
  return undefined;
}

/**
 * Saves the details, so Back, Edit and a refresh keep them.
 *
 * @param details - the details as typed
 */
export function saveDetails(details: CustomerDetails): void {
  const { firstName, lastName, mobile } = details;
  setJson(DETAILS_KEY, { firstName, lastName, mobile });
}

/**
 * Forgets the booking in progress: the details and the idempotency key. Call it once the booking is made, so the
 * next booking in the tab starts empty and gets a new key. The remembered lookups stay, so the confirmation page
 * can still open the appointment.
 */
export function clearBooking(): void {
  removeJson(DETAILS_KEY);
  removeJson(IDEMPOTENCY_STORAGE_KEY);
}

// References are compared as the API compares them: case and hyphens do not matter.
function normaliseReference(reference: string): string {
  return reference.replace(/[\s-]/g, '').toUpperCase();
}

function storedLookups(): Record<string, string> {
  const stored = getJson(LOOKUPS_KEY);
  if (typeof stored !== 'object' || stored === null) {
    return {};
  }
  return Object.fromEntries(
    Object.entries(stored).filter(
      (entry): entry is [string, string] =>
        typeof entry[1] === 'string' && /^\d{4}$/.test(entry[1]),
    ),
  );
}

/**
 * Remembers the digits that opened an appointment, after booking it or after a successful lookup on /manage.
 *
 * @param reference - the appointment's reference
 * @param last4 - the last four digits of the number it was booked with
 */
export function rememberLookup(reference: string, last4: string): void {
  setJson(LOOKUPS_KEY, { ...storedLookups(), [normaliseReference(reference)]: last4 });
}

/**
 * The digits remembered for an appointment, so its page can open it without asking again.
 *
 * @param reference - the appointment's reference, in any case, with or without hyphens
 * @returns the four digits, or `undefined` when this tab has not opened the appointment
 */
export function lookupDigits(reference: string): string | undefined {
  return storedLookups()[normaliseReference(reference)];
}
