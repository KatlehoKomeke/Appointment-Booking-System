import { describe, expect, it, vi } from 'vitest';

import { getJson, setJson } from '../lib/storage';
import {
  clearBooking,
  forgetLookup,
  loadDetails,
  lookupDigits,
  rememberCancellation,
  rememberedCancellation,
  rememberLookup,
  saveDetails,
} from './bookingSession';

const DETAILS = { firstName: 'Nomvula', lastName: 'Dlamini', mobile: '082 555 0101' };

const CANCELLATION = {
  cancelledAt: '2026-10-26T08:00:00+02:00',
  branch: { code: 'SAN', name: 'Sandton Summit Mall' },
  service: { code: 'FICA', name: 'Update personal details (FICA)' },
  slot: { startsAt: '2026-10-27T09:30:00+02:00', endsAt: '2026-10-27T09:45:00+02:00' },
};

// Everything the tab's session storage holds, as text.
function everythingStored(): string {
  return Array.from({ length: sessionStorage.length }, (_, i) => {
    const key = sessionStorage.key(i) ?? '';
    return `${key}=${sessionStorage.getItem(key) ?? ''}`;
  }).join('\n');
}

describe('the booking session', () => {
  it('keeps the details in session storage until the booking is cleared', () => {
    expect(loadDetails()).toBeUndefined();
    saveDetails(DETAILS);
    expect(loadDetails()).toEqual(DETAILS);

    clearBooking();
    expect(loadDetails()).toBeUndefined();
  });

  it('remembers the digits that opened an appointment, whatever the reference is typed like', () => {
    rememberLookup('APT-7K3M-9Q2X', '0101');
    clearBooking();

    expect(lookupDigits('apt7k3m9q2x')).toBe('0101');
    expect(lookupDigits('APT-H4WQ-8RZD')).toBeUndefined();
  });

  it('remembers a cancellation without the name or the number, and forgets the digits', () => {
    rememberLookup('APT-7K3M-9Q2X', '0101');
    rememberLookup('APT-H4WQ-8RZD', '0000');
    forgetLookup('apt7k3m9q2x');
    // Given the whole appointment, it keeps only the codes and names, so neither the address nor anything else.
    rememberCancellation('APT-7K3M-9Q2X', {
      ...CANCELLATION,
      branch: { ...CANCELLATION.branch, address: 'Shop 4, 1 Protea Lane, Sandton' },
      customer: { firstName: 'Nomvula', lastName: 'Dlamini', mobileMasked: '082 *** 0101' },
    } as typeof CANCELLATION);

    expect(lookupDigits('APT-7K3M-9Q2X')).toBeUndefined();
    expect(lookupDigits('APT-H4WQ-8RZD')).toBe('0000');
    expect(rememberedCancellation('apt-7k3m-9q2x')).toEqual(CANCELLATION);
    expect(rememberedCancellation('APT-H4WQ-8RZD')).toBeUndefined();
    const stored = everythingStored();
    for (const personal of ['Nomvula', 'Dlamini', '0101', '***', 'Protea']) {
      expect(stored).not.toContain(personal);
    }
  });

  it('ignores what an older version left behind', () => {
    setJson('branchbook.details', { firstName: 'Nomvula' });
    expect(loadDetails()).toBeUndefined();
    setJson('branchbook.cancellations', { APT7K3M9Q2X: { cancelledAt: 3, branch: 'SAN' } });
    expect(rememberedCancellation('APT-7K3M-9Q2X')).toBeUndefined();
  });

  it('carries on without storage when the browser refuses it', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError');
    });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });

    expect(() => {
      saveDetails(DETAILS);
      rememberLookup('APT-7K3M-9Q2X', '0101');
      forgetLookup('APT-7K3M-9Q2X');
      rememberCancellation('APT-7K3M-9Q2X', CANCELLATION);
      clearBooking();
    }).not.toThrow();
    expect(loadDetails()).toBeUndefined();
    expect(rememberedCancellation('APT-7K3M-9Q2X')).toBeUndefined();
    expect(getJson('anything')).toBeUndefined();
    expect(setJson('anything', 1)).toBe(false);
  });
});
