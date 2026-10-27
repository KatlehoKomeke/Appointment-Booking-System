import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';

import { lookupDigits, rememberedCancellation, rememberLookup } from '../booking/bookingSession';
import {
  appointment,
  CANCELLED_AT,
  notification,
  problem,
  SENT_NOTIFICATION,
} from '../test/fixtures';
import { notFoundResponse, problemResponse } from '../test/handlers';
import { createTestQueryClient } from '../test/render';
import { server } from '../test/server';
import { ApiProblem } from './client';
import { type InboxState, isLinkableReference, useInbox, useRetryMessage } from './inbox';
import {
  type BookingRequest,
  queryKeys,
  tryAgainDelayMs,
  useAppointment,
  useAvailability,
  useBookAppointment,
  useBranches,
  useCancelAppointment,
} from './queries';

const REQUEST: BookingRequest = {
  branchCode: 'SAN',
  serviceCode: 'FICA',
  startsAt: '2026-10-27T09:30:00+02:00',
  customer: { firstName: 'Nomvula', lastName: 'Dlamini', mobile: '082 555 0101' },
};

// Minted, not written out: the tests only need the same key twice, and a literal UUID beside "KEY" reads to the
// secret scanner as a key.
const KEY = crypto.randomUUID();

function renderWithCache<T>(hook: () => T) {
  const queryClient = createTestQueryClient();
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return { ...renderHook(hook, { wrapper }), queryClient };
}

describe('the read hooks', () => {
  it('lists the six seeded branches by name', async () => {
    const { result } = renderWithCache(() => useBranches());

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    expect(result.current.data?.map((b) => b.code)).toEqual([
      'BEL',
      'GQE',
      'POL',
      'RUS',
      'SAN',
      'UMH',
    ]);
  });

  it('asks for availability only once the branch, service and date are all chosen', async () => {
    const { result } = renderWithCache(() => useAvailability('SAN', 'FICA', undefined));
    expect(result.current.fetchStatus).toBe('idle');

    const { result: chosen } = renderWithCache(() => useAvailability('SAN', 'FICA', '2026-10-27'));
    await waitFor(() => {
      expect(chosen.current.isSuccess).toBe(true);
    });
    const slots = chosen.current.data?.slots ?? [];
    expect(slots).toHaveLength(36);
    expect(slots.filter((s) => s.seatsAvailable === 0)).toHaveLength(5);
    expect(slots.find((s) => s.startsAt === '2026-10-27T09:15:00+02:00')?.seatsAvailable).toBe(1);
  });

  it('passes a refusal on as an ApiProblem with its code', async () => {
    const { result } = renderWithCache(() => useAppointment('APT-7K3M-9Q2X', '9999'));

    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });
    expect(result.current.error).toBeInstanceOf(ApiProblem);
    expect(result.current.error).toMatchObject({ status: 404, code: 'APPOINTMENT_NOT_FOUND' });
  });
});

describe('useAppointment polling', () => {
  it('polls every 2 s while the SMS is PENDING and stops once it is sent', async () => {
    let calls = 0;
    server.use(
      http.get('*/api/v1/appointments/:reference', () => {
        calls += 1;
        return HttpResponse.json(
          appointment({ notifications: [calls === 1 ? notification() : SENT_NOTIFICATION] }),
        );
      }),
    );

    const { result } = renderWithCache(() =>
      useAppointment('APT-7K3M-9Q2X', '0101', { poll: true }),
    );

    await waitFor(
      () => {
        expect(result.current.data?.notifications[0]?.status).toBe('SENT');
      },
      { timeout: 3_500 },
    );
    expect(calls).toBe(2);
    await new Promise((resolve) => setTimeout(resolve, 2_300));
    expect(calls).toBe(2);
  }, 10_000);
});

describe('useBookAppointment', () => {
  it('retries a 503 TRY_AGAIN once, after Retry-After, with the same key, and seeds the cache', async () => {
    const keys: (string | null)[] = [];
    server.use(
      // The first try is refused; the retry falls through to the fake API, which books it.
      http.post('*/api/v1/appointments', ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'));
        return keys.length === 1
          ? problemResponse('TRY_AGAIN', {}, { 'Retry-After': '1' })
          : undefined;
      }),
    );
    const { result, queryClient } = renderWithCache(() => useBookAppointment());

    const started = Date.now();
    let booked: Awaited<ReturnType<typeof result.current.mutateAsync>> | undefined;
    await act(async () => {
      booked = await result.current.mutateAsync({ request: REQUEST, idempotencyKey: KEY });
    });

    expect(Date.now() - started).toBeGreaterThanOrEqual(950);
    expect(keys).toEqual([KEY, KEY]);
    expect(booked?.replayed).toBe(false);
    const reference = booked?.appointment.reference ?? '';
    expect(booked?.appointment.customer.mobileMasked).toBe('082 *** 0101');
    expect(queryClient.getQueryData(queryKeys.appointment(reference, '0101'))).toEqual(
      booked?.appointment,
    );
    expect(lookupDigits(reference)).toBe('0101');
  }, 10_000);

  it('gives up after the one retry, and never retries any other refusal', async () => {
    let calls = 0;
    server.use(
      http.post('*/api/v1/appointments', () => {
        calls += 1;
        return problemResponse('TRY_AGAIN', {}, { 'Retry-After': '0' });
      }),
    );
    const { result } = renderWithCache(() => useBookAppointment());

    await act(async () => {
      await expect(
        result.current.mutateAsync({ request: REQUEST, idempotencyKey: KEY }),
      ).rejects.toMatchObject({ status: 503, code: 'TRY_AGAIN' });
    });
    expect(calls).toBe(2);

    calls = 0;
    server.use(
      http.post('*/api/v1/appointments', () => {
        calls += 1;
        return problemResponse('SLOT_FULL');
      }),
    );
    await act(async () => {
      await expect(
        result.current.mutateAsync({ request: REQUEST, idempotencyKey: KEY }),
      ).rejects.toMatchObject({ code: 'SLOT_FULL' });
    });
    expect(calls).toBe(1);
  }, 10_000);

  it('reports a replay of the same key as replayed, with the same appointment', async () => {
    const { result } = renderWithCache(() => useBookAppointment());

    let first: Awaited<ReturnType<typeof result.current.mutateAsync>> | undefined;
    let second: typeof first;
    await act(async () => {
      first = await result.current.mutateAsync({ request: REQUEST, idempotencyKey: KEY });
      second = await result.current.mutateAsync({ request: REQUEST, idempotencyKey: KEY });
    });

    expect(first?.replayed).toBe(false);
    expect(second?.replayed).toBe(true);
    expect(second?.appointment.reference).toBe(first?.appointment.reference);
  });

  it('waits at most five seconds whatever Retry-After says', () => {
    const wait = (seconds: string | undefined) =>
      tryAgainDelayMs(
        new ApiProblem(
          new Response(null, {
            status: 503,
            headers: seconds === undefined ? {} : { 'Retry-After': seconds },
          }),
          problem('TRY_AGAIN'),
        ),
      );
    expect(wait('2')).toBe(2_000);
    expect(wait('3600')).toBe(5_000);
    expect(wait(undefined)).toBe(2_000);
  });
});

describe('useCancelAppointment', () => {
  it('sends the digits, retries a 503 once after Retry-After, and then forgets the appointment', async () => {
    const sent: { reference: string; digits: string | null }[] = [];
    server.use(
      // The first try is refused; the retry falls through to the fake API, which cancels it.
      http.post('*/api/v1/appointments/:reference/cancel', ({ params, request }) => {
        sent.push({
          reference: String(params.reference),
          digits: request.headers.get('X-Mobile-Last4'),
        });
        return sent.length === 1
          ? problemResponse('TRY_AGAIN', {}, { 'Retry-After': '0' })
          : undefined;
      }),
    );
    const { result, queryClient } = renderWithCache(() => useCancelAppointment());
    queryClient.setQueryData(queryKeys.appointment('apt-7k3m-9q2x', '0101'), appointment());
    queryClient.setQueryData(queryKeys.appointment('APT-7K3M-9Q2X', '0101'), appointment());
    rememberLookup('APT-7K3M-9Q2X', '0101');

    let answer: ReturnType<typeof appointment> | undefined;
    await act(async () => {
      answer = await result.current.mutateAsync({ reference: 'apt-7k3m-9q2x', last4: '0101' });
    });

    expect(sent).toEqual([
      { reference: 'APT-7K3M-9Q2X', digits: '0101' },
      { reference: 'APT-7K3M-9Q2X', digits: '0101' },
    ]);
    // The answer is the appointment as it was cancelled, with the cancellation SMS just queued.
    expect(answer).toMatchObject({ status: 'CANCELLED', canCancel: false });
    expect(answer?.notifications.find((n) => n.kind === 'CANCELLATION')?.status).toBe('PENDING');
    // The server has deleted it, so the cache and the tab let go of the name, the number and the digits.
    expect(
      queryClient.getQueryData(queryKeys.appointment('apt-7k3m-9q2x', '0101')),
    ).toBeUndefined();
    expect(
      queryClient.getQueryData(queryKeys.appointment('APT-7K3M-9Q2X', '0101')),
    ).toBeUndefined();
    expect(lookupDigits('APT-7K3M-9Q2X')).toBeUndefined();
    expect(rememberedCancellation('APT-7K3M-9Q2X')).toEqual({
      cancelledAt: CANCELLED_AT,
      branch: { code: 'SAN', name: 'Sandton Summit Mall' },
      service: { code: 'FICA', name: 'Update personal details (FICA)' },
      slot: { startsAt: '2026-10-27T09:30:00+02:00', endsAt: '2026-10-27T09:45:00+02:00' },
    });
  });

  it('finds nothing to cancel a second time, and never retries a refusal', async () => {
    const { result } = renderWithCache(() => useCancelAppointment());

    await act(async () => {
      await result.current.mutateAsync({ reference: 'APT-7K3M-9Q2X', last4: '0101' });
      // The first cancel deleted it.
      await expect(
        result.current.mutateAsync({ reference: 'APT-7K3M-9Q2X', last4: '0101' }),
      ).rejects.toMatchObject({ status: 404, code: 'APPOINTMENT_NOT_FOUND' });
    });

    let calls = 0;
    server.use(
      http.post('*/api/v1/appointments/:reference/cancel', () => {
        calls += 1;
        return problemResponse('TOO_LATE_TO_CANCEL');
      }),
    );
    await act(async () => {
      await expect(
        result.current.mutateAsync({ reference: 'APT-7K3M-9Q2X', last4: '0101' }),
      ).rejects.toMatchObject({ status: 409, code: 'TOO_LATE_TO_CANCEL' });
    });
    expect(calls).toBe(1);
  });
});

describe('useRetryMessage', () => {
  it('puts the answer in the cached inbox at once', async () => {
    const { result, queryClient } = renderWithCache(() => ({
      inbox: useInbox(),
      retry: useRetryMessage(),
    }));
    await waitFor(() => {
      expect(result.current.inbox.isSuccess).toBe(true);
    });

    await act(async () => {
      await result.current.retry.mutateAsync('40000000-0000-4000-8000-000000000001');
    });

    const state = queryClient.getQueryData<InboxState>(queryKeys.inbox());
    const retried =
      state?.available === true
        ? state.messages.find((m) => m.id === '40000000-0000-4000-8000-000000000001')
        : undefined;
    expect(retried).toMatchObject({ status: 'PENDING', attempts: 0, retryable: false });
  });
});

describe('isLinkableReference', () => {
  it('links a real reference only, never a redacted one', () => {
    expect(isLinkableReference('APT-7K3M-9Q2X')).toBe(true);
    expect(isLinkableReference('APT-****-****')).toBe(false);
    expect(isLinkableReference('APT-7K3M-****')).toBe(false);
    // O, I, L, 0 and 1 are not in the alphabet (BR-11).
    expect(isLinkableReference('APT-7K3M-9Q2O')).toBe(false);
    expect(isLinkableReference('apt-7k3m-9q2x')).toBe(false);
  });
});

describe('useInbox', () => {
  it('lists the messages newest first', async () => {
    const { result } = renderWithCache(() => useInbox());

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    const state = result.current.data;
    expect(state?.available).toBe(true);
    expect(state?.available === true && state.messages.map((m) => m.status)).toEqual([
      'PENDING',
      'SENT',
      'FAILED',
    ]);
  });

  it('turns the switched-off inbox, a plain 404, into a state of its own rather than an error', async () => {
    server.use(http.get('*/api/v1/messages', () => notFoundResponse()));
    const { result } = renderWithCache(() => useInbox());

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    expect(result.current.data).toEqual({ available: false });
  });
});
