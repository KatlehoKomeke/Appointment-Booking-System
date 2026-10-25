import { describe, expect, it } from 'vitest';

import { ApiProblem, createApiClient, type Problem, unwrap } from './client';
import type { components } from './schema';

type Availability = components['schemas']['Availability'];

/** Rustenburg's first Saturday slot, as the API renders it: every instant carries the branch's +02:00 offset. */
const AVAILABILITY = {
  branchCode: 'RUS',
  serviceCode: 'HOME',
  date: '2026-11-07',
  timezone: 'Africa/Johannesburg',
  generatedAt: '2026-11-02T06:00:00+02:00',
  closedReason: null,
  holidayName: null,
  slots: [
    {
      startsAt: '2026-11-07T08:00:00+02:00',
      endsAt: '2026-11-07T08:45:00+02:00',
      seatsAvailable: 2,
    },
  ],
} satisfies Availability;

const OUTSIDE_THE_WINDOW = {
  type: 'urn:branchbook:problem:DATE_OUTSIDE_BOOKING_WINDOW',
  title: 'Date outside the booking window',
  status: 422,
  detail: "That date can't be booked. Choose a date from bookableFrom to bookableTo.",
  instance: 'urn:branchbook:request:test-1',
  code: 'DATE_OUTSIDE_BOOKING_WINDOW',
  correlationId: 'test-1',
} satisfies Problem;

/**
 * A client whose every request gets the same response, and which records the requests it sent.
 *
 * @param response - makes the response; a fresh one each time, because a body can be read only once
 */
function clientAnswering(response: () => Response) {
  const sent: Request[] = [];
  const client = createApiClient({
    baseUrl: 'http://localhost',
    fetch: (request) => {
      sent.push(request);
      return Promise.resolve(response());
    },
  });
  return { client, sent };
}

function json(status: number, body: unknown, contentType = 'application/json') {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
}

describe('the API client', () => {
  it('builds the path and query from the contract and returns the typed body', async () => {
    const { client, sent } = clientAnswering(() => json(200, AVAILABILITY));

    const availability = await unwrap(
      client.GET('/api/v1/branches/{code}/availability', {
        params: { path: { code: 'RUS' }, query: { service: 'HOME', date: '2026-11-07' } },
      }),
    );

    expect(sent.map((request) => request.url)).toEqual([
      'http://localhost/api/v1/branches/RUS/availability?service=HOME&date=2026-11-07',
    ]);
    expect(availability.slots[0]?.seatsAvailable).toBe(2);
  });

  it('throws an ApiProblem with the code and correlation id of a problem', async () => {
    const { client } = clientAnswering(() =>
      json(422, OUTSIDE_THE_WINDOW, 'application/problem+json'),
    );

    const failure = unwrap(client.GET('/api/v1/branches'));

    await expect(failure).rejects.toBeInstanceOf(ApiProblem);
    await expect(failure).rejects.toMatchObject({
      status: 422,
      code: 'DATE_OUTSIDE_BOOKING_WINDOW',
      correlationId: 'test-1',
      problem: OUTSIDE_THE_WINDOW,
    });
  });

  it('keeps a problem that has no code, such as a 404 for a path the API does not serve', async () => {
    const notFound = {
      title: 'Not Found',
      status: 404,
      instance: 'urn:branchbook:request:test-2',
      correlationId: 'test-2',
    } satisfies Problem;
    const { client } = clientAnswering(() => json(404, notFound, 'application/problem+json'));

    await expect(unwrap(client.GET('/api/v1/branches'))).rejects.toMatchObject({
      status: 404,
      code: undefined,
      correlationId: 'test-2',
      problem: notFound,
    });
  });

  it('still throws an ApiProblem when the error is not a problem, taking the correlation id from the header', async () => {
    const { client } = clientAnswering(
      () =>
        new Response('<html>Bad gateway</html>', {
          status: 502,
          headers: { 'Content-Type': 'text/html', 'X-Correlation-Id': 'gateway-1' },
        }),
    );

    await expect(unwrap(client.GET('/api/v1/branches'))).rejects.toMatchObject({
      status: 502,
      code: undefined,
      correlationId: 'gateway-1',
      problem: undefined,
    });
  });

  it('lets a network failure through unchanged, so a screen can tell offline from refused', async () => {
    const offline = new TypeError('Failed to fetch');
    const client = createApiClient({
      baseUrl: 'http://localhost',
      fetch: () => Promise.reject(offline),
    });

    await expect(unwrap(client.GET('/api/v1/branches'))).rejects.toBe(offline);
  });
});
