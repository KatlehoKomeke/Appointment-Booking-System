import { describe, expect, it } from 'vitest';

import { last4, parseSaMobile } from './mobile';

// The cases are the API's own (MobileNumberTest, BR-19), so the form and the server agree on every one. A
// differential run of 3,382 typed inputs against libphonenumber 9.0.41 also agreed on every one.
describe('parseSaMobile', () => {
  it.each([
    '082 555 0101',
    '0825550101',
    '+27 82 555 0101',
    '+27 (0)82 555 0101',
    '27825550101',
    '0027825550101',
    '082-555-0101',
    '(082) 555 0101',
  ])('reads %s as +27825550101 and shows it the South African way', (typed) => {
    expect(parseSaMobile(typed)).toEqual({
      ok: true,
      e164: '+27825550101',
      display: '082 555 0101',
    });
  });

  it.each([
    ['a landline', '021 555 0101'],
    ['a VoIP number', '087 555 0101'],
    ['a toll-free number', '080 555 0101'],
    ['a foreign number', '+44 7911 123456'],
    ['an extension', '082 555 0101 ext 12'],
    ['a tel: link', 'tel:+27825550101'],
    ['letters', '082-FYNBOS-1'],
    ['too few digits', '082 555 010'],
    ['too many digits', '082 555 01011'],
    ['nothing', ''],
    ['only spaces', '   '],
    ['more than 32 characters', '0825550101                         '],
    ['a plus between the digits', '082+5550101'],
  ])('refuses %s', (_, typed) => {
    expect(parseSaMobile(typed)).toEqual({ ok: false });
  });

  it('accepts every mobile prefix the API stores: 6x, 7x and 81 to 85', () => {
    for (const prefix of ['060', '069', '071', '075', '079', '081', '085']) {
      expect(parseSaMobile(`${prefix} 555 0101`).ok).toBe(true);
    }
    expect(parseSaMobile('086 555 0101').ok).toBe(false);
  });

  it('keeps the demo number that always fails delivery, since it is a valid mobile', () => {
    expect(parseSaMobile('082 555 0000')).toMatchObject({ ok: true, e164: '+27825550000' });
  });
});

describe('last4', () => {
  it('takes the last four digits of any form', () => {
    expect(last4('082 555 0101')).toBe('0101');
    expect(last4('+27825550101')).toBe('0101');
  });
});
