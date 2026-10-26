/**
 * Booking with the keyboard alone: Tab and Shift+Tab to move, Enter and Space to act, and the arrow keys on the time
 * grid. Proves the skip link comes first and moves focus to the page, each step's heading takes focus, and every
 * control the customer tabs to shows a focus ring (WCAG 2.4.7). Desktop only: see playwright.config.ts.
 *
 * @packageDocumentation
 */
import type { Locator, Page } from '@playwright/test';

import { bookingResponse, dateButton, expect, test } from './fixtures';

test('a customer books with the keyboard alone, seeing where the focus is at every stop', async ({
  page,
  booking,
}) => {
  const { reference } = await booking.book(async (candidate) => {
    await page.goto('/');
    await expect(
      page.getByRole('heading', { level: 1, name: 'Book a branch appointment' }),
    ).toBeVisible();

    // The first Tab on a fresh page reaches the skip link, and Enter puts focus on the page's main content.
    await page.keyboard.press('Tab');
    await expect(page.getByRole('link', { name: 'Skip to main content' })).toBeFocused();
    await expectFocusRing(page);
    await page.keyboard.press('Enter');
    await expect(page.getByRole('main')).toBeFocused();

    await tabTo(page, page.getByRole('link', { name: 'Book an appointment' }));
    await page.keyboard.press('Enter');
    await expectHeadingFocused(page, 'Choose a branch');

    await tabTo(page, page.getByRole('link', { name: candidate.branch.name }));
    await page.keyboard.press('Enter');
    await expectHeadingFocused(page, 'Choose a service');

    await tabTo(page, page.getByRole('button', { name: `Choose ${candidate.service.name}` }));
    await page.keyboard.press('Space');
    await expectHeadingFocused(page, 'Choose a date and time');

    // Page the date strip forward until the date shows, then choose it.
    const date = dateButton(page, candidate.date);
    if (!(await date.isVisible())) {
      const next = page.getByRole('button', { name: 'Next 7 days' });
      await tabTo(page, next);
      for (let pages = 0; pages < 5 && !(await date.isVisible()); pages++) {
        await page.keyboard.press('Enter');
      }
    }
    await tabTo(page, date);
    await page.keyboard.press('Enter');
    await expect(date).toHaveAttribute('aria-pressed', 'true');

    // The times are one radio group: one tab stop, Space to choose, the arrow keys to move the choice.
    const free = page.locator('input[type=radio][name=slot]:not([disabled])');
    await tabTo(page, free.first());
    await page.keyboard.press('Space');
    await expect(free.first()).toBeChecked();
    if ((await free.count()) > 1) {
      await page.keyboard.press('ArrowRight');
      await expect(free.nth(1)).toBeChecked();
      await page.keyboard.press('ArrowLeft');
      await expect(free.first()).toBeChecked();
    }
    const target = page.locator(`input[type=radio][name=slot][value="${candidate.time}"]`);
    const focusedTime = page.locator('input[type=radio][name=slot]:focus');
    for (let moves = 0; moves < 60 && !(await target.isChecked()); moves++) {
      await page.keyboard.press('ArrowRight');
      // The choice is held in the URL, so the radio shows as checked only once the router has re-rendered; reading
      // it sooner would see the old choice and step past the target.
      await expect(focusedTime).toBeChecked();
    }
    await expect(target).toBeChecked();
    await expect(target).toBeFocused();
    await expectFocusRing(page);

    await tabTo(page, page.getByRole('button', { name: 'Continue' }));
    await page.keyboard.press('Enter');
    await expectHeadingFocused(page, 'Your details');

    const firstName = page.getByLabel('First name');
    const lastName = page.getByLabel('Last name');
    await tabTo(page, firstName);
    await page.keyboard.type(candidate.firstName);
    await tabTo(page, lastName);
    await page.keyboard.type(candidate.lastName);
    await tabTo(page, page.getByLabel('Cellphone number'));
    await page.keyboard.type(candidate.mobile);
    // Back one field with Shift+Tab, and forward again.
    await page.keyboard.press('Shift+Tab');
    await expect(lastName).toBeFocused();
    await expectFocusRing(page);
    await expect(lastName).toHaveValue(candidate.lastName);
    await tabTo(page, page.getByRole('button', { name: 'Continue' }));
    await page.keyboard.press('Enter');
    await expectHeadingFocused(page, 'Check and confirm');

    const confirm = page.getByRole('button', { name: 'Confirm appointment' });
    await tabTo(page, confirm);
    return bookingResponse(page, () => page.keyboard.press('Enter'));
  });

  await page.waitForURL(`**/appointments/${reference}`);
  await expectHeadingFocused(page, 'Your appointment is confirmed');
  await expect(page.getByText(reference, { exact: true })).toBeVisible();
});

// Presses `key` until `target` has focus, failing if it never does; then checks the focus can be seen.
async function tabTo(page: Page, target: Locator, key = 'Tab', limit = 80): Promise<void> {
  await expect(target).toBeVisible();
  for (let presses = 0; presses < limit; presses++) {
    await page.keyboard.press(key);
    if (await target.evaluate((element) => element === document.activeElement)) {
      await expectFocusRing(page);
      return;
    }
  }
  throw new Error(`${String(limit)} presses of ${key} never reached ${target.toString()}`);
}

// The focused control draws an outline. A time's radio is visually hidden, so its tile draws the ring instead.
async function expectFocusRing(page: Page): Promise<void> {
  const outline = await page.evaluate(() => {
    const focused = document.activeElement;
    if (!(focused instanceof HTMLElement) || focused === document.body) {
      return 'nothing focused';
    }
    const drawn = focused.matches('input[type=radio]')
      ? (focused.closest('label') ?? focused)
      : focused;
    const style = getComputedStyle(drawn);
    return `${style.outlineStyle} ${style.outlineWidth}`;
  });
  expect(outline, 'the focused control’s outline').not.toMatch(/^(none|nothing)|\s0px$/);
}

// Each step and page moves focus to its heading, so a screen reader starts reading at the new content.
async function expectHeadingFocused(page: Page, name: string): Promise<void> {
  await expect(page.getByRole('heading', { level: 1, name })).toBeFocused();
}
