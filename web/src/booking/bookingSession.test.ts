import { describe, expect, it, vi } from 'vitest';

import { getJson, setJson } from '../lib/storage';
import {
  clearBooking,
  loadDetails,
  lookupDigits,
  rememberLookup,
  saveDetails,
} from './bookingSession';

const DETAILS = { firstName: 'Nomvula', lastName: 'Dlamini', mobile: '082 555 0101' };

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

  it('ignores what an older version left behind', () => {
    setJson('branchbook.details', { firstName: 'Nomvula' });
    expect(loadDetails()).toBeUndefined();
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
      clearBooking();
    }).not.toThrow();
    expect(loadDetails()).toBeUndefined();
    expect(getJson('anything')).toBeUndefined();
    expect(setJson('anything', 1)).toBe(false);
  });
});
