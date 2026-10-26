/**
 * The API cache's settings, shared by every query in the app.
 *
 * - A 4xx is an answer, not an accident: retrying a refused lookup would spend one of the customer's five tries
 *   (BR-13), and retrying a 404 or 422 changes nothing. So a query retries once only for a network failure or a 5xx.
 * - Mutations never retry by themselves. Booking has its own single, idempotent retry (`useBookAppointment`).
 * - Refocusing the window does not refetch: the time step offers Refresh after ten minutes instead, so times do not
 *   change under the customer's finger.
 *
 * @packageDocumentation
 */
import { QueryClient } from '@tanstack/react-query';

import { ApiProblem } from '../api/client';

/** How long an answer is reused before a component that mounts asks again. */
export const DEFAULT_STALE_TIME_MS = 30_000;

/**
 * Whether a failed query is worth one more try.
 *
 * @param failureCount - how many times it has failed so far
 * @param error - the latest failure
 */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (failureCount >= 1) {
    return false;
  }
  if (error instanceof ApiProblem) {
    return error.status >= 500;
  }
  // fetch rejects with a TypeError when the network fails.
  return error instanceof TypeError;
}

/** Creates the cache the app's queries share. */
export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: shouldRetryQuery,
        refetchOnWindowFocus: false,
        staleTime: DEFAULT_STALE_TIME_MS,
      },
      mutations: {
        retry: false,
      },
    },
  });
}
