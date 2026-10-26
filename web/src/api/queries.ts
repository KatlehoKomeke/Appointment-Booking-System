/**
 * The app's API calls as TanStack Query hooks, over the typed client in `client.ts`.
 *
 * Every hook throws {@link ApiProblem} for an error response and the browser's `TypeError` when the network fails,
 * so a screen can pass `error` straight to `problemMessage`. Queries are cached by {@link queryKeys}; the booking
 * mutation seeds the appointment's cache, so the confirmation page opens without a second request.
 *
 * @packageDocumentation
 */
import { type QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { rememberLookup } from '../booking/bookingSession';
import { last4 as lastFourDigits } from '../lib/mobile';
import { api, ApiProblem, isIdempotentReplay, unwrap, unwrapWithResponse } from './client';
import type { components } from './schema';

/** A branch in the list on the first step. */
export type BranchSummary = components['schemas']['BranchSummary'];
/** One branch in full: address, weekly hours, services, the bookable dates and the closed days among them. */
export type BranchDetail = components['schemas']['BranchDetail'];
/** A service a branch offers. */
export type BranchService = components['schemas']['BranchService'];
/** One day's slots for one service at one branch. Full slots are listed with `seatsAvailable` 0. */
export type Availability = components['schemas']['Availability'];
/** One slot of a day. */
export type Slot = components['schemas']['Slot'];
/** What the review step sends to book. */
export type BookingRequest = components['schemas']['BookingRequest'];
/** A booked appointment, with its messages. */
export type Appointment = components['schemas']['Appointment'];
/** One message about an appointment, such as the confirmation SMS. */
export type Notification = components['schemas']['Notification'];

/** How often a page polls while a message is still being sent: the dispatcher's own tick (BR-18). */
export const POLL_INTERVAL_MS = 2_000;

/** The longest the booking waits on `Retry-After` before its one retry, whatever the server asks. */
export const MAX_RETRY_WAIT_MS = 5_000;

/** The cache keys, so a screen can invalidate or prefill exactly what a hook reads. */
export const queryKeys = {
  /** The branch list. */
  branches: () => ['branches'] as const,
  /**
   * One branch's detail.
   *
   * @param code - the branch code
   */
  branch: (code: string) => ['branches', code] as const,
  /** Every availability answer, to refresh all of them after a slot is taken. */
  allAvailability: () => ['availability'] as const,
  /**
   * One day's slots for one service at one branch.
   *
   * @param code - the branch code
   * @param service - the service code
   * @param date - `YYYY-MM-DD`
   */
  availability: (code: string, service: string, date: string) =>
    ['availability', code, service, date] as const,
  /**
   * An appointment as opened with the given digits.
   *
   * @param reference - the reference, in any case
   * @param last4 - the digits it was opened with
   */
  appointment: (reference: string, last4: string) =>
    ['appointment', normaliseReference(reference), last4] as const,
  /** The simulated inbox. */
  inbox: () => ['messages'] as const,
};

// The API ignores case in a reference, so the cache does too.
function normaliseReference(reference: string): string {
  return reference.trim().toUpperCase();
}

function present(value: string | null | undefined): value is string {
  return value !== undefined && value !== null && value !== '';
}

/** The active branches, ordered by name. */
export function useBranches() {
  return useQuery({
    queryKey: queryKeys.branches(),
    queryFn: async () => (await unwrap(api.GET('/api/v1/branches'))).branches,
    // The catalogue changes only with a migration.
    staleTime: 5 * 60_000,
  });
}

/**
 * One branch in full. Disabled until a code is given.
 *
 * @param code - the branch code, such as `SAN`
 */
export function useBranch(code: string | null | undefined) {
  return useQuery({
    queryKey: queryKeys.branch(code ?? ''),
    queryFn: () =>
      unwrap(api.GET('/api/v1/branches/{code}', { params: { path: { code: code ?? '' } } })),
    enabled: present(code),
    staleTime: 5 * 60_000,
  });
}

/**
 * One day's slots. Disabled until the branch, the service and the date are all given.
 *
 * @param code - the branch code
 * @param service - the service code
 * @param date - `YYYY-MM-DD`
 */
export function useAvailability(
  code: string | null | undefined,
  service: string | null | undefined,
  date: string | null | undefined,
) {
  return useQuery({
    queryKey: queryKeys.availability(code ?? '', service ?? '', date ?? ''),
    queryFn: () =>
      unwrap(
        api.GET('/api/v1/branches/{code}/availability', {
          params: {
            path: { code: code ?? '' },
            query: { service: service ?? '', date: date ?? '' },
          },
        }),
      ),
    enabled: present(code) && present(service) && present(date),
  });
}

/** Options for {@link useAppointment}. */
export interface UseAppointmentOptions {
  /** Refetch every 2 s while any message is still PENDING, so a page shows the SMS being sent. */
  poll?: boolean;
}

/**
 * Tells whether any of an appointment's messages is still waiting to be sent.
 *
 * @param appointment - the appointment, or nothing yet
 */
export function hasPendingNotification(appointment: Appointment | undefined): boolean {
  return appointment?.notifications.some((n) => n.status === 'PENDING') ?? false;
}

/**
 * Opens an appointment with its reference and the last four digits of the number it was booked with. Disabled
 * until both are given. A wrong reference and wrong digits both fail with `APPOINTMENT_NOT_FOUND`.
 *
 * @param reference - such as `APT-7K3M-9Q2X`; case and hyphens do not matter
 * @param last4 - four digits
 * @param options - whether to poll while a message is pending
 */
export function useAppointment(
  reference: string | null | undefined,
  last4: string | null | undefined,
  options: UseAppointmentOptions = {},
) {
  const poll = options.poll ?? false;
  return useQuery({
    queryKey: queryKeys.appointment(reference ?? '', last4 ?? ''),
    queryFn: () =>
      unwrap(
        api.GET('/api/v1/appointments/{reference}', {
          params: {
            path: { reference: normaliseReference(reference ?? '') },
            header: { 'X-Mobile-Last4': last4 ?? '' },
          },
        }),
      ),
    enabled: present(reference) && present(last4),
    refetchInterval: (query) =>
      poll && hasPendingNotification(query.state.data) ? POLL_INTERVAL_MS : false,
  });
}

/** What {@link useBookAppointment} sends. */
export interface BookAppointmentVariables {
  /** The booking. */
  request: BookingRequest;
  /** The key from `useIdempotencyKey`, the same on every retry of this request. */
  idempotencyKey: string;
}

/** What {@link useBookAppointment} returns. */
export interface BookAppointmentResult {
  /** The appointment, booked now or by an earlier request with the same key. */
  appointment: Appointment;
  /** True when an earlier request with this key had already booked it. */
  replayed: boolean;
}

/**
 * How long to wait before retrying a `503 TRY_AGAIN`: the server's `Retry-After`, at most five seconds, so the
 * customer is not left waiting on the review step. Two seconds when the header is missing.
 *
 * @param problem - the 503
 */
export function tryAgainDelayMs(problem: ApiProblem): number {
  return Math.min((problem.retryAfterSeconds ?? 2) * 1000, MAX_RETRY_WAIT_MS);
}

async function book({ request, idempotencyKey }: BookAppointmentVariables) {
  const { data, response } = await unwrapWithResponse(
    api.POST('/api/v1/appointments', {
      params: { header: { 'Idempotency-Key': idempotencyKey } },
      body: request,
    }),
  );
  return { appointment: data, replayed: isIdempotentReplay(response) };
}

/**
 * Seeds the appointment's cache and remembers its digits, so the confirmation page shows it at once and can poll.
 *
 * @param queryClient - the app's cache
 * @param appointment - the booked appointment
 * @param mobile - the number it was booked with, in any form
 */
function remember(queryClient: QueryClient, appointment: Appointment, mobile: string) {
  const digits = lastFourDigits(mobile);
  queryClient.setQueryData(queryKeys.appointment(appointment.reference, digits), appointment);
  rememberLookup(appointment.reference, digits);
}

/**
 * Books an appointment.
 *
 * A `503 TRY_AGAIN` (a lock or a database that is briefly busy) is retried once, after the server's `Retry-After`
 * capped at five seconds, with the same idempotency key, so the retry can never book twice. Any other failure, and a
 * second 503, is the caller's to show. On success the appointment's cache is seeded and its digits remembered in the
 * tab (`rememberLookup`), and every cached availability is marked stale, since a seat was just taken.
 */
export function useBookAppointment() {
  const queryClient = useQueryClient();
  return useMutation<BookAppointmentResult, Error, BookAppointmentVariables>({
    mutationFn: async (variables) => {
      try {
        return await book(variables);
      } catch (error) {
        if (error instanceof ApiProblem && error.status === 503) {
          await new Promise((resolve) => setTimeout(resolve, tryAgainDelayMs(error)));
          return book(variables);
        }
        throw error;
      }
    },
    onSuccess: ({ appointment }, { request }) => {
      remember(queryClient, appointment, request.customer.mobile);
      void queryClient.invalidateQueries({ queryKey: queryKeys.allAvailability() });
    },
  });
}
