/**
 * The typed client for the BranchBook API.
 *
 * Every path, parameter and response body is checked at compile time against `schema.d.ts`, which
 * `npm run generate:api` generates from the committed contract, `docs/openapi.yaml` (ADR-0005). An API change the
 * app has not followed therefore fails the type check rather than a customer's booking.
 *
 * @packageDocumentation
 */
import createClient from 'openapi-fetch';

import { type CaptchaSolver, withCaptcha } from './captcha';
import type { components, paths } from './schema';

/** An RFC 9457 problem: the body of every error the API returns (docs/errors.md). */
export type Problem = components['schemas']['Problem'];

/** The response header that carries the id the server logged the request under. */
export const CORRELATION_ID_HEADER = 'X-Correlation-Id';

/** The response header that says, in seconds, how long to wait before trying again (429 and 503). */
export const RETRY_AFTER_HEADER = 'Retry-After';

/** The response header that marks a booking an earlier request with the same idempotency key already made. */
export const IDEMPOTENT_REPLAYED_HEADER = 'Idempotent-Replayed';

/**
 * Where the API is when no base URL is given: the page's own origin, which serves both the app and the API.
 *
 * It is the absolute origin rather than `''` because the client builds a `Request`, and outside a browser (Node,
 * where the tests run) a `Request` needs an absolute URL. In the browser the two are the same.
 */
export function defaultBaseUrl(): string {
  return typeof globalThis.location === 'undefined' ? '' : globalThis.location.origin;
}

/** Options for {@link createApiClient}. The defaults suit the browser. */
export interface ApiClientOptions {
  /** Where the API is served. Defaults to the page's own origin, which serves both the app and the API. */
  baseUrl?: string;
  /** The fetch implementation. Defaults to the browser's; tests pass a fake. */
  fetch?: (request: Request) => Promise<Response>;
  /**
   * Who solves a WAF CAPTCHA when a POST is challenged. Defaults to the one the `CaptchaProvider` registers
   * (`captcha.ts`); tests pass a fake.
   */
  solveCaptcha?: CaptchaSolver;
}

/**
 * Creates a client for the API.
 *
 * A POST that WAF challenges with a CAPTCHA (`405` and `x-amzn-waf-action: captcha`) is sent again, once, with the
 * same body and headers, after the customer solves it; see `captcha.ts`.
 *
 * @param options - where the API is and how to reach it
 * @returns a client whose `GET` and other methods accept only the contract's paths and parameters
 */
export function createApiClient(options: ApiClientOptions = {}) {
  return createClient<paths>({
    baseUrl: options.baseUrl ?? defaultBaseUrl(),
    // The global fetch is looked up on each call, not when the client is made, so a fake installed later (MSW in
    // the tests) still sees every request.
    fetch: withCaptcha(
      options.fetch ?? ((request) => globalThis.fetch(request)),
      options.solveCaptcha,
    ),
  });
}

/** The client the app uses: the API on the page's own origin. */
export const api = createApiClient();

/**
 * An error response from the API, thrown by {@link unwrap}.
 *
 * Screens choose their wording from {@link ApiProblem.code}, never from the problem's `detail`, which is written for
 * developers. The correlation id lets support find the request in the server's logs.
 */
export class ApiProblem extends Error {
  /** The HTTP status, such as 422. */
  readonly status: number;

  /**
   * The stable error code, such as `SLOT_FULL`: one of the codes the contract lists, so a screen can switch on it
   * exhaustively. Absent when the response was not one of the API's problems, or was an HTTP-level failure.
   */
  readonly code: Problem['code'];

  /** The id the server logged the request under, from the problem or else from the response header. */
  readonly correlationId: string | undefined;

  /** The whole problem, when the response carried one. */
  readonly problem: Problem | undefined;

  /** How many seconds the API asked the caller to wait before trying again, from `Retry-After`; 429 and 503 only. */
  readonly retryAfterSeconds: number | undefined;

  /**
   * Creates the error from an error response.
   *
   * @param response - the error response
   * @param body - its parsed body; a proxy or an outage can make it something other than a problem
   */
  constructor(response: Response, body: unknown) {
    const problem = isProblem(body) ? body : undefined;
    super(`The API answered ${String(response.status)} ${problem?.code ?? ''}`.trim());
    this.name = 'ApiProblem';
    this.status = response.status;
    this.code = problem?.code;
    this.correlationId =
      problem?.correlationId ?? response.headers.get(CORRELATION_ID_HEADER) ?? undefined;
    this.problem = problem;
    this.retryAfterSeconds = retryAfterSeconds(response);
  }
}

/**
 * Reads `Retry-After` from a response: a number of seconds, or an HTTP date, which is turned into the seconds left.
 *
 * @param response - any response
 * @returns the seconds to wait, never negative, or `undefined` when the header is absent or unreadable
 */
export function retryAfterSeconds(response: Response): number | undefined {
  const value = response.headers.get(RETRY_AFTER_HEADER)?.trim();
  if (value === undefined || value === '') {
    return undefined;
  }
  if (/^\d+$/.test(value)) {
    return Number(value);
  }
  const at = Date.parse(value);
  return Number.isNaN(at) ? undefined : Math.max(0, Math.ceil((at - Date.now()) / 1000));
}

/**
 * Tells whether a booking response replays an earlier request with the same idempotency key, rather than a new
 * booking. Either way the appointment in the body is the one booked.
 *
 * @param response - the response to `POST /api/v1/appointments`
 */
export function isIdempotentReplay(response: Response): boolean {
  return response.headers.get(IDEMPOTENT_REPLAYED_HEADER) === 'true';
}

/**
 * Returns the body of a successful call, or throws an {@link ApiProblem} for an error response.
 *
 * @example
 * ```ts
 * const { branches } = await unwrap(api.GET('/api/v1/branches'));
 * ```
 * @param call - a call made with the client, for an operation whose success has a body
 * @returns the response body, typed by the contract
 * @throws {ApiProblem} when the API answers with an error. A network failure rejects with the browser's own error
 *   instead, so a screen can tell "offline" from "refused".
 */
export async function unwrap<T>(
  call: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  return (await unwrapWithResponse(call)).data;
}

/**
 * Like {@link unwrap}, but also returns the response, for a caller that needs its headers, such as
 * {@link isIdempotentReplay}.
 *
 * @param call - a call made with the client, for an operation whose success has a body
 * @returns the response body, typed by the contract, and the response it came in
 * @throws {ApiProblem} when the API answers with an error; a network failure rejects with the browser's own error
 */
export async function unwrapWithResponse<T>(
  call: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<{ data: T; response: Response }> {
  const { data, error, response } = await call;
  if (response.ok && data !== undefined) {
    return { data, response };
  }
  throw new ApiProblem(response, error);
}

/**
 * Tells whether a parsed error body is one of the API's problems rather than, say, a proxy's HTML page. It checks
 * the four properties every problem carries, including the ones without a code, such as a 404 for an unknown path.
 *
 * @param body - the parsed body of an error response
 */
function isProblem(body: unknown): body is Problem {
  return (
    typeof body === 'object' &&
    body !== null &&
    'status' in body &&
    typeof body.status === 'number' &&
    'title' in body &&
    typeof body.title === 'string' &&
    'instance' in body &&
    typeof body.instance === 'string' &&
    'correlationId' in body &&
    typeof body.correlationId === 'string'
  );
}
