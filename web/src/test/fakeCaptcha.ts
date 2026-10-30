/**
 * A fake of AWS WAF's CAPTCHA for tests: the firewall's challenge, client settings that configure a CAPTCHA, and a
 * fake `window.AwsWafCaptcha` that appears when the app adds the CAPTCHA script, as AWS's `jsapi.js` would.
 *
 * jsdom never fetches a `<script src>`, so the fake watches the document's head and, when the app adds the script,
 * defines the API and fires `load` (or fires `error`, to fail the load). The puzzle it draws is one paragraph; a test
 * ends it with {@link FakeCaptcha.solve} or {@link FakeCaptcha.fail}. Everything is undone when the test finishes.
 *
 * @packageDocumentation
 */
import { act } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { onTestFinished } from 'vitest';

import { WAF_ACTION_HEADER } from '../api/captcha';
import type { RenderCaptchaOptions } from '../captcha/awsWafCaptcha';
import { server } from './server';

/** The script the fake settings point at. `.example` is reserved, so it can never reach a real host. */
export const FAKE_CAPTCHA_SCRIPT = 'https://captcha.example/abc123/jsapi.js';

/** The CAPTCHA key the fake settings give. */
export const FAKE_CAPTCHA_KEY = 'fake-captcha-key';

/** The text of the fake puzzle, drawn in the container the app gives it. */
export const FAKE_PUZZLE_TEXT = 'Fake puzzle';

/** Makes the fake API's client settings configure a CAPTCHA, as in AWS. */
export function captchaConfigured(): void {
  server.use(
    http.get('*/api/v1/client-config', () =>
      HttpResponse.json({
        captcha: { scriptUrl: FAKE_CAPTCHA_SCRIPT, apiKey: FAKE_CAPTCHA_KEY },
      }),
    ),
  );
}

/** WAF's answer to a POST without a valid CAPTCHA token: `405` with `x-amzn-waf-action: captcha`. */
export function captchaChallenge(): Response {
  return new HttpResponse('', { status: 405, headers: { [WAF_ACTION_HEADER]: 'captcha' } });
}

/** One puzzle the app asked the fake to draw. */
export interface RenderedPuzzle {
  /** Where the app asked for it. */
  container: HTMLElement;
  /** The key and callbacks the app passed. */
  options: RenderCaptchaOptions;
}

/** The fake, as a test drives it. */
export interface FakeCaptcha {
  /** Every puzzle drawn so far, oldest first. */
  rendered: RenderedPuzzle[];
  /** The `src` of every CAPTCHA script in the document now. */
  scripts(): string[];
  /** Solves the latest puzzle, as a customer would. */
  solve(): void;
  /** Ends the latest puzzle with an error, as AWS reports one. */
  fail(): void;
}

/**
 * Installs the fake for the current test.
 *
 * @param options - how the script's load goes
 * @param options.load - `'fails'` fires `error` on the script instead of defining the API; `'succeeds'` by default
 * @returns the fake, to inspect and to solve or fail its puzzle
 */
export function installFakeCaptcha(options: { load?: 'succeeds' | 'fails' } = {}): FakeCaptcha {
  const rendered: RenderedPuzzle[] = [];
  const api = {
    renderCaptcha(container: HTMLElement, renderOptions: RenderCaptchaOptions) {
      const puzzle = document.createElement('p');
      puzzle.textContent = FAKE_PUZZLE_TEXT;
      container.append(puzzle);
      rendered.push({ container, options: renderOptions });
    },
  };

  const observer = new MutationObserver((mutations) => {
    for (const added of mutations.flatMap((m) => [...m.addedNodes])) {
      if (added instanceof HTMLScriptElement && added.src === FAKE_CAPTCHA_SCRIPT) {
        if (options.load === 'fails') {
          added.dispatchEvent(new Event('error'));
        } else {
          window.AwsWafCaptcha = api;
          added.dispatchEvent(new Event('load'));
        }
      }
    }
  });
  observer.observe(document.head, { childList: true });

  onTestFinished(() => {
    observer.disconnect();
    delete window.AwsWafCaptcha;
    for (const script of document.head.querySelectorAll('script')) {
      script.remove();
    }
  });

  const latest = () => {
    const puzzle = rendered.at(-1);
    if (puzzle === undefined) {
      throw new Error('No puzzle has been drawn yet');
    }
    return puzzle;
  };

  return {
    rendered,
    scripts: () =>
      [...document.head.querySelectorAll('script')]
        .map((script) => script.src)
        .filter((src) => src === FAKE_CAPTCHA_SCRIPT),
    solve: () => {
      act(() => {
        latest().options.onSuccess('fake-waf-token');
      });
    },
    fail: () => {
      act(() => {
        latest().options.onError({ kind: 'network_error' });
      });
    },
  };
}
