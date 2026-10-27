/**
 * Cancelling an appointment (Tier 2): the confirmation step sends nothing until the customer confirms, the seat is
 * free again for someone else (BR-14), and the appointment is deleted as it is cancelled. The cancelled page says
 * the details are deleted and the cancellation SMS is on its way; a refresh shows the same from what the tab
 * remembers, while the API answers 404 even to the right digits. Runs in both projects; each books its own fresh
 * demo number, so neither meets the other's booking.
 *
 * @packageDocumentation
 */
import type { Page } from '@playwright/test';

import {
  bookingResponse,
  chooseDate,
  expect,
  last4,
  test,
  timeTile,
  type Candidate,
} from './fixtures';

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

test('a customer cancels, the appointment is deleted and the seat is free again', async ({
  page,
  booking,
  expectConsoleError,
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
  const digits = last4(candidate.mobile);

  await page.waitForURL(`**/appointments/${reference}`);
  // The page stops polling when it sends the cancel, but a poll already on its way can still meet the deleted
  // appointment, and the browser logs that 404; it changes nothing on the page.
  expectConsoleError(new RegExp(`404.*/api/v1/appointments/${reference}`));
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
  const cancelled = page.getByRole('heading', { level: 1, name: 'This appointment was cancelled' });
  await expect(cancelled).toBeVisible();
  await expect(cancelled).toBeFocused();
  // The cancel's answer names the masked number when it carries the cancellation SMS.
  await expect(
    page.getByText(
      new RegExp(
        `^Its details have been deleted\\. A cancellation SMS is on its way to (082 \\*\\*\\* ${digits}|the number you booked with)\\.$`,
      ),
    ),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Cancel appointment' })).toHaveCount(0);

  // The seat came back: the next customer sees one more seat at that time than before the cancellation.
  expect(await seatsAt(page, candidate)).toBe(seatsWhileBooked + 1);

  // The server kept nothing to open, so a refresh shows what this tab remembers of the cancellation.
  await page.reload();
  await expect(cancelled).toBeVisible();
  await expect(
    page.getByText(
      'Its details have been deleted. A cancellation SMS was sent to the number you booked with.',
    ),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Cancel appointment' })).toHaveCount(0);

  // Even the right digits find nothing now. This request is the test's own, so the browser logs no 404.
  const opened = await page.request.get(`/api/v1/appointments/${reference}`, {
    headers: { 'X-Mobile-Last4': digits },
  });
  expect(opened.status()).toBe(404);
  expect(((await opened.json()) as { code?: unknown }).code).toBe('APPOINTMENT_NOT_FOUND');
});
