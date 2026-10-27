/**
 * What the booking keeps in the tab's session storage rather than the URL: the customer's name and number, which
 * must never reach a URL, a history entry or a server log (BR-19); the idempotency key (`useIdempotencyKey.ts`);
 * the last four digits that open an appointment the tab has just booked or looked up, so the confirmation page
 * can keep polling after a refresh; and, for an appointment the tab cancelled, which the server deletes as it
 * cancels it, only what the cancelled page shows again: when it was cancelled, and which service at which branch.
 * The cancellation keeps no name and no number, not even masked, and cancelling forgets the digits.
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

/** A branch or a service as the cancelled page names it and books it again. */
export interface NamedCode {
  /** Such as "SAN" or "FICA". */
  code: string;
  /** Such as "Sandton Summit Mall". */
  name: string;
}

/**
 * What the tab remembers of an appointment it cancelled, after the server has deleted it: enough to show the
 * cancelled page again and to book the same service again, and nothing that identifies the customer.
 */
export interface RememberedCancellation {
  /** When it was cancelled, as the API gave it, for example "2026-10-26T08:00:00+02:00". */
  cancelledAt: string | null;
  /** The branch it was at. */
  branch: NamedCode;
  /** The service it was for. */
  service: NamedCode;
  /** When it was for, at the branch's offset; neither time identifies the customer. `null` when not known. */
  slot: { startsAt: string; endsAt: string } | null;
}

const DETAILS_KEY = 'branchbook.details';
const LOOKUPS_KEY = 'branchbook.lookups';
const CANCELLATIONS_KEY = 'branchbook.cancellations';

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

/**
 * Forgets the digits remembered for an appointment. Cancelling calls it: the server deletes the appointment as it
 * cancels it, so the digits open nothing any more.
 *
 * @param reference - the appointment's reference, in any case, with or without hyphens
 */
export function forgetLookup(reference: string): void {
  const forgotten = normaliseReference(reference);
  setJson(
    LOOKUPS_KEY,
    Object.fromEntries(Object.entries(storedLookups()).filter(([key]) => key !== forgotten)),
  );
}

function isNamedCode(value: unknown): value is NamedCode {
  return (
    typeof value === 'object' &&
    value !== null &&
    'code' in value &&
    typeof value.code === 'string' &&
    'name' in value &&
    typeof value.name === 'string'
  );
}

function isCancellation(value: unknown): value is RememberedCancellation {
  return (
    typeof value === 'object' &&
    value !== null &&
    'cancelledAt' in value &&
    (value.cancelledAt === null || typeof value.cancelledAt === 'string') &&
    'branch' in value &&
    isNamedCode(value.branch) &&
    'service' in value &&
    isNamedCode(value.service) &&
    // A record kept before the slot was remembered has none, which reads as not known.
    (!('slot' in value) || value.slot === null || isSlot(value.slot))
  );
}

function isSlot(value: unknown): value is { startsAt: string; endsAt: string } {
  return (
    typeof value === 'object' &&
    value !== null &&
    'startsAt' in value &&
    typeof value.startsAt === 'string' &&
    'endsAt' in value &&
    typeof value.endsAt === 'string'
  );
}

// Only these fields are written or read back, whatever else the object given or found holds.
function cancellationFacts({
  cancelledAt,
  branch,
  service,
  slot,
}: Omit<RememberedCancellation, 'slot'> & {
  slot?: RememberedCancellation['slot'];
}): RememberedCancellation {
  return {
    cancelledAt,
    branch: { code: branch.code, name: branch.name },
    service: { code: service.code, name: service.name },
    slot: slot ? { startsAt: slot.startsAt, endsAt: slot.endsAt } : null,
  };
}

function storedCancellations(): Record<string, RememberedCancellation> {
  const stored = getJson(CANCELLATIONS_KEY);
  if (typeof stored !== 'object' || stored === null) {
    return {};
  }
  // Read back field by field, so a record kept before the slot was remembered reads as having none.
  return Object.fromEntries(
    Object.entries(stored)
      .filter((entry): entry is [string, RememberedCancellation] => isCancellation(entry[1]))
      .map(([reference, cancellation]) => [reference, cancellationFacts(cancellation)]),
  );
}

/**
 * Remembers that this tab cancelled an appointment, so its page can say so again after a refresh, once the server
 * has nothing left to show. Only the time and the branch's and service's codes and names are kept: an appointment
 * or a branch with more fields, such as an address, is stored without them.
 *
 * @param reference - the appointment's reference
 * @param cancellation - when it was cancelled, and the branch and service it was for
 */
export function rememberCancellation(
  reference: string,
  cancellation: RememberedCancellation,
): void {
  setJson(CANCELLATIONS_KEY, {
    ...storedCancellations(),
    [normaliseReference(reference)]: cancellationFacts(cancellation),
  });
}

/**
 * What this tab remembers of cancelling an appointment.
 *
 * @param reference - the appointment's reference, in any case, with or without hyphens
 * @returns the cancellation, or `undefined` when this tab has not cancelled the appointment
 */
export function rememberedCancellation(reference: string): RememberedCancellation | undefined {
  const stored = storedCancellations()[normaliseReference(reference)];
  return stored === undefined ? undefined : cancellationFacts(stored);
}
