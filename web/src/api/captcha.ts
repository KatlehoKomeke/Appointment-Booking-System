/**
 * The API client's side of the WAF CAPTCHA: recognising the challenge, asking the page to have it solved, and sending
 * the request again once it is.
 *
 * In AWS the web application firewall puts a CAPTCHA in front of every booking and cancel POST. A request without a
 * valid token is answered `405` with `x-amzn-waf-action: captcha`, which is how WAF challenges a request that is not
 * a page load. The app is on the same origin, so it can read that header. {@link withCaptcha} wraps the client's
 * fetch: on that answer it asks the registered {@link CaptchaSolver} (the `CaptchaProvider`, which shows AWS's
 * puzzle) and, once the puzzle is solved, sends the very same request once more. WAF has by then set its token in
 * the `aws-waf-token` cookie, which the browser sends with it. The copy is the same request, so a booking keeps its
 * body and its `Idempotency-Key`, and can never book twice.
 *
 * Locally, and anywhere no CAPTCHA is configured, nothing answers 405 this way; if something does, it is an ordinary
 * failure.
 *
 * @packageDocumentation
 */

/** The response header WAF sets on a challenged request, naming the action it wants: `captcha` or `challenge`. */
export const WAF_ACTION_HEADER = 'x-amzn-waf-action';

/** Why a CAPTCHA was not solved: closed by the customer, refused by the puzzle, or never loaded. */
export type CaptchaFailure = 'cancelled' | 'failed' | 'unavailable';

/**
 * Thrown in place of a request's answer when its CAPTCHA was not solved. Nothing reached the API, so nothing was
 * booked or cancelled; `problemMessage` words it for the customer by {@link CaptchaError.reason}.
 */
export class CaptchaError extends Error {
  /** Why the CAPTCHA was not solved. */
  readonly reason: CaptchaFailure;

  /**
   * Creates the error.
   *
   * @param reason - why the CAPTCHA was not solved
   * @param options - the underlying failure, if there was one, as `cause`
   */
  constructor(reason: CaptchaFailure, options?: ErrorOptions) {
    super(`The CAPTCHA was not solved: ${reason}`, options);
    this.name = 'CaptchaError';
    this.reason = reason;
  }
}

/**
 * Has the customer solve a CAPTCHA.
 *
 * @returns true once it is solved and the request may be sent again; false when no CAPTCHA is configured, so the
 *   405 stands as an ordinary failure
 * @throws {CaptchaError} when the customer closes it, the puzzle fails, or its script cannot be loaded
 */
export type CaptchaSolver = () => Promise<boolean>;

let registered: CaptchaSolver | undefined;

/**
 * Makes `solver` the one the app's client asks when a request is challenged. The `CaptchaProvider` registers itself
 * when it mounts.
 *
 * @param solver - shows the puzzle and settles when it is solved or given up
 * @returns a function that unregisters it, if it is still the one registered
 */
export function registerCaptchaSolver(solver: CaptchaSolver): () => void {
  registered = solver;
  return () => {
    if (registered === solver) {
      registered = undefined;
    }
  };
}

/**
 * Tells whether a response is WAF asking for a CAPTCHA: `405` with `x-amzn-waf-action: captcha`.
 *
 * @param response - any response
 */
export function isCaptchaChallenge(response: Response): boolean {
  return (
    response.status === 405 &&
    response.headers.get(WAF_ACTION_HEADER)?.trim().toLowerCase() === 'captcha'
  );
}

/**
 * Wraps a fetch so that a POST WAF challenges is sent again, once, after the customer solves the CAPTCHA.
 *
 * Only POSTs are challenged (WAF's rule covers the booking and cancel POSTs), so only a POST is copied before it is
 * sent. When no solver is registered, or the solver says no CAPTCHA is configured, the 405 is returned as it came.
 * A second challenge after solving is returned as it came too: the request is sent again only once.
 *
 * @param send - the fetch to wrap
 * @param solver - who solves the CAPTCHA; by default whoever {@link registerCaptchaSolver} registered, looked up when
 *   the challenge arrives
 * @returns a fetch with the same signature
 */
export function withCaptcha(
  send: (request: Request) => Promise<Response>,
  solver?: CaptchaSolver,
): (request: Request) => Promise<Response> {
  return async (request) => {
    // A body can be read only once, so the copy is taken before the original is sent.
    const again = request.method === 'POST' ? request.clone() : undefined;
    const response = await send(request);
    if (again === undefined || !isCaptchaChallenge(response)) {
      return response;
    }
    const solve = solver ?? registered;
    if (solve === undefined || !(await solve())) {
      return response;
    }
    return send(again);
  };
}
