/**
 * Unit tests for the canary's script (lambda/canary): it asks for a date the API always accepts, checks the paths and
 * bodies a healthy environment answers with, and fails on anything else.
 */
import { PassThrough } from 'node:stream';
import type { IncomingMessage } from 'node:http';
import { checks, settingsFrom, tomorrowIn, verifyResponse } from '../lambda/canary/index.js';

const SETTINGS = { branchCode: 'SAN', serviceCode: 'FICA', timeZone: 'Africa/Johannesburg' };

/**
 * A response with the given status that streams the given body.
 *
 * @param statusCode - the HTTP status
 * @param body - the body text
 */
function response(statusCode: number, body: string): IncomingMessage {
  const stream = new PassThrough() as PassThrough & { statusCode: number };
  stream.statusCode = statusCode;
  stream.end(body);
  return stream as unknown as IncomingMessage;
}

describe('the canary\'s date', () => {
  test('is tomorrow in Johannesburg, which is already the next day there late in the UTC evening', () => {
    expect(tomorrowIn('Africa/Johannesburg', new Date('2026-10-30T10:00:00Z'))).toBe('2026-10-31');
    // 22:30 UTC is 00:30 the next day in Johannesburg (UTC+2).
    expect(tomorrowIn('Africa/Johannesburg', new Date('2026-10-30T22:30:00Z'))).toBe('2026-11-01');
  });

  test('rolls over the end of a month and of a year', () => {
    expect(tomorrowIn('Africa/Johannesburg', new Date('2026-12-31T08:00:00Z'))).toBe('2027-01-01');
    expect(tomorrowIn('Africa/Johannesburg', new Date('2027-02-28T08:00:00Z'))).toBe('2027-03-01');
  });
});

describe('the canary\'s checks', () => {
  const [readiness, availability] = checks(SETTINGS, new Date('2026-10-30T10:00:00Z'));

  test('read /readyz, then one service\'s availability at one branch for tomorrow, with GETs only', () => {
    expect(readiness.path).toBe('/readyz');
    expect(availability.path).toBe('/api/v1/branches/SAN/availability?service=FICA&date=2026-10-31');
  });

  test('accept a ready app and a day\'s slots, closed days included', () => {
    expect(() => {
      readiness.verify({ status: 'UP' });
    }).not.toThrow();
    expect(() => {
      availability.verify({ branchCode: 'SAN', serviceCode: 'FICA', date: '2026-10-31', slots: [], closedReason: 'PUBLIC_HOLIDAY' });
    }).not.toThrow();
  });

  test('refuse an app that is not ready, or an answer for another branch, service or day, or without slots', () => {
    expect(() => {
      readiness.verify({ status: 'DOWN' });
    }).toThrow(/not "UP"/);
    const good = { branchCode: 'SAN', serviceCode: 'FICA', date: '2026-10-31', slots: [] };
    expect(() => {
      availability.verify({ ...good, branchCode: 'BEL' });
    }).toThrow(/branchCode/);
    expect(() => {
      availability.verify({ ...good, date: '2026-10-30' });
    }).toThrow(/date/);
    expect(() => {
      availability.verify({ ...good, slots: undefined });
    }).toThrow(/slots/);
  });

  test('pass a 200 whose body is right, and fail any other status or a body that is not JSON', async () => {
    await expect(verifyResponse(response(200, '{"status":"UP"}'), readiness.verify)).resolves.toBeUndefined();
    await expect(verifyResponse(response(503, '{"status":"DOWN"}'), readiness.verify)).rejects.toThrow('expected 200, got 503');
    await expect(verifyResponse(response(405, ''), readiness.verify)).rejects.toThrow('expected 200, got 405');
    await expect(verifyResponse(response(200, '<html>'), readiness.verify)).rejects.toThrow(SyntaxError);
  });

  test('read their settings from the canary\'s environment and refuse a missing one', () => {
    const env = {
      BRANCHBOOK_BASE_URL: 'https://stg.branchbook.click',
      BRANCHBOOK_BRANCH_CODE: 'SAN',
      BRANCHBOOK_SERVICE_CODE: 'FICA',
      BRANCHBOOK_TIME_ZONE: 'Africa/Johannesburg',
    };
    expect(settingsFrom(env).baseUrl.hostname).toBe('stg.branchbook.click');
    expect(() => settingsFrom({ ...env, BRANCHBOOK_BRANCH_CODE: '' })).toThrow('BRANCHBOOK_BRANCH_CODE is not set');
  });
});
