/**
 * A confirmation SMS that cannot be delivered. The simulated gateway refuses every message to 082 555 0000, so the
 * dispatcher retries with backoff and gives up after five attempts (BR-17); the confirmation page says so, and that
 * the booking still stands. Desktop only: see playwright.config.ts.
 *
 * 082 555 0000 is one number, so BR-10 lets it hold one upcoming appointment per service. The booking fixture
 * moves to a service the number does not hold yet when the API answers DUPLICATE_APPOINTMENT.
 *
 * @packageDocumentation
 */
import { bookingResponse, chooseDate, expect, test, timeTile } from './fixtures';

const ALWAYS_FAILS = '082 555 0000';

test('an SMS that cannot be delivered is shown as Not delivered, and the booking still stands', async ({
  page,
  booking,
}) => {
  const { reference } = await booking.book(
    async (candidate) => {
      await page.goto(`/book?branch=${candidate.branch.code}`);
      await page.getByRole('button', { name: `Choose ${candidate.service.name}` }).click();
      await chooseDate(page, candidate.date);
      await timeTile(page, candidate.time).click();
      await page.getByRole('button', { name: 'Continue' }).click();
      await page.getByLabel('First name').fill(candidate.firstName);
      await page.getByLabel('Last name').fill(candidate.lastName);
      await page.getByLabel('Cellphone number').fill(candidate.mobile);
      await page.getByRole('button', { name: 'Continue' }).click();
      await expect(
        page.getByRole('heading', { level: 1, name: 'Check and confirm' }),
      ).toBeVisible();
      return bookingResponse(page, () =>
        page.getByRole('button', { name: 'Confirm appointment' }).click(),
      );
    },
    { mobile: ALWAYS_FAILS },
  );

  await page.waitForURL(`**/appointments/${reference}`);
  await expect(
    page.getByRole('heading', { level: 1, name: 'Your appointment is confirmed' }),
  ).toBeVisible();
  // Five attempts, 2, 4, 8 and 16 s apart, on a dispatcher that wakes every 2 s: about 40 s in all.
  await expect(page.getByText('Not delivered', { exact: true })).toBeVisible({ timeout: 60_000 });
  await expect(page.getByText(/Your booking still stands/)).toBeVisible();
  await expect(page.getByText(reference, { exact: true })).toBeVisible();
});
