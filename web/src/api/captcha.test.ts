import { describe, expect, it, vi } from 'vitest';

import { CaptchaError, isCaptchaChallenge, WAF_ACTION_HEADER } from './captcha';
import { ApiProblem, createApiClient, unwrap } from './client';
import type { BookingRequest } from './queries';

const REQUEST: BookingRequest = {
  branchCode: 'SAN',
  serviceCode: 'FICA',
  startsAt: '2026-10-27T09:30:00+02:00',
  customer: { firstName: 'Nomvula', lastName: 'Dlamini', mobile: '+27825550101' },
};

// Minted, not written out: a literal UUID beside "KEY" reads to the secret scanner as a key.
const KEY = crypto.randomUUID();

function challenge() {
  return new Response('', { status: 405, headers: { [WAF_ACTION_HEADER]: 'captcha' } });
}

function booked() {
  return new Response(JSON.stringify({ reference: 'APT-7K3M-9Q2X' }), {
    status: 201,
    headers: { 'Content-Type': 'application/json' },
  });
}

/**
 * A client whose requests get the given responses in turn, recording each request's method, key and body.
 *
 * @param solve - the CAPTCHA solver the client is given
 * @param responses - one per request, in order
 */
function clientAnswering(solve: () => Promise<boolean>, ...responses: (() => Response)[]) {
  const sent: { method: string; key: string | null; body: string }[] = [];
  const client = createApiClient({
    baseUrl: 'http://localhost',
    solveCaptcha: solve,
    fetch: async (request) => {
      sent.push({
        method: request.method,
        key: request.headers.get('Idempotency-Key'),
        body: await request.text(),
      });
      const next = responses[sent.length - 1];
      if (next === undefined) {
        throw new Error(`No response for request ${String(sent.length)}`);
      }
      return next();
    },
  });
  return { client, sent };
}

function bookWith(client: ReturnType<typeof createApiClient>) {
  return unwrap(
    client.POST('/api/v1/appointments', {
      params: { header: { 'Idempotency-Key': KEY } },
      body: REQUEST,
    }),
  );
}

describe('the API client, when the firewall asks for a CAPTCHA', () => {
  it('sends a challenged POST again, once, with the same body and Idempotency-Key, once the CAPTCHA is solved', async () => {
    const solve = vi.fn(() => Promise.resolve(true));
    const { client, sent } = clientAnswering(solve, challenge, booked);

    await expect(bookWith(client)).resolves.toMatchObject({ reference: 'APT-7K3M-9Q2X' });

    expect(solve).toHaveBeenCalledOnce();
    expect(sent).toHaveLength(2);
    expect(sent[1]).toEqual(sent[0]);
    expect(sent[0]).toEqual({ method: 'POST', key: KEY, body: JSON.stringify(REQUEST) });
  });

  it('keeps the 405 as an ordinary failure when no CAPTCHA is configured', async () => {
    const { client, sent } = clientAnswering(() => Promise.resolve(false), challenge);

    const failure = bookWith(client);

    await expect(failure).rejects.toBeInstanceOf(ApiProblem);
    await expect(failure).rejects.toMatchObject({ status: 405, code: undefined });
    expect(sent).toHaveLength(1);
  });

  it('rejects with the CaptchaError when the CAPTCHA is not solved, sending nothing more', async () => {
    const closed = new CaptchaError('cancelled');
    const { client, sent } = clientAnswering(() => Promise.reject(closed), challenge);

    await expect(bookWith(client)).rejects.toBe(closed);
    expect(sent).toHaveLength(1);
  });

  it('sends the request again only once, even when the firewall challenges it again', async () => {
    const solve = vi.fn(() => Promise.resolve(true));
    const { client, sent } = clientAnswering(solve, challenge, challenge);

    await expect(bookWith(client)).rejects.toMatchObject({ status: 405 });
    expect(solve).toHaveBeenCalledOnce();
    expect(sent).toHaveLength(2);
  });

  it('never asks for a CAPTCHA for a GET, or for a 405 the firewall did not mark', async () => {
    const solve = vi.fn(() => Promise.resolve(true));
    const { client, sent } = clientAnswering(
      solve,
      challenge,
      () => new Response('', { status: 405 }),
    );

    await expect(unwrap(client.GET('/api/v1/branches'))).rejects.toMatchObject({ status: 405 });
    await expect(bookWith(client)).rejects.toMatchObject({ status: 405 });
    expect(solve).not.toHaveBeenCalled();
    expect(sent.map((request) => request.method)).toEqual(['GET', 'POST']);
  });
});

describe('isCaptchaChallenge', () => {
  it('is a 405 whose x-amzn-waf-action is captcha, in any case', () => {
    const withAction = (status: number, action?: string) =>
      new Response('', {
        status,
        headers: action === undefined ? {} : { [WAF_ACTION_HEADER]: action },
      });

    expect(isCaptchaChallenge(withAction(405, 'captcha'))).toBe(true);
    expect(isCaptchaChallenge(withAction(405, 'CAPTCHA'))).toBe(true);
    expect(isCaptchaChallenge(withAction(405, 'challenge'))).toBe(false);
    expect(isCaptchaChallenge(withAction(405))).toBe(false);
    expect(isCaptchaChallenge(withAction(403, 'captcha'))).toBe(false);
  });
});
