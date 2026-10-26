/**
 * South African cellphone numbers, read as a customer types them (BR-19).
 *
 * The form checks the number when the field is left, so it must accept exactly what the API accepts: a number the
 * form passes and the API refuses would surface only as a 422 at the last step. The API's `MobileNumber.parse` uses
 * libphonenumber (9.0.41) for region ZA and keeps a number only when it is a mobile whose E.164 form matches
 * `^\+27(6[0-9]|7[0-9]|8[1-5])[0-9]{7}$`. For that pattern libphonenumber's ZA metadata reports every number as a
 * mobile, so this module needs no metadata: it repeats the parser's steps on the digits. The inputs it was checked
 * against are in `mobile.test.ts`; the server remains the authority.
 *
 * @packageDocumentation
 */

/** The result of {@link parseSaMobile}. */
export type ParsedMobile =
  | {
      ok: true;
      /** The form the API stores and the booking sends, for example `+27825550101`. */
      e164: string;
      /** The form the customer reads, for example `082 555 0101`. */
      display: string;
    }
  | { ok: false };

/** What the API lets through to the parser: digits and the punctuation people type, 1 to 32 characters. */
const TYPED = /^[0-9+()\-. ]{1,32}$/;

/** A South African mobile's national number without its leading 0: a 6x, 7x or 81 to 85 prefix and seven digits. */
const NATIONAL_MOBILE = /^(?:6\d|7\d|8[1-5])\d{7}$/;

/**
 * Reads a South African cellphone number in any of the forms the API accepts, such as `082 555 0101`,
 * `+27 82 555 0101`, `+27 (0)82 555 0101`, `27825550101` or `0027825550101`. Spaces, hyphens, dots and brackets
 * are ignored; a landline (021), VoIP (087), toll-free (080), foreign number, a wrong length, letters or an
 * extension are refused.
 *
 * @param input - the number as typed
 */
export function parseSaMobile(input: string): ParsedMobile {
  if (input.trim() === '' || !TYPED.test(input)) {
    return { ok: false };
  }
  // As libphonenumber does, read from the first plus or digit to the last digit: the punctuation around them is
  // ignored, and pluses count only as one run at the start.
  const start = input.search(/[+\d]/);
  const end = input.search(/\d[^\d]*$/);
  if (start === -1 || end === -1) {
    return { ok: false };
  }
  const core = input.slice(start, end + 1);
  const pluses = /^\+*/.exec(core)?.[0].length ?? 0;
  if (core.slice(pluses).includes('+')) {
    return { ok: false };
  }
  const international = pluses > 0;
  const national = nationalNumber(core.replace(/\D/g, ''), international);
  if (national === undefined || !NATIONAL_MOBILE.test(national)) {
    return { ok: false };
  }
  return {
    ok: true,
    e164: `+27${national}`,
    display: `0${national.slice(0, 2)} ${national.slice(2, 5)} ${national.slice(5)}`,
  };
}

/**
 * The national number, without the country code or the trunk 0, as libphonenumber finds it for region ZA.
 *
 * @param digits - every digit typed, in order
 * @param international - whether a plus came first
 */
function nationalNumber(digits: string, international: boolean): string | undefined {
  let rest = digits;
  if (international && !rest.startsWith('00')) {
    if (!rest.startsWith('27')) {
      return undefined;
    }
    rest = rest.slice(2);
  } else if (rest.startsWith('00')) {
    // "+00 27..." is read again without its plus, as libphonenumber does, so it lands here too.
    // 00 is South Africa's international dialling prefix, so what follows must be the country code.
    if (!rest.slice(2).startsWith('27')) {
      return undefined;
    }
    rest = rest.slice(4);
  } else if (rest.startsWith('27') && rest.length > 10) {
    // Without a plus, a leading 27 is the country code only when the whole is too long to be a national number.
    rest = rest.slice(2);
  }
  // The trunk prefix: "082..." nationally, and the "(0)" some people keep after +27.
  return rest.startsWith('0') ? rest.slice(1) : rest;
}

/**
 * The last four digits of a number, which a customer gives with the reference to open an appointment.
 *
 * @example `last4('082 555 0101')` and `last4('+27825550101')` are both "0101"
 * @param mobile - the number in any form
 */
export function last4(mobile: string): string {
  return mobile.replace(/\D/g, '').slice(-4);
}
