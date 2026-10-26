import { describe, expect, it } from 'vitest';

import { problem } from '../test/fixtures';
import { ApiProblem, type Problem } from './client';
import {
  DEMO_NUMBER_COPY,
  DEMO_NUMBER_FIELD_COPY,
  fieldErrors,
  messageForCode,
  type ProblemCode,
  problemMessage,
} from './problemCopy';

function thrown(body: Problem, headers: Record<string, string> = {}) {
  return new ApiProblem(new Response(null, { status: body.status, headers }), body);
}

/** The customer copy docs/errors.md gives each code, where it gives words. */
const DOCUMENTED: Partial<Record<ProblemCode, string>> = {
  BRANCH_NOT_FOUND: "We couldn't find that branch.",
  APPOINTMENT_NOT_FOUND: "We couldn't find an appointment with those details.",
  SERVICE_NOT_OFFERED_AT_BRANCH: "This branch doesn't offer that service.",
  SLOT_WITHIN_LEAD_TIME: 'That time is too soon. Please choose a later slot.',
  MOBILE_NUMBER_REQUIRED: 'We need a cellphone number to SMS your confirmation.',
  DEMO_NUMBER_REQUIRED: 'Use a demo number',
  DUPLICATE_APPOINTMENT: 'You already have an upcoming appointment for this service.',
  CUSTOMER_OVERLAP: 'You already have an appointment at that time.',
  SLOT_FULL: 'That time was just taken',
  TOO_LATE_TO_CANCEL: 'This appointment has already started.',
  LOOKUP_RATE_LIMITED: 'Too many attempts. Try again later.',
};

const EVERY_CODE: ProblemCode[] = [
  'VALIDATION_FAILED',
  'IDEMPOTENCY_KEY_REQUIRED',
  'BRANCH_NOT_FOUND',
  'APPOINTMENT_NOT_FOUND',
  'MESSAGE_NOT_FOUND',
  'SERVICE_NOT_OFFERED_AT_BRANCH',
  'DATE_OUTSIDE_BOOKING_WINDOW',
  'SLOT_ON_CLOSED_DAY',
  'SLOT_OUTSIDE_HOURS',
  'SLOT_OFF_GRID',
  'SLOT_WITHIN_LEAD_TIME',
  'MOBILE_NUMBER_REQUIRED',
  'DEMO_NUMBER_REQUIRED',
  'IDEMPOTENCY_KEY_REUSED',
  'DUPLICATE_APPOINTMENT',
  'CUSTOMER_OVERLAP',
  'SLOT_FULL',
  'TOO_LATE_TO_CANCEL',
  'LOOKUP_RATE_LIMITED',
  'INTERNAL_ERROR',
  'TRY_AGAIN',
];

describe('problemMessage', () => {
  it.each(EVERY_CODE)('gives %s customer copy that never repeats the server detail', (code) => {
    const message = problemMessage(
      thrown(problem(code, { detail: 'DEVELOPER TEXT', title: 'SERVER TITLE' })),
    );

    expect(message.title).not.toBe('');
    expect(`${message.title} ${message.body ?? ''}`).not.toMatch(/DEVELOPER TEXT|SERVER TITLE/);
    const documented = DOCUMENTED[code];
    if (documented !== undefined) {
      expect(`${message.title} ${message.body ?? ''}`.startsWith(documented)).toBe(true);
    }
  });

  it('gives the codes only an app bug could cause the generic copy with the id to quote', () => {
    for (const code of [
      'IDEMPOTENCY_KEY_REQUIRED',
      'IDEMPOTENCY_KEY_REUSED',
      'INTERNAL_ERROR',
    ] as const) {
      const message = problemMessage(thrown(problem(code, { correlationId: 'abc-123' })));
      expect(message.title).toBe('Something went wrong on our side');
      expect(message.body).toContain('quote reference abc-123');
    }
  });

  it('names the demo numbers when a demo that takes only those is given another', () => {
    expect(problemMessage(thrown(problem('DEMO_NUMBER_REQUIRED')))).toEqual({
      title: 'Use a demo number',
      body: 'This demo accepts only the numbers 082 555 0000 to 082 555 0199. Try 082 555 0101.',
    });
  });

  it('treats a 5xx without a problem body as our failure, quoting the id from the header', () => {
    const gateway = new ApiProblem(
      new Response('<html>Bad gateway</html>', {
        status: 502,
        headers: { 'X-Correlation-Id': 'gateway-1' },
      }),
      '<html>Bad gateway</html>',
    );

    expect(problemMessage(gateway)).toEqual({
      title: 'Something went wrong on our side',
      body: 'Please try again in a few minutes. If it keeps happening, quote reference gateway-1.',
    });
  });

  it('tells a network failure apart as being offline', () => {
    expect(problemMessage(new TypeError('Failed to fetch')).title).toBe(
      "We couldn't reach BranchBook",
    );
  });

  it('copes with something that is not an error at all', () => {
    expect(problemMessage('nope').title).toBe('Something went wrong on our side');
  });
});

describe('messageForCode', () => {
  it('words a refusal the page sees coming exactly as the API refusal would be worded', () => {
    for (const code of ['BRANCH_NOT_FOUND', 'SERVICE_NOT_OFFERED_AT_BRANCH'] as const) {
      expect(messageForCode(code)).toEqual(problemMessage(thrown(problem(code))));
    }
  });
});

describe('fieldErrors', () => {
  it('maps the API field names onto the form, in the customer words, first one winning', () => {
    const refused = thrown(
      problem('VALIDATION_FAILED', {
        errors: [
          { field: 'customer.firstName', code: 'NAME_INVALID', message: 'must be a name' },
          { field: 'customer.firstName', code: 'NOT_BLANK', message: 'must not be blank' },
          { field: 'customer.lastName', code: 'NOT_BLANK', message: 'must not be blank' },
          { field: 'customer.mobile', code: 'MOBILE_NUMBER_REQUIRED', message: 'not a mobile' },
          { field: 'branchCode', code: 'PATTERN', message: 'not on the form' },
        ],
      }),
    );

    expect(fieldErrors(refused)).toEqual({
      firstName: 'Enter your first name using letters, spaces, hyphens or apostrophes.',
      lastName: 'Enter your last name.',
      mobile: 'We need a cellphone number to SMS your confirmation.',
    });
  });

  it('words a number outside the demo numbers on the cellphone field, naming the demo numbers', () => {
    expect(
      fieldErrors(
        thrown(
          problem('DEMO_NUMBER_REQUIRED', {
            errors: [
              {
                field: 'customer.mobile',
                code: 'DEMO_NUMBER_REQUIRED',
                message: 'not a demo number',
              },
            ],
          }),
        ),
      ),
    ).toEqual({ mobile: DEMO_NUMBER_FIELD_COPY });
    expect(DEMO_NUMBER_COPY).toBe(
      'This demo accepts only the numbers 082 555 0000 to 082 555 0199. Try 082 555 0101.',
    );
    expect(DEMO_NUMBER_FIELD_COPY).toBe(
      'This demo accepts only 082 555 0000 to 082 555 0199, for example 082 555 0101.',
    );
  });

  it('names the time and the lookup digits too, and nothing for an error without fields', () => {
    expect(
      fieldErrors(
        problem('SLOT_WITHIN_LEAD_TIME', {
          errors: [{ field: 'startsAt', code: 'SLOT_WITHIN_LEAD_TIME', message: null }],
        }),
      ),
    ).toEqual({ slot: 'That time is too soon. Please choose a later slot.' });
    expect(
      fieldErrors(
        thrown(
          problem('VALIDATION_FAILED', {
            errors: [{ field: 'X-Mobile-Last4', code: 'PATTERN', message: 'must be 4 digits' }],
          }),
        ),
      ),
    ).toEqual({ last4: 'Enter the last four digits of your cellphone number.' });
    expect(fieldErrors(new TypeError('offline'))).toEqual({});
  });
});
