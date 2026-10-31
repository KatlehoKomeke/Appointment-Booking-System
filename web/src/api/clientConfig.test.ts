import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { notFoundResponse } from '../test/handlers';
import { createTestQueryClient } from '../test/render';
import { server } from '../test/server';
import { captchaSettings, parseClientConfig } from './clientConfig';

const SCRIPT = 'https://captcha.example/abc123/jsapi.js';

// Serves the given answer at the client settings' path, counting the requests.
function serveSettings(answer: () => Response): { count: number } {
  const asked = { count: 0 };
  server.use(
    http.get('*/api/v1/client-config', () => {
      asked.count += 1;
      return answer();
    }),
  );
  return asked;
}

describe('parseClientConfig', () => {
  it('reads a CAPTCHA with an https script and a key', () => {
    expect(
      parseClientConfig({ captcha: { scriptUrl: SCRIPT, apiKey: 'fake-captcha-key' } }),
    ).toEqual({ captcha: { scriptUrl: SCRIPT, apiKey: 'fake-captcha-key' } });
  });

  it('reads anything else as no CAPTCHA, never loading a script over plain HTTP', () => {
    for (const body of [
      { captcha: null },
      {},
      null,
      'captcha',
      { captcha: { scriptUrl: 'http://captcha.example/jsapi.js', apiKey: 'fake-captcha-key' } },
      { captcha: { scriptUrl: 'not a url', apiKey: 'fake-captcha-key' } },
      { captcha: { scriptUrl: SCRIPT, apiKey: '' } },
      { captcha: { scriptUrl: SCRIPT } },
    ]) {
      expect(parseClientConfig(body), JSON.stringify(body)).toEqual({ captcha: null });
    }
  });
});

describe('captchaSettings', () => {
  it('asks the API once, and keeps the answer for the session', async () => {
    const asked = serveSettings(() =>
      HttpResponse.json({ captcha: { scriptUrl: SCRIPT, apiKey: 'fake-captcha-key' } }),
    );
    const queryClient = createTestQueryClient();

    expect(await captchaSettings(queryClient)).toEqual({
      scriptUrl: SCRIPT,
      apiKey: 'fake-captcha-key',
    });
    expect(await captchaSettings(queryClient)).toEqual({
      scriptUrl: SCRIPT,
      apiKey: 'fake-captcha-key',
    });
    expect(asked.count).toBe(1);
  });

  it('is no CAPTCHA when the settings cannot be read, and asks again the next time', async () => {
    const asked = serveSettings(() => notFoundResponse());
    const queryClient = createTestQueryClient();

    expect(await captchaSettings(queryClient)).toBeNull();
    expect(await captchaSettings(queryClient)).toBeNull();
    expect(asked.count).toBe(2);
  });

  it('is no CAPTCHA when the network fails', async () => {
    serveSettings(() => HttpResponse.error());

    expect(await captchaSettings(createTestQueryClient())).toBeNull();
  });
});
