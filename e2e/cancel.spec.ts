/**
 * Cancelling an appointment (Tier 2): the confirmation step sends nothing until the customer confirms, the cancelled
 * page says so calmly, the cancellation SMS goes out, and the seat is free again for someone else (BR-14, BR-24).
 * Runs in both projects; each books its own fresh demo number, so neither meets the other's booking.
 *
 * @packageDocumentation
 */
import type { Page } from '@playwright/test';

import { bookingResponse, chooseDate, expect, test, timeTile, type Candidate } from './fixtures';

interface Availability {
  slots: { startsAt: string; seatsAvailable: number }[];
}

// The seats left at the booked time, read from the API the time grid uses, so the test sees what the next customer
// would see.
async function seatsAt(page: Page, candidate: Candidate): Promise<number> {
  const response = await page.request.get(
    `/api/v1/branches/${candidate.branch.code}/availability?service=${candidate.service.code}&date=${candidate.date}`,
  );
  expect(response.ok()).toBe(true);
  const { slots } = (await response.json()) as Availability;
  const slot = slots.find((s) => s.startsAt.startsWith(`${candidate.date}T${candidate.time}`));
  expect(slot, `a slot at ${candidate.time} on ${candidate.date}`).toBeDefined();
  return slot?.seatsAvailable ?? -1;
}

test('a customer cancels, sees the appointment cancelled and its SMS sent, and the seat is free again', async ({
  page,
  booking,
}) => {
  const { candidate, reference } = await booking.book(async (c) => {
    await page.goto(`/book?branch=${c.branch.code}`);
    await page.getByRole('button', { name: `Choose ${c.service.name}` }).click();
    await chooseDate(page, c.date);
    await timeTile(page, c.time).click();
    await page.getByRole('button', { name: 'Continue' }).click();
    await page.getByLabel('First name').fill(c.firstName);
    await page.getByLabel('Last name').fill(c.lastName);
    await page.getByLabel('Cellphone number').fill(c.mobile);
    await page.getByRole('button', { name: 'Continue' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Check and confirm' })).toBeVisible();
    return bookingResponse(page, () =>
      page.getByRole('button', { name: 'Confirm appointment' }).click(),
    );
  });

  await page.waitForURL(`**/appointments/${reference}`);
  await expect(
    page.getByRole('heading', { level: 1, name: 'Your appointment is confirmed' }),
  ).toBeVisible();
  const seatsWhileBooked = await seatsAt(page, candidate);

  // Changing one's mind sends nothing, and gives focus back where it was.
  const cancel = page.getByRole('button', { name: 'Cancel appointment' });
  await cancel.click();
  await expect(
    page.getByText(/Cancelling releases the time for someone else to book/),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Keep my appointment' }).click();
  await expect(cancel).toBeFocused();
  await expect(
    page.getByRole('heading', { level: 1, name: 'Your appointment is confirmed' }),
  ).toBeVisible();

  await cancel.click();
  await page.getByRole('button', { name: 'Yes, cancel it' }).click();
  await expect(
    page.getByRole('heading', { level: 1, name: 'This appointment was cancelled' }),
  ).toBeVisible();
  await expect(page.getByText(/Its time is free again for someone else to book/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Cancel appointment' })).toHaveCount(0);
  await expect(page.getByText('Cancellation SMS', { exact: true })).toBeVisible();

  // The seat came back: the next customer sees one more seat at that time than before the cancellation.
  expect(await seatsAt(page, candidate)).toBe(seatsWhileBooked + 1);

  // The cancellation is the server's, not the page's: a refresh shows the same.
  await page.reload();
  await expect(
    page.getByRole('heading', { level: 1, name: 'This appointment was cancelled' }),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Cancel appointment' })).toHaveCount(0);
});
