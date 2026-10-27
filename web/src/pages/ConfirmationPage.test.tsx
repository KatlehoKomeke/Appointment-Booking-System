import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { POLL_INTERVAL_MS } from '../api/queries';
import {
  lookupDigits,
  rememberCancellation,
  rememberedCancellation,
  rememberLookup,
} from '../booking/bookingSession';
import {
  appointment,
  CANCELLED_AT,
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
    expect(smsCard()).toHaveTextContent('BRANCHBOOK · 10:42 SAST');
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

// Counts the times the page asks for the appointment, passing each on to the fake API.
function countOpens() {
  const opens = { count: 0 };
  server.use(
    http.get('*/api/v1/appointments/:reference', () => {
      opens.count += 1;
      return undefined;
    }),
  );
  return opens;
}

// Opens the demo appointment with the remembered digits and cancels it, as the customer does.
async function cancelTheAppointment(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Cancel appointment' }));
  await user.click(within(cancelRegion()).getByRole('button', { name: 'Yes, cancel it' }));
  return screen.findByRole('heading', { level: 1, name: 'This appointment was cancelled' });
}

// Longer than the poll interval, so a poll that was going to happen has happened.
function aPollLater() {
  return new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS + 300));
}

const REMEMBERED = {
  cancelledAt: CANCELLED_AT,
  branch: { code: 'SAN', name: 'Sandton Summit Mall' },
  service: { code: 'FICA', name: 'Update personal details (FICA)' },
  slot: { startsAt: '2026-10-27T09:30:00+02:00', endsAt: '2026-10-27T09:45:00+02:00' },
};

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

  it('cancels with the remembered digits, then says the details are deleted and the SMS is on its way', async () => {
    const user = userEvent.setup();
    rememberLookup(DEMO_REFERENCE, '0101');
    const sent = countCancels();
    renderRoute(PATH);

    // The confirm button is gone, so its focus moves to the heading, which says what happened.
    expect(await cancelTheAppointment(user)).toHaveFocus();
    expect(sent).toEqual(['0101']);
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(
      screen.getByText(
        'Its details have been deleted. A cancellation SMS is on its way to 082 *** 0101.',
      ),
    ).toBeInTheDocument();

    const details = screen.getByRole('region', { name: 'Your appointment' });
    expect(details).toHaveTextContent('Cancelled');
    expect(details).toHaveTextContent('Update personal details (FICA)');
    expect(details).toHaveTextContent('Sandton Summit Mall');
    expect(details).toHaveTextContent('Tuesday 27 October 2026');
    expect(details).toHaveTextContent('09:30 to 09:45 SAST');
    expect(details).toHaveTextContent('08:00 SAST on Monday 26 October 2026');
    expect(details).toHaveTextContent('Its time is free again for someone else to book.');
    expect(details).not.toHaveTextContent('Please arrive 10 minutes early.');

    // Nothing is left to open, watch or cancel: no reference, no SMS and no live status.
    expect(screen.queryByRole('button', { name: 'Cancel appointment' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Your reference' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Cancellation SMS' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Confirmation SMS' })).not.toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(
      screen.getByRole('link', {
        name: 'Book this service again: Update personal details (FICA) at Sandton Summit Mall',
      }),
    ).toHaveAttribute('href', '/book?branch=SAN&service=FICA');
    expect(screen.getByRole('link', { name: 'Book another appointment' })).toHaveAttribute(
      'href',
      '/book',
    );

    // The tab forgets the digits and keeps only what the view needs, with no name and no number.
    expect(lookupDigits(DEMO_REFERENCE)).toBeUndefined();
    expect(rememberedCancellation(DEMO_REFERENCE)).toEqual(REMEMBERED);
  });

  it('stops asking once the cancel is sent, since the server deletes the appointment', async () => {
    const user = userEvent.setup();
    rememberLookup(DEMO_REFERENCE, '0101');
    const opens = countOpens();
    renderRoute(PATH);

    // The confirmation SMS is still PENDING, so the page polls until the customer cancels.
    await user.click(await screen.findByRole('button', { name: 'Cancel appointment' }));
    const asked = opens.count;
    await user.click(within(cancelRegion()).getByRole('button', { name: 'Yes, cancel it' }));
    await screen.findByRole('heading', { level: 1, name: 'This appointment was cancelled' });
    await aPollLater();

    // A poll now would find nothing, and must not turn the page into "not found".
    expect(opens.count).toBe(asked);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(
      screen.getByRole('heading', { level: 1, name: 'This appointment was cancelled' }),
    ).toBeInTheDocument();
  }, 10_000);

  it('names the number only when the answer gives it', async () => {
    const user = userEvent.setup();
    rememberLookup(DEMO_REFERENCE, '0101');
    server.use(
      http.post('*/api/v1/appointments/:reference/cancel', () =>
        HttpResponse.json(cancelledAppointment(appointment(), { notifications: [] })),
      ),
    );
    renderRoute(PATH);

    await cancelTheAppointment(user);

    expect(
      screen.getByText(
        'Its details have been deleted. A cancellation SMS is on its way to the number you booked with.',
      ),
    ).toBeInTheDocument();
  });

  it('shows the cancellation again after a refresh, from what this tab remembers', async () => {
    const user = userEvent.setup();
    rememberLookup(DEMO_REFERENCE, '0101');
    const { unmount } = renderRoute(PATH);
    await cancelTheAppointment(user);
    unmount();

    const opens = countOpens();
    renderRoute('/appointments/apt-7k3m-9q2x');

    expect(
      await screen.findByRole('heading', { level: 1, name: 'This appointment was cancelled' }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        'Its details have been deleted. A cancellation SMS was sent to the number you booked with.',
      ),
    ).toBeInTheDocument();
    const details = screen.getByRole('region', { name: 'Your appointment' });
    expect(details).toHaveTextContent('Update personal details (FICA)');
    expect(details).toHaveTextContent('Sandton Summit Mall');
    expect(details).toHaveTextContent('08:00 SAST on Monday 26 October 2026');
    // The tab kept when the appointment was for, which identifies no one, so the date and time are still shown.
    expect(details).toHaveTextContent('Tuesday 27 October 2026');
    expect(details).toHaveTextContent('09:30 to 09:45 SAST');
    expect(
      screen.getByRole('link', {
        name: 'Book this service again: Update personal details (FICA) at Sandton Summit Mall',
      }),
    ).toHaveAttribute('href', '/book?branch=SAN&service=FICA');
    // The digits are forgotten, and the server would find nothing, so the page neither asks for them nor asks it.
    expect(
      screen.queryByRole('textbox', { name: 'Last four digits of your cellphone number' }),
    ).not.toBeInTheDocument();
    expect(opens.count).toBe(0);
  });

  it('shows the cancellation when the server finds nothing for one this tab cancelled, and only then', async () => {
    rememberCancellation(DEMO_REFERENCE, REMEMBERED);
    rememberLookup(DEMO_REFERENCE, '0101');
    server.use(
      http.get('*/api/v1/appointments/:reference', () => problemResponse('APPOINTMENT_NOT_FOUND')),
    );
    const { unmount } = renderRoute(PATH);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'This appointment was cancelled' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    unmount();

    // Another tab, which opened the appointment but did not cancel it, gets the usual answer.
    sessionStorage.clear();
    rememberLookup(DEMO_REFERENCE, '0101');
    renderRoute(PATH);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      "We couldn't find an appointment with those details.",
    );
    expect(screen.getByRole('heading', { level: 1, name: 'Your appointment' })).toBeInTheDocument();
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

  it('shows an appointment the server calls cancelled the same way, and never polls it', async () => {
    rememberLookup(DEMO_REFERENCE, '0101');
    let opens = 0;
    server.use(
      http.get('*/api/v1/appointments/:reference', () => {
        opens += 1;
        return HttpResponse.json(cancelledAppointment());
      }),
    );
    renderRoute(PATH);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'This appointment was cancelled' }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        'Its details have been deleted. A cancellation SMS is on its way to 082 *** 0101.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel appointment' })).not.toBeInTheDocument();
    // Its cancellation SMS is still PENDING, but there is nothing to watch.
    await aPollLater();
    expect(opens).toBe(1);
  }, 10_000);

  it('offers no cancel on an appointment that has started', async () => {
    rememberLookup(DEMO_REFERENCE, '0101');
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
