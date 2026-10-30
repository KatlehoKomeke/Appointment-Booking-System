import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';

import { DEMO_MOBILE, DEMO_REFERENCE } from '../test/fixtures';
import {
  captchaChallenge,
  captchaConfigured,
  FAKE_CAPTCHA_KEY,
  FAKE_CAPTCHA_SCRIPT,
  FAKE_PUZZLE_TEXT,
  installFakeCaptcha,
} from '../test/fakeCaptcha';
import { renderRoute } from '../test/render';
import { server } from '../test/server';
import { rememberLookup, saveDetails } from '../booking/bookingSession';

const REVIEW = '/book?branch=SAN&service=FICA&date=2026-10-27&slot=09%3A30&step=review';

interface Sent {
  key: string | null;
  body: string;
}

// Has the firewall challenge the first `challenges` booking POSTs, recording every POST's key and body, and lets the
// rest through to the fake API.
function challengeBookings(challenges = 1): Sent[] {
  const sent: Sent[] = [];
  server.use(
    http.post('*/api/v1/appointments', async ({ request }) => {
      sent.push({
        key: request.headers.get('Idempotency-Key'),
        body: await request.clone().text(),
      });
      return sent.length <= challenges ? captchaChallenge() : undefined;
    }),
  );
  return sent;
}

// Has the firewall challenge the first `challenges` cancel POSTs, counting every one.
function challengeCancels(challenges = 1): { count: number } {
  const cancels = { count: 0 };
  server.use(
    http.post('*/api/v1/appointments/:reference/cancel', () => {
      cancels.count += 1;
      return cancels.count <= challenges ? captchaChallenge() : undefined;
    }),
  );
  return cancels;
}

async function openReviewAndConfirm(user: ReturnType<typeof userEvent.setup>) {
  saveDetails({ firstName: 'Nomvula', lastName: 'Dlamini', mobile: DEMO_MOBILE });
  renderRoute(REVIEW);
  const confirm = await screen.findByRole('button', { name: 'Confirm appointment' });
  await user.click(confirm);
  return confirm;
}

async function theCheck() {
  return screen.findByRole('dialog', { name: 'A quick check first' });
}

describe('the WAF CAPTCHA, when booking', () => {
  it('shows the puzzle when the firewall challenges the booking, then books with the same key once it is solved', async () => {
    const user = userEvent.setup();
    captchaConfigured();
    const captcha = installFakeCaptcha();
    const sent = challengeBookings();

    const confirm = await openReviewAndConfirm(user);

    const dialog = await theCheck();
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAccessibleDescription(
      'To keep out automated requests, please solve the puzzle below. Nothing has been sent yet; we carry on as soon as it is solved.',
    );
    expect(within(dialog).getByRole('heading', { name: 'A quick check first' })).toHaveFocus();
    // The page behind cannot be reached while the check is open.
    expect(confirm.closest('[inert]')).not.toBeNull();

    // The script is added once, and the puzzle drawn inside the dialog with the configured key.
    expect(await within(dialog).findByText('The puzzle is ready.')).toBeInTheDocument();
    expect(within(dialog).getByText(FAKE_PUZZLE_TEXT)).toBeInTheDocument();
    expect(captcha.scripts()).toEqual([FAKE_CAPTCHA_SCRIPT]);
    expect(captcha.rendered).toHaveLength(1);
    expect(captcha.rendered[0]?.options.apiKey).toBe(FAKE_CAPTCHA_KEY);
    expect(sent).toHaveLength(1);

    captcha.solve();

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Your appointment is confirmed' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.querySelector('[inert]')).toBeNull();
    expect(screen.getByText('Check complete. Carrying on.')).toHaveAttribute('role', 'status');

    // The same request, sent once more: the same key and the same body, so it can never book twice.
    expect(sent).toHaveLength(2);
    expect(sent[1]).toEqual(sent[0]);
    expect(sent[0]?.key).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('says nothing was sent when the customer closes the check with Escape, and sends nothing more', async () => {
    const user = userEvent.setup();
    captchaConfigured();
    installFakeCaptcha();
    const sent = challengeBookings();

    await openReviewAndConfirm(user);
    const dialog = await theCheck();
    await within(dialog).findByText('The puzzle is ready.');
    await user.keyboard('{Escape}');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The check was closed before it was finished');
    expect(alert).toHaveTextContent(
      'Nothing was sent. Try again, and solve the puzzle when it appears.',
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByText('Check closed. Nothing was sent.')).toHaveAttribute('role', 'status');
    expect(sent).toHaveLength(1);
    // Confirm is free again, to try once more.
    expect(screen.getByRole('button', { name: 'Confirm appointment' })).not.toHaveAttribute(
      'aria-disabled',
    );
  });

  it('says so when the puzzle fails, and sends nothing more', async () => {
    const user = userEvent.setup();
    captchaConfigured();
    const captcha = installFakeCaptcha();
    const sent = challengeBookings();

    await openReviewAndConfirm(user);
    await within(await theCheck()).findByText('The puzzle is ready.');
    captcha.fail();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent("We couldn't complete the check");
    expect(alert).toHaveTextContent('Nothing was sent. Please try again.');
    expect(sent).toHaveLength(1);
  });

  it('says the check could not be loaded when its script fails, and removes the script for the next try', async () => {
    const user = userEvent.setup();
    captchaConfigured();
    const captcha = installFakeCaptcha({ load: 'fails' });
    const sent = challengeBookings();

    await openReviewAndConfirm(user);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent("We couldn't load the check");
    expect(alert).toHaveTextContent(
      'Nothing was sent. Check your internet connection, then try again.',
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(captcha.scripts()).toEqual([]);
    expect(captcha.rendered).toEqual([]);
    expect(sent).toHaveLength(1);
  });

  it('treats a challenge with no CAPTCHA configured as an ordinary failure, showing no puzzle', async () => {
    const user = userEvent.setup();
    // The fake API's own settings: no CAPTCHA, as on a local run.
    const captcha = installFakeCaptcha();
    const sent = challengeBookings();

    await openReviewAndConfirm(user);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong on our side');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(captcha.scripts()).toEqual([]);
    expect(sent).toHaveLength(1);
  });
});

describe('the WAF CAPTCHA, when cancelling', () => {
  function cancelRegion() {
    return screen.getByRole('region', { name: 'Cancel this appointment?' });
  }

  it('shows the puzzle when the firewall challenges the cancel, then cancels once it is solved', async () => {
    const user = userEvent.setup();
    rememberLookup(DEMO_REFERENCE, '0101');
    captchaConfigured();
    const captcha = installFakeCaptcha();
    const cancels = challengeCancels();
    renderRoute(`/appointments/${DEMO_REFERENCE}`);

    await user.click(await screen.findByRole('button', { name: 'Cancel appointment' }));
    await user.click(within(cancelRegion()).getByRole('button', { name: 'Yes, cancel it' }));
    await within(await theCheck()).findByText('The puzzle is ready.');
    expect(cancels.count).toBe(1);

    captcha.solve();

    expect(
      await screen.findByRole('heading', { level: 1, name: 'This appointment was cancelled' }),
    ).toBeInTheDocument();
    expect(cancels.count).toBe(2);
  });

  it('gives focus back to "Yes, cancel it" when the check is closed, and keeps the appointment', async () => {
    const user = userEvent.setup();
    rememberLookup(DEMO_REFERENCE, '0101');
    captchaConfigured();
    installFakeCaptcha();
    const cancels = challengeCancels();
    renderRoute(`/appointments/${DEMO_REFERENCE}`);

    await user.click(await screen.findByRole('button', { name: 'Cancel appointment' }));
    await user.click(within(cancelRegion()).getByRole('button', { name: 'Yes, cancel it' }));
    const dialog = await theCheck();
    await user.click(within(dialog).getByRole('button', { name: 'Close the check' }));

    expect(await within(cancelRegion()).findByRole('alert')).toHaveTextContent(
      'The check was closed before it was finished',
    );
    await waitFor(() => {
      expect(within(cancelRegion()).getByRole('button', { name: 'Yes, cancel it' })).toHaveFocus();
    });
    expect(
      screen.getByRole('heading', { level: 1, name: 'Your appointment is confirmed' }),
    ).toBeInTheDocument();
    expect(cancels.count).toBe(1);
  });
});
