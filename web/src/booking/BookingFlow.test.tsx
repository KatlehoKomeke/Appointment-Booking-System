import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { appointment, confirmationSms, DEMO_MOBILE, NOW, notification } from '../test/fixtures';
import { renderRoute } from '../test/render';
import { server } from '../test/server';

// What the customer types that must stay out of every URL: the names, and the number in any form it could take.
const PERSONAL = ['Nomvula', 'Dlamini', '555', '0101'];

type Router = ReturnType<typeof renderRoute>['router'];

// Every URL the router shows, from the first one on, so a test can check what the address bar ever held.
function recordUrls(router: Router): string[] {
  const href = ({ pathname, search, hash }: Router['state']['location']) =>
    decodeURIComponent(`${pathname}${search}${hash}`).replace(/\+/g, ' ');
  const urls = [href(router.state.location)];
  router.subscribe((state) => {
    urls.push(href(state.location));
  });
  return urls;
}

async function heading(name: string) {
  return screen.findByRole('heading', { level: 1, name });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(NOW));
});

afterEach(() => {
  vi.useRealTimers();
});

describe('Booking an appointment, end to end', () => {
  it('books the FICA update at Sandton Summit Mall and shows the SMS going from Sending to Sent', async () => {
    const keys: string[] = [];
    let delivered = false;
    server.use(
      // Notes the key, then lets the fake API book.
      http.post('*/api/v1/appointments', ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key') ?? '');
      }),
      // The fake API's own answer (Sending) until the gateway "delivers"; then the same booking with its SMS sent.
      http.get('*/api/v1/appointments/:reference', ({ params }) => {
        if (!delivered) {
          return undefined;
        }
        const reference = String(params.reference);
        return HttpResponse.json(
          appointment({
            reference,
            notifications: [
              notification({
                status: 'SENT',
                attempts: 1,
                sentAt: '2026-10-26T08:00:04+02:00',
                body: confirmationSms('FICA', 'SAN', 'Tue 27 Oct 09:30', reference),
              }),
            ],
          }),
        );
      }),
    );
    const user = userEvent.setup();
    const { router } = renderRoute('/');
    const urls = recordUrls(router);

    // Landing: Book.
    await user.click(screen.getByRole('link', { name: 'Book an appointment' }));

    // Step 1: the branch.
    await heading('Choose a branch');
    await user.click(await screen.findByRole('link', { name: /^Sandton Summit Mall/ }));

    // Step 2: the service.
    await heading('Choose a service');
    await user.click(
      await screen.findByRole('button', { name: 'Choose update personal details (FICA)' }),
    );

    // Step 3: Tuesday 27 October, at a free time. The booking so far sits under the step's heading.
    const dateHeading = await heading('Choose a date and time');
    const soFar = await screen.findByRole('region', { name: 'Your booking so far' });
    expect(soFar).toHaveTextContent('Sandton Summit Mall');
    expect(dateHeading.compareDocumentPosition(soFar)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    await user.click(await screen.findByRole('button', { name: 'Tuesday 27 October' }));
    const times = await screen.findByRole('group', {
      name: /^Available times on Tuesday 27 October/,
    });
    await user.click(within(times).getByRole('radio', { name: '09:30' }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    // Step 4: the details.
    await heading('Your details');
    await user.type(screen.getByRole('textbox', { name: 'First name' }), 'Nomvula');
    await user.type(screen.getByRole('textbox', { name: 'Last name' }), 'Dlamini');
    await user.type(screen.getByRole('textbox', { name: /^Cellphone number/ }), DEMO_MOBILE);
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    // Step 5: the review, then Confirm.
    await heading('Check and confirm');
    const summary = screen.getByRole('region', { name: 'Your appointment' });
    for (const fact of [
      'Update personal details (FICA)',
      'Sandton Summit Mall',
      'Tuesday 27 October 2026',
      '09:30 to 09:45 SAST',
      'Nomvula Dlamini',
      '082 555 0101',
    ]) {
      expect(summary).toHaveTextContent(fact);
    }
    await user.click(screen.getByRole('button', { name: 'Confirm appointment' }));

    // The confirmation page, at the new booking's reference.
    await heading('Your appointment is confirmed');
    const reference = /^\/appointments\/(APT-[2-9A-Z]{4}-[2-9A-Z]{4})$/.exec(
      router.state.location.pathname,
    )?.[1];
    expect(reference).toBeDefined();
    const referenceCard = screen.getByRole('region', { name: 'Your reference' });
    expect(referenceCard).toHaveTextContent(reference ?? 'no reference');

    const sms = screen.getByRole('region', { name: 'Confirmation SMS' });
    const status = within(sms).getByRole('status');
    expect(status).toHaveTextContent('Sending');
    expect(sms).toHaveTextContent('To 082 *** 0101');

    // The next poll, two seconds on, hears that the SMS was sent.
    delivered = true;
    expect(await within(status).findByText('Sent at 08:00', {}, { timeout: 4000 })).toBeVisible();

    // One booking request, with a key the API can replay it by.
    expect(keys).toHaveLength(1);
    expect(keys[0]).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    );

    // The name and number were typed and shown, but never put in an address.
    expect(urls.length).toBeGreaterThan(5);
    for (const url of urls) {
      for (const text of PERSONAL) {
        expect(url).not.toContain(text);
      }
    }
  }, 15_000);

  it('Back from the review returns to the details step, the details still filled in', async () => {
    const user = userEvent.setup();
    const { router } = renderRoute(
      '/book?branch=SAN&service=FICA&date=2026-10-27&slot=09%3A30&step=details',
    );

    await heading('Your details');
    await user.type(screen.getByRole('textbox', { name: 'First name' }), 'Nomvula');
    await user.type(screen.getByRole('textbox', { name: 'Last name' }), 'Dlamini');
    await user.type(screen.getByRole('textbox', { name: /^Cellphone number/ }), DEMO_MOBILE);
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await heading('Check and confirm');

    // The browser's Back button.
    await act(() => router.navigate(-1));

    expect(await heading('Your details')).toHaveFocus();
    expect(new URLSearchParams(router.state.location.search).get('step')).toBe('details');
    expect(screen.getByRole('textbox', { name: 'First name' })).toHaveValue('Nomvula');
    expect(screen.getByRole('textbox', { name: 'Last name' })).toHaveValue('Dlamini');
    expect(screen.getByRole('textbox', { name: /^Cellphone number/ })).toHaveValue(DEMO_MOBILE);
  });
});
