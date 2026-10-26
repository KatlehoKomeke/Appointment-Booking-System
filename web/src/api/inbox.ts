/**
 * The simulated inbox: every message the outbox has produced, newest first (`GET /api/v1/messages`).
 *
 * It is a demo operator view, switched off in production, where the endpoint answers a plain 404 with no code.
 * {@link useInbox} turns that 404 into a state of its own, so the page can say the inbox is not available here
 * rather than show an error.
 *
 * @packageDocumentation
 */
import { useQuery } from '@tanstack/react-query';

import { api, ApiProblem, unwrap } from './client';
import { POLL_INTERVAL_MS, queryKeys } from './queries';
import type { components } from './schema';

/** One message in the inbox. Its recipient is always redacted, `082 *** ****`. */
export type InboxMessage = components['schemas']['InboxMessage'];

/** The inbox as {@link useInbox} returns it. */
export type InboxState =
  | {
      /** The inbox is switched on. */
      available: true;
      /** Newest first. */
      messages: InboxMessage[];
    }
  | {
      /** The server does not offer the inbox, as in production. */
      available: false;
    };

/** How many messages the page asks for: the API's default. */
export const INBOX_LIMIT = 50;

async function fetchInbox(): Promise<InboxState> {
  try {
    const { messages } = await unwrap(
      api.GET('/api/v1/messages', { params: { query: { limit: INBOX_LIMIT } } }),
    );
    return { available: true, messages };
  } catch (error) {
    // The switched-off inbox is a 404 with no code; a 404 with a code would be some other problem.
    if (error instanceof ApiProblem && error.status === 404 && error.code === undefined) {
      return { available: false };
    }
    throw error;
  }
}

/**
 * The simulated inbox, refetched every 2 s while any message is still PENDING, so a page shows it being sent.
 */
export function useInbox() {
  return useQuery({
    queryKey: queryKeys.inbox(),
    queryFn: fetchInbox,
    staleTime: 0,
    refetchInterval: (query) => {
      const state = query.state.data;
      return state?.available === true && state.messages.some((m) => m.status === 'PENDING')
        ? POLL_INTERVAL_MS
        : false;
    },
  });
}
