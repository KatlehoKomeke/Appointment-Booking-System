/**
 * Playwright's configuration for BranchBook's end-to-end tests: a real Chromium against a running app.
 *
 * The app is not started here. `make up` (or any deployment) runs it, and E2E_BASE_URL points the tests at it, so the
 * same suite checks the local stack and a hosted one.
 *
 * @packageDocumentation
 */
import { defineConfig } from '@playwright/test';

const onCi = process.env.CI !== undefined && process.env.CI !== '';

// On staging the firewall puts a CAPTCHA in front of every booking and cancel, and lets through requests that carry
// the synthetic-traffic token in `x-branchbook-synthetic`. E2E_SYNTHETIC_TOKEN, when set, is sent on every request
// the browser and the API fixture make. Production has no such bypass, and a local stack has no CAPTCHA.
const syntheticToken = process.env.E2E_SYNTHETIC_TOKEN ?? '';
const withToken = syntheticToken !== '';

export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.ts',
  // Each booking is a real row in the one database every test shares, so tests run one at a time: two workers
  // racing for a seat or a number would fail for reasons that have nothing to do with the code under test.
  workers: 1,
  fullyParallel: false,
  forbidOnly: onCi,
  // One retry on CI absorbs a slow runner; none locally, so a flaky test is seen the first time it flakes.
  retries: onCi ? 1 : 0,
  // The failed-delivery test waits up to 60 s for the dispatcher's five attempts.
  timeout: 120_000,
  reporter: onCi ? [['html', { open: 'never' }], ['github']] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
    locale: 'en-ZA',
    // The app shows SAST whatever zone the device is in; a browser in another zone proves that holds (BR-20).
    timezoneId: 'America/New_York',
    colorScheme: 'light',
    ...(withToken ? { extraHTTPHeaders: { 'x-branchbook-synthetic': syntheticToken } } : {}),
    // A trace records every request's headers, so with the token set none is kept: the report is uploaded as a CI
    // artifact, and the token must not travel with it. The screenshot still shows where a test failed.
    trace: withToken ? 'off' : 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'mobile',
      // A phone has no Tab key to book with, and a failed SMS or the inbox looks the same at any width, so these run
      // on the desktop only.
      testIgnore: ['keyboard.spec.ts', 'failed-delivery.spec.ts', 'inbox.spec.ts'],
      use: {
        browserName: 'chromium',
        viewport: { width: 390, height: 844 },
        deviceScaleFactor: 2,
        isMobile: true,
        hasTouch: true,
      },
    },
    {
      name: 'desktop',
      use: {
        browserName: 'chromium',
        viewport: { width: 1280, height: 800 },
      },
    },
  ],
});
