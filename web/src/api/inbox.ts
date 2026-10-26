/**
 * The simulated inbox: every message the outbox has produced, newest first (`GET /api/v1/messages`), and retrying a
 * message the dispatcher gave up on (`POST /api/v1/messages/{id}/retry`).
 *
 * It is a demo operator view, switched off in production, where both endpoints answer a plain 404 with no code.
 * {@link useInbox} turns that 404 into a state of its own, so the page can say the inbox is not available here
 * rather than show an error.
 *
 * @packageDocumentation
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

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

/** A whole reference, `APT-` and two groups of four from the 31-character alphabet (BR-11). */
const REFERENCE = /^APT-[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}$/;

/**
 * Tells whether an inbox message's reference is a real one that can be linked to, rather than one the server has
 * redacted, such as `APT-****-****`.
 *
 * @param reference - the message's reference, as the inbox gives it
 */
export function isLinkableReference(reference: string): boolean {
  return !reference.includes('*') && REFERENCE.test(reference);
}

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

/**
 * Asks the dispatcher to try a FAILED message again. The API answers with the message: reset to PENDING, or
 * unchanged with `retryable` false when it may no longer be retried, such as the confirmation of an appointment that
 * was cancelled or has started.
 *
 * The answer replaces the message in the cached inbox at once, and the inbox is then fetched again, so its polling
 * picks up the message's progress; a failure refreshes it too, since the message, or the inbox itself, may be gone.
 */
export function useRetryMessage() {
  const queryClient = useQueryClient();
  return useMutation<InboxMessage, Error, string>({
    mutationFn: (id) =>
      unwrap(api.POST('/api/v1/messages/{id}/retry', { params: { path: { id } } })),
    onSuccess: (message) => {
      queryClient.setQueryData<InboxState>(queryKeys.inbox(), (state) =>
        state?.available === true
          ? { ...state, messages: state.messages.map((m) => (m.id === message.id ? message : m)) }
          : state,
      );
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.inbox() });
    },
  });
}
