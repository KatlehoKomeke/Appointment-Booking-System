/**
 * The `Idempotency-Key` a booking is sent with.
 *
 * The API books once per key: sending the same key with the same details again returns the same appointment, and
 * sending it with different details is refused as `IDEMPOTENCY_KEY_REUSED` (docs/errors.md). So the key belongs to
 * one request. It is kept in session storage with a fingerprint of the request, which gives three behaviours:
 *
 * - a retry, a double tap or a refresh of the review step sends the same request with the same key, so a booking
 *   whose answer was lost is not made twice;
 * - any change to the branch, service, time or details gets a new key, so `IDEMPOTENCY_KEY_REUSED` never happens;
 * - `clearBooking()` drops it once the booking is made, so the next booking in the tab starts afresh.
 *
 * The fingerprint is a hash, so the stored record holds no name or number.
 *
 * @packageDocumentation
 */
import { useMemo } from 'react';

import type { components } from '../api/schema';
import { getJson, setJson } from '../lib/storage';
import { IDEMPOTENCY_STORAGE_KEY } from './bookingSession';

type BookingRequest = components['schemas']['BookingRequest'];

/**
 * Returns the idempotency key for a booking request, the same one for as long as the request stays the same.
 *
 * @param request - the request the review step would send, or `undefined` while it is incomplete
 * @returns a UUID, or `undefined` when there is no request yet
 */
export function useIdempotencyKey(request: BookingRequest | undefined): string | undefined {
  const fingerprint = request === undefined ? undefined : fingerprintOf(request);
  return useMemo(
    () => (fingerprint === undefined ? undefined : keyFor(fingerprint)),
    [fingerprint],
  );
}

// The stored key if it was minted for this fingerprint; otherwise a new key, stored in its place. Calling it twice
// for one fingerprint gives one key, so React may run it more than once.
function keyFor(fingerprint: string): string {
  const stored = getJson(IDEMPOTENCY_STORAGE_KEY);
  if (
    typeof stored === 'object' &&
    stored !== null &&
    'fingerprint' in stored &&
    stored.fingerprint === fingerprint &&
    'key' in stored &&
    typeof stored.key === 'string'
  ) {
    return stored.key;
  }
  const key = crypto.randomUUID();
  setJson(IDEMPOTENCY_STORAGE_KEY, { fingerprint, key });
  return key;
}

// A hash of every field the API compares, in a fixed order, so the same request always gives the same text.
function fingerprintOf({ branchCode, serviceCode, startsAt, customer }: BookingRequest): string {
  return hash(
    JSON.stringify([
      branchCode,
      serviceCode,
      startsAt,
      customer.firstName,
      customer.lastName,
      customer.mobile,
    ]),
  );
}

// cyrb53, a fast 53-bit string hash. It only has to tell one request from another in one tab, not resist an
// attacker, so a cryptographic hash (which the browser offers only asynchronously) is not needed.
function hash(text: string): string {
  let h1 = 0xdeadbeef;
  let h2 = 0x41c6ce57;
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    h1 = Math.imul(h1 ^ code, 2654435761);
    h2 = Math.imul(h2 ^ code, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return (4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(36);
}
