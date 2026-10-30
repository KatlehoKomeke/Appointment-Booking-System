import { afterEach, describe, expect, it, vi } from 'vitest';

import { FAKE_CAPTCHA_SCRIPT, installFakeCaptcha } from '../test/fakeCaptcha';
import { type AwsWafCaptchaApi, CaptchaScriptError, loadCaptchaScript } from './awsWafCaptcha';

function scripts() {
  return [...document.head.querySelectorAll('script')].map((script) => script.src);
}

afterEach(() => {
  vi.useRealTimers();
  delete window.AwsWafCaptcha;
  for (const script of document.head.querySelectorAll('script')) {
    script.remove();
  }
});

describe('loadCaptchaScript', () => {
  it('adds the script once, only when asked, and resolves with the API it defines', async () => {
    installFakeCaptcha();
    expect(scripts()).toEqual([]);

    const [first, second] = await Promise.all([
      loadCaptchaScript(FAKE_CAPTCHA_SCRIPT),
      loadCaptchaScript(FAKE_CAPTCHA_SCRIPT),
    ]);

    expect(first).toBe(window.AwsWafCaptcha);
    expect(second).toBe(first);
    expect(scripts()).toEqual([FAKE_CAPTCHA_SCRIPT]);
    // Once it is there, asking again adds nothing.
    expect(await loadCaptchaScript(FAKE_CAPTCHA_SCRIPT)).toBe(first);
    expect(scripts()).toEqual([FAKE_CAPTCHA_SCRIPT]);
  });

  it('adds no script when the API is already defined', async () => {
    const api: AwsWafCaptchaApi = { renderCaptcha: () => undefined };
    window.AwsWafCaptcha = api;

    expect(await loadCaptchaScript(FAKE_CAPTCHA_SCRIPT)).toBe(api);
    expect(scripts()).toEqual([]);
  });

  it('fails clearly when the script cannot be loaded, and removes it so a later check can try again', async () => {
    installFakeCaptcha({ load: 'fails' });

    const failure = loadCaptchaScript(FAKE_CAPTCHA_SCRIPT);

    await expect(failure).rejects.toBeInstanceOf(CaptchaScriptError);
    await expect(failure).rejects.toThrow(
      `The CAPTCHA script could not be loaded from ${FAKE_CAPTCHA_SCRIPT}`,
    );
    expect(scripts()).toEqual([]);
  });

  it('gives up when the script loads but never defines the API in time', async () => {
    vi.useFakeTimers();
    // No fake installed: the script is added and nothing ever defines AwsWafCaptcha.
    const failure = loadCaptchaScript(FAKE_CAPTCHA_SCRIPT, 1_000);
    const settled = expect(failure).rejects.toThrow(
      `The CAPTCHA script did not define AwsWafCaptcha within 1000 ms (${FAKE_CAPTCHA_SCRIPT})`,
    );
    expect(scripts()).toEqual([FAKE_CAPTCHA_SCRIPT]);

    await vi.advanceTimersByTimeAsync(1_000);

    await settled;
    expect(scripts()).toEqual([]);
  });
});
