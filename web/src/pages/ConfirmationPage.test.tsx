import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { lookupDigits, rememberLookup } from '../booking/bookingSession';
import {
  appointment,
  DEMO_REFERENCE,
  FAILED_NOTIFICATION,
  SENT_NOTIFICATION,
} from '../test/fixtures';
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

  it('asks a new tab for the last four digits once, and remembers them', async () => {
    const user = userEvent.setup();
    renderRoute('/appointments/apt-7k3m-9q2x');

    expect(
      screen.getByRole('heading', { level: 1, name: 'Open your appointment' }),
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
