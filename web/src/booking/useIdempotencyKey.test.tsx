import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { BookingRequest } from '../api/queries';
import { clearBooking } from './bookingSession';
import { useIdempotencyKey } from './useIdempotencyKey';

const REQUEST: BookingRequest = {
  branchCode: 'SAN',
  serviceCode: 'FICA',
  startsAt: '2026-10-27T09:30:00+02:00',
  customer: { firstName: 'Nomvula', lastName: 'Dlamini', mobile: '082 555 0101' },
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function render(request: BookingRequest | undefined) {
  return renderHook(({ value }) => useIdempotencyKey(value), {
    initialProps: { value: request },
  });
}

describe('useIdempotencyKey', () => {
  it('keeps one key for the same request across renders and a refresh', () => {
    const first = render(REQUEST);
    const key = first.result.current;
    expect(key).toMatch(UUID);

    first.rerender({ value: { ...REQUEST, customer: { ...REQUEST.customer } } });
    expect(first.result.current).toBe(key);

    // A refresh: a new page reads the same session storage.
    first.unmount();
    expect(render(REQUEST).result.current).toBe(key);
  });

  it('mints a new key when anything in the request changes, so the API never sees a key reused', () => {
    const { result, rerender } = render(REQUEST);
    const key = result.current;

    rerender({ value: { ...REQUEST, startsAt: '2026-10-27T09:45:00+02:00' } });
    const forNewTime = result.current;
    expect(forNewTime).toMatch(UUID);
    expect(forNewTime).not.toBe(key);

    rerender({ value: { ...REQUEST, customer: { ...REQUEST.customer, mobile: '082 555 0000' } } });
    expect(result.current).not.toBe(forNewTime);
  });

  it('starts afresh after clearBooking, and keeps no name or number in storage', () => {
    const { result, unmount } = render(REQUEST);
    const key = result.current;
    const stored = sessionStorage.getItem('branchbook.idempotency') ?? '';
    expect(stored).toContain(key ?? 'no key');
    expect(stored).not.toMatch(/Nomvula|Dlamini|555/);
    unmount();

    clearBooking();
    expect(render(REQUEST).result.current).not.toBe(key);
  });

  it('has no key until there is a request', () => {
    expect(render(undefined).result.current).toBeUndefined();
  });
});
