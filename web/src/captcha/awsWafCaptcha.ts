/**
 * AWS WAF's CAPTCHA JavaScript API: the part of it the app uses, typed, and a loader for its script.
 *
 * The script is AWS's (`<integration URL>/jsapi.js`, from the client settings), so it is loaded from AWS's origin,
 * never bundled. It is added as a `<script src>` element the first time a request is challenged, and never before:
 * a customer who is never challenged never downloads it. Nothing is inlined, so the Content Security Policy stays
 * strict; the API's policy allows the script's origin only where a CAPTCHA is configured.
 *
 * @packageDocumentation
 */

/** How long the script may take to load and define `window.AwsWafCaptcha` before the check is given up. */
export const CAPTCHA_SCRIPT_TIMEOUT_MS = 15_000;

/** How often the loader looks for `window.AwsWafCaptcha` while the script loads. */
const POLL_MS = 50;

/** What {@link AwsWafCaptchaApi.renderCaptcha} takes: the key, and what to do when the puzzle ends. */
export interface RenderCaptchaOptions {
  /** The CAPTCHA API key from the client settings. */
  apiKey: string;
  /**
   * Called once the puzzle is solved. WAF has then set its token in the `aws-waf-token` cookie, which the browser
   * sends with the next request; the token is passed here too, and the app does not need it.
   */
  onSuccess: (wafToken: string) => void;
  /**
   * Called when the puzzle cannot go on: AWS reports an error `kind` such as `network_error` or `token_error`. The
   * app treats every kind the same.
   */
  onError: (error: unknown) => void;
}

/** The global `AwsWafCaptcha` that AWS's `jsapi.js` defines. */
export interface AwsWafCaptchaApi {
  /**
   * Draws the puzzle inside `container`, which must already be in the document.
   *
   * @param container - the element the puzzle is drawn in
   * @param options - the key and the callbacks
   */
  renderCaptcha(container: HTMLElement, options: RenderCaptchaOptions): void;
}

declare global {
  interface Window {
    /** AWS WAF's CAPTCHA JavaScript API, defined by its `jsapi.js` once it has loaded. */
    AwsWafCaptcha?: AwsWafCaptchaApi;
  }
}

/** Thrown by {@link loadCaptchaScript} when the script fails to load, or loads without defining the API in time. */
export class CaptchaScriptError extends Error {
  /**
   * Creates the error.
   *
   * @param message - what went wrong, for the console
   */
  constructor(message: string) {
    super(message);
    this.name = 'CaptchaScriptError';
  }
}

let loading: Promise<AwsWafCaptchaApi> | undefined;

/**
 * Loads AWS's CAPTCHA script, once, and resolves with the API it defines.
 *
 * When `window.AwsWafCaptcha` already exists it resolves at once, adding nothing. Otherwise it adds one
 * `<script src>` to the document's head; callers that ask while it loads share the same wait. A script that fails
 * to load, or does not define the API within the timeout, is removed again, so a later challenge can try afresh.
 *
 * @param scriptUrl - the script's address, from the client settings
 * @param timeoutMs - how long to wait for the API to appear
 * @returns the API
 * @throws {CaptchaScriptError} when the script fails to load or does not define the API in time
 */
export function loadCaptchaScript(
  scriptUrl: string,
  timeoutMs = CAPTCHA_SCRIPT_TIMEOUT_MS,
): Promise<AwsWafCaptchaApi> {
  const ready = window.AwsWafCaptcha;
  if (ready !== undefined) {
    return Promise.resolve(ready);
  }
  loading ??= inject(scriptUrl, timeoutMs).finally(() => {
    loading = undefined;
  });
  return loading;
}

function inject(scriptUrl: string, timeoutMs: number): Promise<AwsWafCaptchaApi> {
  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = scriptUrl;
    script.async = true;

    // Settles the wait once, whichever comes first: the API, a load error or the timeout. It runs only after the
    // poll and the timer below exist, since nothing can fire before the script is added, at the end.
    const settle = (outcome: () => void) => {
      clearInterval(poll);
      clearTimeout(timer);
      script.removeEventListener('load', lookForApi);
      script.removeEventListener('error', failed);
      outcome();
    };
    const giveUp = (message: string) => {
      script.remove();
      settle(() => {
        reject(new CaptchaScriptError(message));
      });
    };
    // The API is usually defined by the time the load event fires; polling also covers a script that defines it a
    // moment later.
    function lookForApi() {
      const api = window.AwsWafCaptcha;
      if (api !== undefined) {
        settle(() => {
          resolve(api);
        });
      }
    }
    function failed() {
      giveUp(`The CAPTCHA script could not be loaded from ${scriptUrl}`);
    }

    script.addEventListener('load', lookForApi);
    script.addEventListener('error', failed);
    const poll = setInterval(lookForApi, POLL_MS);
    const timer = setTimeout(() => {
      giveUp(
        `The CAPTCHA script did not define AwsWafCaptcha within ${String(timeoutMs)} ms (${scriptUrl})`,
      );
    }, timeoutMs);
    document.head.append(script);
  });
}
