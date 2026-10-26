/**
 * The whole journey a customer takes: from the landing page through the five booking steps to the confirmation
 * page, watching the SMS go out, then opening the booking again on /manage. Runs on a phone and on a desktop.
 *
 * With SCREENSHOTS=1, each step is also saved as a full-page PNG in docs/assets/walkthrough/, named
 * `<project>-<nn>-<step>.png`. Without it nothing is written, so an ordinary run never changes the repository.
 *
 * @packageDocumentation
 */
import path from 'node:path';

import AxeBuilder from '@axe-core/playwright';
import type { Page, TestInfo } from '@playwright/test';

import {
  bookingResponse,
  chooseDate,
  expect,
  expectNoPersonalData,
  last4,
  test,
  timeTile,
} from './fixtures';

const WALKTHROUGH_DIR = path.resolve(import.meta.dirname, '../docs/assets/walkthrough');
// The copy the README and docs/errors.md give for a number that is not a South African cellphone.
const NOT_A_CELLPHONE = 'We need a cellphone number to SMS your confirmation.';
// A Johannesburg landline in the 555 range: a valid South African number, but not one an SMS can reach.
const LANDLINE = '011 555 0101';

test('a customer books from the landing page, sees the SMS sent, and opens the booking on /manage', async ({
  page,
  booking,
  expectConsoleError,
}, testInfo) => {
  const shot = walkthrough(page, testInfo);
  const addresses: string[] = [];
  page.on('framenavigated', (frame) => {
    if (frame === page.mainFrame()) {
      addresses.push(frame.url());
    }
  });

  const { candidate, reference } = await booking.book(async (candidate) => {
    await page.goto('/');
    await expect(
      page.getByRole('heading', { level: 1, name: 'Book a branch appointment' }),
    ).toBeVisible();
    await shot(1, 'landing');
    await page.getByRole('link', { name: 'Book an appointment' }).click();

    await expect(page.getByRole('heading', { level: 1, name: 'Choose a branch' })).toBeVisible();
    await shot(2, 'branch');
    await page.getByRole('link', { name: candidate.branch.name }).click();

    await expect(page.getByRole('heading', { level: 1, name: 'Choose a service' })).toBeVisible();
    await shot(3, 'service');
    await page.getByRole('button', { name: `Choose ${candidate.service.name}` }).click();

    await expect(
      page.getByRole('heading', { level: 1, name: 'Choose a date and time' }),
    ).toBeVisible();
    await chooseDate(page, candidate.date);
    await timeTile(page, candidate.time).click();
    await expect(page.locator(`input[type=radio][value="${candidate.time}"]`)).toBeChecked();
    await shot(4, 'date-time');
    await page.getByRole('button', { name: 'Continue' }).click();

    await expect(page.getByRole('heading', { level: 1, name: 'Your details' })).toBeVisible();
    const mobile = page.getByLabel('Cellphone number');
    await page.getByLabel('First name').fill(candidate.firstName);
    await page.getByLabel('Last name').fill(candidate.lastName);
    await mobile.fill(LANDLINE);
    await page.getByRole('button', { name: 'Continue' }).click();
    // Refused in the browser, on the field, before anything is sent; the step does not move on.
    await expect(mobile).toHaveAttribute('aria-invalid', 'true');
    await expect(mobile).toHaveAccessibleDescription(new RegExp(escapeRegExp(NOT_A_CELLPHONE)));
    expect(new URL(page.url()).searchParams.get('step')).toBe('details');
    await shot(5, 'details-landline');
    await mobile.fill(candidate.mobile);
    await expect(mobile).not.toHaveAttribute('aria-invalid', 'true');
    expectNoPersonalData(page.url(), candidate);
    await page.getByRole('button', { name: 'Continue' }).click();

    await expect(page.getByRole('heading', { level: 1, name: 'Check and confirm' })).toBeVisible();
    await expect(
      page.getByRole('region', { name: 'Your appointment' }).getByText(candidate.mobile),
    ).toBeVisible();
    expectNoPersonalData(page.url(), candidate);
    await expectNoSeriousA11yViolations(page, 'the review step');
    await shot(6, 'review');
    return bookingResponse(page, () =>
      page.getByRole('button', { name: 'Confirm appointment' }).click(),
    );
  });

  // The confirmation page: the reference, and the SMS leaving the outbox by itself.
  expect(reference).toMatch(/^APT-[A-Z0-9]{4}-[A-Z0-9]{4}$/);
  await page.waitForURL(`**/appointments/${reference}`);
  await expect(
    page.getByRole('heading', { level: 1, name: 'Your appointment is confirmed' }),
  ).toBeVisible();
  await expect(page.getByText(reference, { exact: true })).toBeVisible();
  await expect(page.getByText('Sending', { exact: true })).toBeVisible();
  await shot(7, 'confirmation-sending');
  await expect(page.getByText(/^Sent at \d\d:\d\d$/)).toBeVisible({ timeout: 15_000 });
  await expectNoSeriousA11yViolations(page, 'the confirmation page');
  await shot(8, 'confirmation-sent');

  // /manage: wrong digits get the same answer as a wrong reference; the right ones open the booking.
  await page.goto('/manage');
  await page.getByLabel('Reference', { exact: true }).fill(reference);
  const digits = page.getByLabel('Last four digits of your cellphone number');
  await digits.fill(wrongDigits(last4(candidate.mobile)));
  // The API answers a mismatch with 404, and the browser logs every 404 it fetches.
  expectConsoleError(/status of 404 .*\/api\/v1\/appointments\/APT-/);
  await page.getByRole('button', { name: 'Find appointment' }).click();
  await expect(page.getByRole('alert')).toContainText(
    "We couldn't find an appointment with those details.",
  );
  await shot(9, 'manage-not-found');
  await digits.fill(last4(candidate.mobile));
  await page.getByRole('button', { name: 'Find appointment' }).click();
  await page.waitForURL(`**/appointments/${reference}`);
  await expect(
    page.getByRole('heading', { level: 1, name: 'Your appointment is confirmed' }),
  ).toBeVisible();
  await expect(page.getByText(reference, { exact: true })).toBeVisible();
  await shot(10, 'manage-found');

  // Every address the tab held, including the steps a retry passed through, is free of the name and number.
  expect(addresses.length).toBeGreaterThan(0);
  for (const address of addresses) {
    expectNoPersonalData(address, candidate);
  }
});

// Fails on serious or critical problems only: a moderate finding is worth reading in the report, not a red build.
async function expectNoSeriousA11yViolations(page: Page, where: string): Promise<void> {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  const serious = results.violations
    .filter((violation) => violation.impact === 'serious' || violation.impact === 'critical')
    .map(
      (violation) =>
        `${violation.id} (${violation.impact ?? 'unknown'}): ${violation.help}, at ${violation.nodes
          .map((node) => node.target.join(' '))
          .join('; ')}`,
    );
  expect(serious, `serious or critical accessibility violations on ${where}`).toEqual([]);
}

// Saves a step's full page when SCREENSHOTS=1. A retry passes the same steps again and overwrites the same files.
function walkthrough(page: Page, testInfo: TestInfo) {
  return async (step: number, name: string): Promise<void> => {
    if (process.env.SCREENSHOTS !== '1') {
      return;
    }
    const file = `${testInfo.project.name}-${String(step).padStart(2, '0')}-${name}.png`;
    await page.screenshot({
      path: path.join(WALKTHROUGH_DIR, file),
      fullPage: true,
      animations: 'disabled',
      caret: 'hide',
    });
  };
}

// Four digits that are certainly not the number's last four.
function wrongDigits(right: string): string {
  return String((Number(right) + 5000) % 10_000).padStart(4, '0');
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
