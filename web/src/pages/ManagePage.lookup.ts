/**
 * Opening an appointment with a reference and four digits the customer types: the form on /manage, and the
 * confirmation page when it opens in a tab that has not seen the appointment.
 *
 * A lookup is a mutation, not a query: it runs when the form is submitted, and each wrong try counts towards the
 * five that pause a reference (BR-13), so it is never repeated by itself, except once, quietly, for the one 429
 * that means "still checking" rather than "paused".
 *
 * @packageDocumentation
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';

import { api, ApiProblem, unwrap } from '../api/client';
import { type Appointment, queryKeys } from '../api/queries';
import { rememberLookup } from '../booking/bookingSession';

/** The 31 characters a reference is made of: no 0, 1, I, L or O, which are read back wrongly (BR-11). */
const REFERENCE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';

const COMPACT_REFERENCE = new RegExp(`^APT[${REFERENCE_ALPHABET}]{8}$`);

/** How long the API asks a lookup to wait while other lookups of the same reference are still being checked. */
const STILL_CHECKING_SECONDS = 1;

/**
 * A reference as typed, in the form the API and the SMS print it.
 *
 * @example `canonicalReference('apt 7k3m9q2x')` is "APT-7K3M-9Q2X"
 * @param input - the reference in any case, with or without spaces and hyphens
 * @returns `APT-XXXX-XXXX`, or `undefined` when the input cannot be a reference
 */
export function canonicalReference(input: string): string | undefined {
  const compact = input.replace(/[\s-]/g, '').toUpperCase();
  if (!COMPACT_REFERENCE.test(compact)) {
    return undefined;
  }
  return `${compact.slice(0, 3)}-${compact.slice(3, 7)}-${compact.slice(7)}`;
}

/** The last four digits field, as both lookup forms check it. */
export const last4Field = z
  .string()
  .trim()
  .regex(/^\d{4}$/, { error: 'Enter the last four digits of your cellphone number.' });

/** What {@link useLookupAppointment} sends. */
export interface LookupVariables {
  /** The reference, as the page has it. */
  reference: string;
  /** Four digits. */
  last4: string;
}

function fetchAppointment({ reference, last4 }: LookupVariables): Promise<Appointment> {
  return unwrap(
    api.GET('/api/v1/appointments/{reference}', {
      params: { path: { reference }, header: { 'X-Mobile-Last4': last4 } },
    }),
  );
}

function isStillChecking(error: unknown): boolean {
  return (
    error instanceof ApiProblem &&
    error.code === 'LOOKUP_RATE_LIMITED' &&
    error.retryAfterSeconds !== undefined &&
    error.retryAfterSeconds <= STILL_CHECKING_SECONDS
  );
}

/**
 * Looks an appointment up on demand. A wrong reference and wrong digits both fail with `APPOINTMENT_NOT_FOUND`;
 * a paused reference fails with `LOOKUP_RATE_LIMITED`. A 429 with `Retry-After: 1`, which only means other
 * lookups of the reference were still being checked, is tried once more after a second.
 *
 * On success the appointment is put in the cache under the reference the page asked with and under its own, and
 * its digits are remembered in the tab (`rememberLookup`), so its page opens at once and keeps polling.
 */
export function useLookupAppointment() {
  const queryClient = useQueryClient();
  return useMutation<Appointment, Error, LookupVariables>({
    mutationFn: async (variables) => {
      try {
        return await fetchAppointment(variables);
      } catch (error) {
        if (isStillChecking(error)) {
          await new Promise((resolve) => setTimeout(resolve, STILL_CHECKING_SECONDS * 1000));
          return fetchAppointment(variables);
        }
        throw error;
      }
    },
    onSuccess: (appointment, { reference, last4 }) => {
      queryClient.setQueryData(queryKeys.appointment(reference, last4), appointment);
      queryClient.setQueryData(queryKeys.appointment(appointment.reference, last4), appointment);
      rememberLookup(appointment.reference, last4);
    },
  });
}
