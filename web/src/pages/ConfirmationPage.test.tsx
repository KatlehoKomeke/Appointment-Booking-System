import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { lookupDigits, rememberLookup } from '../booking/bookingSession';
import {
  appointment,
  cancelledAppointment,
  DEMO_REFERENCE,
  FAILED_NOTIFICATION,
  SENT_NOTIFICATION,
} from '../test/fixtures';
import { problemResponse } from '../test/handlers';
import { renderRoute } from '../test/render';
import { server } from '../test/server';

const PATH = `/appointments/${DEMO_REFERENCE}`;

function smsCard() {
  return screen.getByRole('region', { name: 'Confirmation SMS' });
}

describe('ConfirmationPage', () => {
  it('shows the booking, and moves the SMS from Sending to Sent by polling', async () => {
    rememberLookup(DEMO_REFERENCE, '0101');
    let calls = 0;
    server.use(
      http.get('*/api/v1/appointments/:reference', () => {
        calls += 1;
        return HttpResponse.json(
          calls === 1 ? appointment() : appointment({ notifications: [SENT_NOTIFICATION] }),
        );
      }),
    );
    renderRoute(PATH);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Your appointment is confirmed' }),
    ).toBeInTheDocument();
    expect(screen.getByText(DEMO_REFERENCE)).toBeInTheDocument();
    const details = screen.getByRole('region', { name: 'Your appointment' });
    expect(details).toHaveTextContent('Update personal details (FICA)');
    expect(details).toHaveTextContent('Sandton Summit Mall');
    expect(details).toHaveTextContent('Tuesday 27 October 2026');
    expect(details).toHaveTextContent('09:30 to 09:45 SAST');
    expect(within(details).getAllByRole('listitem').length).toBeGreaterThan(0);

    const status = within(smsCard()).getByRole('status');
    expect(status).toHaveTextContent('Sending');
    expect(smsCard()).toHaveTextContent('To 082 *** 0101');

    expect(await within(status).findByText('Sent at 10:42', {}, { timeout: 4000 })).toBeVisible();
    expect(smsCard()).toHaveTextContent('Ref APT-7K3M-9Q2X');
    expect(smsCard()).toHaveTextContent('BRANCHBOOK · 10:42 · simulated');
    expect(within(smsCard()).getByRole('button', { name: 'Hide the message' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    expect(
      screen.getByRole('link', { name: 'See the SMS in the simulated inbox' }),
    ).toHaveAttribute('href', '/messages');
  }, 10_000);

  it('says calmly that the SMS was not delivered, and that the booking stands', async () => {
    rememberLookup(DEMO_REFERENCE, '0101');
    server.use(
      http.get('*/api/v1/appointments/:reference', () =>
        HttpResponse.json(appointment({ notifications: [FAILED_NOTIFICATION] })),
      ),
    );
    renderRoute(PATH);

    const status = await within(
      await screen.findByRole('region', { name: 'Confirmation SMS' }),
    ).findByRole('status');
    expect(status).toHaveTextContent('Not delivered');
    expect(smsCard()).toHaveTextContent(
      'We could not send the SMS after 5 attempts. Your booking still stands; keep your reference.',
    );
    expect(
      within(smsCard()).getByRole('link', { name: 'See what happened in the simulated inbox' }),
    ).toHaveAttribute('href', '/messages');
  });

  it('copies the reference, and says so in a status region that was there, empty, before', async () => {
    const user = userEvent.setup();
    rememberLookup(DEMO_REFERENCE, '0101');
    renderRoute(PATH);

    const card = await screen.findByRole('region', { name: 'Your reference' });
    const announcement = within(card).getByRole('status');
    expect(announcement).toBeEmptyDOMElement();

    await user.click(within(card).getByRole('button', { name: 'Copy reference' }));

    expect(within(card).getByRole('button', { name: 'Copied' })).toBeInTheDocument();
    expect(announcement).toHaveTextContent('Reference copied.');
    expect(await navigator.clipboard.readText()).toBe(DEMO_REFERENCE);
  });

  it('books the same service at the same branch again, from the date and time step', async () => {
    const user = userEvent.setup();
    rememberLookup(DEMO_REFERENCE, '0101');
    const { router } = renderRoute(PATH);

    // The visible words start the name; the rest names the service and branch for a screen reader's list of links.
    const again = await screen.findByRole('link', {
      name: 'Book this service again: Update personal details (FICA) at Sandton Summit Mall',
    });
    expect(again).toHaveTextContent(/^Book this service again$/);
    expect(again).toHaveAttribute('href', '/book?branch=SAN&service=FICA');
    expect(screen.getByRole('link', { name: 'Book another appointment' })).toHaveAttribute(
      'href',
      '/book',
    );

    await user.click(again);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Choose a date and time' }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/book');
    expect(router.state.location.search).toBe('?branch=SAN&service=FICA');
  });

  it('asks a new tab for the last four digits once, and remembers them', async () => {
    const user = userEvent.setup();
    renderRoute('/appointments/apt-7k3m-9q2x');

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Open your appointment' }),
    ).toBeInTheDocument();
    expect(document.title).toBe('Open your appointment - BranchBook');
    const digits = screen.getByRole('textbox', {
      name: 'Last four digits of your cellphone number',
    });

    await user.type(digits, '1234');
    await user.click(screen.getByRole('button', { name: 'Open appointment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      "We couldn't find an appointment with those details.",
    );

    await user.clear(digits);
    await user.type(digits, '0101');
    await user.click(screen.getByRole('button', { name: 'Open appointment' }));

    // The form's button is gone, so its focus moves to the new heading rather than to the page's body.
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Your appointment is confirmed' }),
    ).toHaveFocus();
    expect(document.title).toBe('Your appointment is confirmed - BranchBook');
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(lookupDigits(DEMO_REFERENCE)).toBe('0101');
  });

  it('sends the customer to /manage when the appointment cannot be opened', async () => {
    rememberLookup(DEMO_REFERENCE, '9999');
    renderRoute(PATH);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      "We couldn't find an appointment with those details.",
    );
    expect(screen.getByRole('link', { name: 'Find your appointment' })).toHaveAttribute(
      'href',
      '/manage',
    );
  });
});

function cancelRegion() {
  return screen.getByRole('region', { name: 'Cancel this appointment?' });
}

// Counts the cancels the page sends, passing each on to the fake API.
function countCancels() {
  const sent: (string | null)[] = [];
  server.use(
    http.post('*/api/v1/appointments/:reference/cancel', ({ request }) => {
      sent.push(request.headers.get('X-Mobile-Last4'));
      return undefined;
    }),
  );
  return sent;
}

describe('ConfirmationPage, cancelling', () => {
  it('asks first, naming the appointment, and keeps it when the customer says so', async () => {
    const user = userEvent.setup();
    rememberLookup(DEMO_REFERENCE, '0101');
    const sent = countCancels();
    renderRoute(PATH);

    const trigger = await screen.findByRole('button', { name: 'Cancel appointment' });
    await user.click(trigger);

    const region = cancelRegion();
    expect(within(region).getByRole('heading', { name: 'Cancel this appointment?' })).toHaveFocus();
    expect(region).toHaveTextContent('Update personal details (FICA)');
    expect(region).toHaveTextContent('at Sandton Summit Mall');
    expect(region).toHaveTextContent('Tuesday 27 October 2026, 09:30 to 09:45 SAST');
    expect(region).toHaveTextContent(
      'Cancelling releases the time for someone else to book. It cannot be undone.',
    );

    await user.click(within(region).getByRole('button', { name: 'Keep my appointment' }));

    expect(
      screen.queryByRole('region', { name: 'Cancel this appointment?' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel appointment' })).toHaveFocus();
    expect(sent).toEqual([]);
  });

  it('keeps the appointment on Escape, and gives focus back to the button', async () => {
    const user = userEvent.setup();
    rememberLookup(DEMO_REFERENCE, '0101');
    const sent = countCancels();
    renderRoute(PATH);

    await user.click(await screen.findByRole('button', { name: 'Cancel appointment' }));
    await user.tab();
    expect(within(cancelRegion()).getByRole('button', { name: 'Yes, cancel it' })).toHaveFocus();
    await user.keyboard('{Escape}');

    expect(
      screen.queryByRole('region', { name: 'Cancel this appointment?' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel appointment' })).toHaveFocus();
    expect(sent).toEqual([]);
  });

  it('cancels with the remembered digits, then shows the cancellation calmly', async () => {
    const user = userEvent.setup();
    rememberLookup(DEMO_REFERENCE, '0101');
    const sent = countCancels();
    renderRoute(PATH);

    await user.click(await screen.findByRole('button', { name: 'Cancel appointment' }));
    await user.click(within(cancelRegion()).getByRole('button', { name: 'Yes, cancel it' }));

    // The confirm button is gone, so its focus moves to the heading, which says what happened.
    expect(
      await screen.findByRole('heading', { level: 1, name: 'This appointment was cancelled' }),
    ).toHaveFocus();
    expect(sent).toEqual(['0101']);
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(
      screen.getByText(
        'Cancelled at 08:00 SAST on Monday 26 October 2026. Its time is free again for someone else to book.',
      ),
    ).toBeInTheDocument();

    const details = screen.getByRole('region', { name: 'Your appointment' });
    expect(details).toHaveTextContent('Cancelled');
    expect(details).toHaveTextContent('Cancelled: this time is free for someone else.');
    expect(details).not.toHaveTextContent('Please arrive 10 minutes early.');

    const cancellation = screen.getByRole('region', { name: 'Cancellation SMS' });
    expect(within(cancellation).getByRole('status')).toHaveTextContent('Sending');
    expect(cancellation).toHaveTextContent('your cancellation is already saved');

    // The confirmation was stopped, not failed: no "Not delivered", and no failure to look into.
    const confirmation = smsCard();
    expect(within(confirmation).getByRole('status')).toHaveTextContent('Not sent');
    expect(confirmation).not.toHaveTextContent('Not delivered');
    expect(confirmation).toHaveTextContent(
      'Not sent: the appointment was cancelled before this SMS went out.',
    );
    expect(
      within(confirmation).queryByRole('link', {
        name: 'See what happened in the simulated inbox',
      }),
    ).not.toBeInTheDocument();

    expect(screen.queryByRole('button', { name: 'Cancel appointment' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: /^Book this service again/ })).toHaveAttribute(
      'href',
      '/book?branch=SAN&service=FICA',
    );
  });

  it('retries a 503 once, after Retry-After, before it shows anything', async () => {
    const user = userEvent.setup();
    rememberLookup(DEMO_REFERENCE, '0101');
    let calls = 0;
    server.use(
      http.post('*/api/v1/appointments/:reference/cancel', () => {
        calls += 1;
        return calls === 1 ? problemResponse('TRY_AGAIN', {}, { 'Retry-After': '0' }) : undefined;
      }),
    );
    renderRoute(PATH);

    await user.click(await screen.findByRole('button', { name: 'Cancel appointment' }));
    await user.click(within(cancelRegion()).getByRole('button', { name: 'Yes, cancel it' }));

    expect(
      await screen.findByRole('heading', { level: 1, name: 'This appointment was cancelled' }),
    ).toBeInTheDocument();
    expect(calls).toBe(2);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it.each([
    [
      'LOOKUP_RATE_LIMITED',
      () => problemResponse('LOOKUP_RATE_LIMITED', {}, { 'Retry-After': '3600' }),
      'Too many attempts. Try again later.',
    ],
    [
      'TRY_AGAIN twice',
      () => problemResponse('TRY_AGAIN', {}, { 'Retry-After': '0' }),
      "We couldn't finish that just now",
    ],
    ['a network failure', () => HttpResponse.error(), "We couldn't reach BranchBook"],
  ])('words %s by its code, and keeps the step open to try again', async (_, answer, title) => {
    const user = userEvent.setup();
    rememberLookup(DEMO_REFERENCE, '0101');
    server.use(http.post('*/api/v1/appointments/:reference/cancel', answer));
    renderRoute(PATH);

    await user.click(await screen.findByRole('button', { name: 'Cancel appointment' }));
    const confirm = within(cancelRegion()).getByRole('button', { name: 'Yes, cancel it' });
    await user.click(confirm);

    expect(await within(cancelRegion()).findByRole('alert')).toHaveTextContent(title);
    expect(confirm).toHaveFocus();
    expect(confirm).not.toHaveAttribute('aria-disabled');
    expect(
      screen.getByRole('heading', { level: 1, name: 'Your appointment is confirmed' }),
    ).toBeInTheDocument();
  });

  it('says the appointment has already started when it is too late, and offers no cancel', async () => {
    const user = userEvent.setup();
    rememberLookup(DEMO_REFERENCE, '0101');
    let started = false;
    server.use(
      http.post('*/api/v1/appointments/:reference/cancel', () => {
        started = true;
        return problemResponse('TOO_LATE_TO_CANCEL');
      }),
      http.get('*/api/v1/appointments/:reference', () =>
        HttpResponse.json(appointment({ canCancel: !started, notifications: [SENT_NOTIFICATION] })),
      ),
    );
    renderRoute(PATH);

    await user.click(await screen.findByRole('button', { name: 'Cancel appointment' }));
    await user.click(within(cancelRegion()).getByRole('button', { name: 'Yes, cancel it' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('This appointment has already started.');
    expect(alert).toHaveTextContent('It can no longer be cancelled.');
    expect(alert.parentElement).toHaveFocus();
    expect(screen.queryByRole('button', { name: 'Cancel appointment' })).not.toBeInTheDocument();
    expect(
      screen.queryByRole('region', { name: 'Cancel this appointment?' }),
    ).not.toBeInTheDocument();
  });

  it('offers no cancel on an appointment that is already cancelled, or has started', async () => {
    rememberLookup(DEMO_REFERENCE, '0101');
    server.use(
      http.get('*/api/v1/appointments/:reference', () =>
        HttpResponse.json(
          cancelledAppointment(appointment({ notifications: [SENT_NOTIFICATION] })),
        ),
      ),
    );
    const { unmount } = renderRoute(PATH);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'This appointment was cancelled' }),
    ).toBeInTheDocument();
    // A confirmation already sent stays sent.
    expect(within(smsCard()).getByRole('status')).toHaveTextContent('Sent at 10:42');
    expect(screen.queryByRole('button', { name: 'Cancel appointment' })).not.toBeInTheDocument();
    unmount();

    server.use(
      http.get('*/api/v1/appointments/:reference', () =>
        HttpResponse.json(appointment({ canCancel: false, notifications: [SENT_NOTIFICATION] })),
      ),
    );
    renderRoute(PATH);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Your appointment is confirmed' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel appointment' })).not.toBeInTheDocument();
  });
});
