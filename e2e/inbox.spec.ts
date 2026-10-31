/**
 * The simulated inbox: a booking's confirmation SMS is listed at `/messages`, its recipient redacted, its reference a
 * link back to the appointment, and its status moving to Sent as the dispatcher sends it. Desktop only: see
 * playwright.config.ts.
 *
 * The inbox is a demo operator view that the `prod` profile switches off, so on staging and production
 * `GET /api/v1/messages` answers 404 and this test skips itself (`skipWithoutInbox`).
 *
 * @packageDocumentation
 */
import { bookingResponse, chooseDate, expect, skipWithoutInbox, test, timeTile } from './fixtures';

test('the simulated inbox lists the confirmation SMS of a booking, recipient redacted, and shows it sent', async ({
  page,
  request,
  booking,
}) => {
  await skipWithoutInbox(request);

  const { reference } = await booking.book(async (candidate) => {
    await page.goto(`/book?branch=${candidate.branch.code}`);
    await page.getByRole('button', { name: `Choose ${candidate.service.name}` }).click();
    await chooseDate(page, candidate.date);
    await timeTile(page, candidate.time).click();
    await page.getByRole('button', { name: 'Continue' }).click();
    await page.getByLabel('First name').fill(candidate.firstName);
    await page.getByLabel('Last name').fill(candidate.lastName);
    await page.getByLabel('Cellphone number').fill(candidate.mobile);
    await page.getByRole('button', { name: 'Continue' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Check and confirm' })).toBeVisible();
    return bookingResponse(page, () =>
      page.getByRole('button', { name: 'Confirm appointment' }).click(),
    );
  });
  await page.waitForURL(`**/appointments/${reference}`);

  await page.getByRole('link', { name: 'See the SMS in the simulated inbox' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Simulated inbox' })).toBeVisible();
  const card = page
    .getByRole('list', { name: 'Messages' })
    .getByRole('listitem')
    .filter({ has: page.getByRole('link', { name: reference, exact: true }) });
  await expect(card).toHaveCount(1);
  await expect(card).toContainText('Confirmation SMS');
  await expect(card).toContainText('082 *** ****');
  // The page polls every 2 s while a message is pending; the dispatcher sends within a few seconds.
  await expect(card.getByText('Sent', { exact: true })).toBeVisible({ timeout: 15_000 });

  // The reference opens the appointment again, in this tab that booked it.
  await card.getByRole('link', { name: reference, exact: true }).click();
  await expect(
    page.getByRole('heading', { level: 1, name: 'Your appointment is confirmed' }),
  ).toBeVisible();
});
