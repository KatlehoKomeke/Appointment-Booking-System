/**
 * The settings the app takes from the API at run time, from `GET /api/v1/client-config`: today the WAF CAPTCHA's
 * script and public key, which exist only where the firewall asks for a CAPTCHA (in AWS), so they cannot be built
 * into the app.
 *
 * They are read the first time they are needed, when a request is first challenged, and kept for the rest of the
 * session. When they cannot be read, the app behaves as though no CAPTCHA is configured: that challenge is then an
 * ordinary failure, and the next one asks again.
 *
 * @packageDocumentation
 */
import { type QueryClient, queryOptions } from '@tanstack/react-query';

import { ApiProblem, defaultBaseUrl } from './client';
import { queryKeys } from './queries';

/** Where AWS WAF's CAPTCHA JavaScript API is loaded from, and the key it renders the puzzle with. */
export interface CaptchaSettings {
  /** The account's CAPTCHA integration URL followed by `jsapi.js`; always `https:`. */
  scriptUrl: string;
  /** The CAPTCHA API key. It is public by design: AWS binds it to the domains it may issue tokens for. */
  apiKey: string;
}

/**
 * The client settings, as `GET /api/v1/client-config` answers them.
 *
 * Written by hand until the contract (docs/openapi.yaml) lists the endpoint. It is then replaced by the generated
 * `components['schemas']['ClientConfig']` from `schema.d.ts`, and {@link fetchClientConfig} calls
 * `api.GET('/api/v1/client-config')`.
 */
export interface ClientConfig {
  /** The CAPTCHA, or `null` where none is configured, as on a local run. */
  captcha: CaptchaSettings | null;
}

/** Where the API serves the client settings. */
export const CLIENT_CONFIG_PATH = '/api/v1/client-config';

/**
 * Reads the client settings from an answer's body, trusting nothing. Anything but a CAPTCHA with an `https:` script
 * and a key is read as no CAPTCHA: the app must never load a script over plain HTTP, or from a value it cannot read.
 *
 * @param body - the parsed JSON body of a 200
 */
export function parseClientConfig(body: unknown): ClientConfig {
  const captcha =
    typeof body === 'object' && body !== null && 'captcha' in body ? body.captcha : undefined;
  if (
    typeof captcha !== 'object' ||
    captcha === null ||
    !('scriptUrl' in captcha) ||
    !('apiKey' in captcha) ||
    typeof captcha.scriptUrl !== 'string' ||
    typeof captcha.apiKey !== 'string' ||
    captcha.apiKey === '' ||
    !isHttpsUrl(captcha.scriptUrl)
  ) {
    return { captcha: null };
  }
  return { captcha: { scriptUrl: captcha.scriptUrl, apiKey: captcha.apiKey } };
}

function isHttpsUrl(value: string): boolean {
  try {
    return new URL(value).protocol === 'https:';
  } catch {
    return false;
  }
}

/**
 * Asks the API for the client settings.
 *
 * @throws {ApiProblem} when the API answers with an error, such as a 404 from an API that predates the endpoint; a
 *   network failure rejects with the browser's own error
 */
export async function fetchClientConfig(): Promise<ClientConfig> {
  const response = await globalThis.fetch(`${defaultBaseUrl()}${CLIENT_CONFIG_PATH}`, {
    headers: { Accept: 'application/json' },
  });
  const body: unknown = await response.json().catch(() => undefined);
  if (!response.ok) {
    throw new ApiProblem(response, body);
  }
  return parseClientConfig(body);
}

/**
 * The client settings as a query, cached for the session: they change only with a deployment. A failure is not
 * retried, since a customer is waiting on a challenged request; it is not cached either, so the next challenge asks
 * again.
 */
export const clientConfigQuery = queryOptions({
  queryKey: queryKeys.clientConfig(),
  queryFn: fetchClientConfig,
  staleTime: Infinity,
  gcTime: Infinity,
  retry: false,
});

/**
 * The CAPTCHA settings, from the cache or asked for once. It never throws: settings that cannot be read are no
 * CAPTCHA.
 *
 * @param queryClient - the app's cache
 * @returns the settings, or `null` when no CAPTCHA is configured or the settings could not be read
 */
export async function captchaSettings(queryClient: QueryClient): Promise<CaptchaSettings | null> {
  try {
    return (await queryClient.query(clientConfigQuery)).captcha;
  } catch {
    return null;
  }
}
