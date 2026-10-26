import { act } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../test/render';

import {
  applyBookingChanges,
  type BookingParams,
  readBookingParams,
  STEP_LABEL,
  STEP_NUMBER,
  useBookingParams,
} from './bookingParams';

function stepOf(query: string) {
  return readBookingParams(new URLSearchParams(query)).step;
}

function apply(query: string, changes: Parameters<typeof applyBookingChanges>[1]) {
  return applyBookingChanges(new URLSearchParams(query), changes).toString();
}

describe('the booking step, derived from the URL', () => {
  it('opens the first step still missing an answer', () => {
    expect(stepOf('')).toBe('branch');
    expect(stepOf('service=FICA')).toBe('branch');
    expect(stepOf('branch=SAN')).toBe('service');
    expect(stepOf('branch=SAN&service=FICA')).toBe('datetime');
    expect(stepOf('branch=SAN&service=FICA&date=2026-10-27')).toBe('datetime');
    expect(stepOf('branch=SAN&service=FICA&date=2026-10-27&slot=09:30')).toBe('datetime');
    expect(stepOf('branch=SAN&service=FICA&date=2026-10-27&slot=09:30&step=details')).toBe(
      'details',
    );
    expect(stepOf('branch=SAN&service=FICA&date=2026-10-27&slot=09:30&step=review')).toBe('review');
  });

  it('does not let a URL skip ahead or carry a malformed choice', () => {
    expect(stepOf('branch=SAN&service=FICA&step=review')).toBe('datetime');
    expect(stepOf('branch=SAN&service=FICA&date=27-10-2026&slot=09:30&step=details')).toBe(
      'datetime',
    );
    expect(stepOf('branch=SAN&service=FICA&date=2026-10-27&slot=9.30&step=details')).toBe(
      'datetime',
    );
    expect(stepOf('branch=SAN&service=FICA&date=2026-10-27&slot=09:30&step=pay')).toBe('datetime');
  });

  it('numbers and names the five steps', () => {
    expect(STEP_NUMBER.datetime).toBe(3);
    expect(Object.values(STEP_LABEL)).toEqual([
      'Branch',
      'Service',
      'Date and time',
      'Your details',
      'Review',
    ]);
  });
});

describe('changing a choice', () => {
  const full = 'branch=SAN&service=FICA&date=2026-10-27&slot=09:30&step=review';

  it('clears what depends on it', () => {
    expect(apply(full, { branch: 'UMH' })).toBe('branch=UMH');
    expect(apply(full, { service: 'CARD' })).toBe('branch=SAN&service=CARD');
    expect(apply(full, { date: '2026-10-28' })).toBe('branch=SAN&service=FICA&date=2026-10-28');
  });

  it('keeps the step for a new time, so an alternative chosen on review stays on review', () => {
    expect(apply(full, { slot: '09:45' })).toBe(
      'branch=SAN&service=FICA&date=2026-10-27&slot=09%3A45&step=review',
    );
  });

  it('keeps everything when the choice is the same, and what the same change sets', () => {
    expect(apply(full, { branch: 'SAN' })).toBe(new URLSearchParams(full).toString());
    expect(apply('branch=SAN&service=FICA', { date: '2026-10-27', slot: '09:30' })).toBe(
      'branch=SAN&service=FICA&date=2026-10-27&slot=09%3A30',
    );
  });
});

describe('useBookingParams', () => {
  function renderAt(path: string) {
    const probe: { current?: BookingParams } = {};
    function Probe() {
      probe.current = useBookingParams();
      return null;
    }
    const { router } = renderWithProviders(<Probe />, { path });
    return {
      router,
      params: () => {
        if (probe.current === undefined) {
          throw new Error('not rendered');
        }
        return probe.current;
      },
    };
  }

  it('pushes a history entry for each step, so Back returns to the last one', async () => {
    const { router, params } = renderAt('/book?branch=SAN&service=FICA&date=2026-10-27&slot=09:30');
    expect(params().step).toBe('datetime');

    act(() => {
      params().goToStep('details');
    });
    expect(params().step).toBe('details');
    expect(router.state.historyAction).toBe('PUSH');
    expect(params().hrefForStep('datetime')).toBe(
      '/book?branch=SAN&service=FICA&date=2026-10-27&slot=09%3A30',
    );

    await act(() => router.navigate(-1));
    expect(params().step).toBe('datetime');
    expect(params().slot).toBe('09:30');
  });

  it('replaces the entry when asked, and goes back to a choice by clearing it', () => {
    const { router, params } = renderAt('/book?branch=SAN&service=FICA');

    act(() => {
      params().setParams({ date: '2026-10-27' }, { replace: true });
    });
    expect(router.state.historyAction).toBe('REPLACE');
    expect(params().date).toBe('2026-10-27');

    act(() => {
      params().goToStep('service');
    });
    expect(params().step).toBe('service');
    expect(router.state.location.search).toBe('?branch=SAN');
  });
});
