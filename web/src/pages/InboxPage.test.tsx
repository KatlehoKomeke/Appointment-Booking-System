import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { inboxMessage, inboxMessages } from '../test/fixtures';
import { notFoundResponse, problemResponse } from '../test/handlers';
import { renderRoute } from '../test/render';
import { server } from '../test/server';

describe('InboxPage', () => {
  it('lists every message newest first, with its status, attempts and failure in words', async () => {
    renderRoute('/messages');

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Simulated inbox' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Demo operator view.')).toBeInTheDocument();
    expect(screen.getByText(/every message is simulated/)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    const [pending, sent, failed] = within(
      await screen.findByRole('list', { name: 'Messages' }),
    ).getAllByRole('listitem');
    if (pending === undefined || sent === undefined || failed === undefined) {
      throw new Error('Expected three messages');
    }

    expect(pending).toHaveTextContent('Pending');
    expect(pending).toHaveTextContent('To 082 *** **** · APT-QW7P-3KDM');
    expect(pending).toHaveTextContent('Umhlanga Ridge, Thu 29 Oct 11:00 SAST');
    expect(pending).toHaveTextContent('Created 10:44 SAST · 0 attempts');
    expect(within(pending).getByRole('link', { name: 'APT-QW7P-3KDM' })).toHaveAttribute(
      'href',
      '/appointments/APT-QW7P-3KDM',
    );

    expect(sent).toHaveTextContent('Sent');
    expect(sent).toHaveTextContent('Created 10:42 SAST · sent 10:42 SAST · 1 attempt');

    expect(failed).toHaveTextContent('Failed');
    expect(failed).toHaveTextContent('ReasonDelivery failed after 5 attempts');
    expect(failed).toHaveTextContent('Last errorThe simulated gateway refuses numbers ending 0000');
    expect(failed).toHaveTextContent('Created 10:40 SAST · 5 attempts');

    // Only the message the dispatcher gave up on, and that may still be sent, can be retried.
    expect(within(pending).queryByRole('button')).not.toBeInTheDocument();
    expect(within(sent).queryByRole('button')).not.toBeInTheDocument();
    const retry = within(failed).getByRole('button', {
      name: 'Retry the confirmation SMS to 082 *** ****, APT-H4WQ-8RZD',
    });
    expect(retry).toHaveTextContent(/^Retry$/);
    // Present, and empty, before it has anything to say.
    expect(within(failed).getByRole('status')).toBeEmptyDOMElement();
  });

  it('reads well when every message is still pending', async () => {
    server.use(
      http.get('*/api/v1/messages', () =>
        HttpResponse.json({
          messages: [
            inboxMessage({ id: 'a', status: 'PENDING', attempts: 0, sentAt: null }),
            inboxMessage({
              id: 'b',
              status: 'PENDING',
              attempts: 2,
              sentAt: null,
              lastError: 'SIMULATED_FAILURE',
              nextAttemptAt: '2026-10-26T10:43:00+02:00',
            }),
          ],
        }),
      ),
    );
    renderRoute('/messages');

    const [first, second] = within(
      await screen.findByRole('list', { name: 'Messages' }),
    ).getAllByRole('listitem');
    expect(first).toHaveTextContent('Created 10:42 SAST · 0 attempts');
    expect(first).not.toHaveTextContent('next attempt');
    expect(second).toHaveTextContent('Created 10:42 SAST · 2 attempts · next attempt 10:43 SAST');
    expect(second).toHaveTextContent('Last errorThe simulated gateway refuses numbers ending 0000');
  });

  it('says calmly that the inbox is switched off on this deployment', async () => {
    server.use(http.get('*/api/v1/messages', () => notFoundResponse()));
    renderRoute('/messages');

    expect(await screen.findByText(/switched off on this deployment/)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Messages' })).not.toBeInTheDocument();
  });

  it('says when there are no messages yet', async () => {
    server.use(http.get('*/api/v1/messages', () => HttpResponse.json({ messages: [] })));
    renderRoute('/messages');

    expect(await screen.findByText(/No messages yet/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Book an appointment' })).toHaveAttribute(
      'href',
      '/book',
    );
  });

  it('offers to try again when the inbox cannot be loaded', async () => {
    server.use(http.get('*/api/v1/messages', () => problemResponse('INTERNAL_ERROR')));
    renderRoute('/messages');

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong on our side');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('retries a failed message, says so, and shows it queued again', async () => {
    const user = userEvent.setup();
    let release: () => void = () => undefined;
    const answered = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      // Held until the test has seen the button disabled; then the fake API resets the message.
      http.post('*/api/v1/messages/:id/retry', async () => {
        await answered;
        return undefined;
      }),
    );
    renderRoute('/messages');

    const failed = within(await screen.findByRole('list', { name: 'Messages' })).getAllByRole(
      'listitem',
    )[2];
    if (failed === undefined) {
      throw new Error('Expected three messages');
    }
    const status = within(failed).getByRole('status');
    const retry = within(failed).getByRole('button', { name: /^Retry the confirmation SMS/ });
    await user.click(retry);

    expect(retry).toHaveAttribute('aria-disabled', 'true');
    release();

    expect(await within(failed).findByText('Pending')).toBeInTheDocument();
    expect(status).toHaveTextContent('Queued to send again. Its status updates here.');
    expect(within(failed).queryByRole('button')).not.toBeInTheDocument();
    // The button went with the answer, so its focus moved to the card's heading.
    expect(within(failed).getByRole('heading', { name: 'Confirmation SMS' })).toHaveFocus();
    expect(failed).toHaveTextContent('Created 10:40 SAST · 0 attempts');
  });

  it('explains when the message can no longer be retried', async () => {
    const user = userEvent.setup();
    const message = inboxMessages[2];
    if (message === undefined) {
      throw new Error('Expected the failed message');
    }
    // The appointment was cancelled meanwhile: the API answers with the message unchanged but no longer retryable,
    // and lists it so from then on.
    const unchanged = { ...message, retryable: false };
    let asked = false;
    server.use(
      http.post('*/api/v1/messages/:id/retry', () => {
        asked = true;
        return HttpResponse.json(unchanged);
      }),
      http.get('*/api/v1/messages', () =>
        HttpResponse.json({
          messages: inboxMessages.map((m) => (asked && m.id === message.id ? unchanged : m)),
        }),
      ),
    );
    renderRoute('/messages');

    const retry = await screen.findByRole('button', { name: /^Retry the confirmation SMS/ });
    const card = retry.closest('li');
    if (card === null) {
      throw new Error('Expected the card');
    }
    await user.click(retry);

    expect(await within(card).findByRole('status')).toHaveTextContent(
      'This message can no longer be retried: its appointment was cancelled or has already started.',
    );
    expect(within(card).queryByRole('button')).not.toBeInTheDocument();
    expect(card).toHaveTextContent('Failed');
  });

  it('says why a retry was refused, and leaves the button to try again', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('*/api/v1/messages/:id/retry', async () => {
        await delay(10);
        return problemResponse('MESSAGE_NOT_FOUND');
      }),
    );
    renderRoute('/messages');

    const retry = await screen.findByRole('button', { name: /^Retry the confirmation SMS/ });
    const card = retry.closest('li');
    if (card === null) {
      throw new Error('Expected the card');
    }
    await user.click(retry);

    expect(await within(card).findByText(/We couldn't find that message\./)).toBeInTheDocument();
    expect(within(card).getByRole('status')).toHaveTextContent(
      "We couldn't find that message. Refresh the inbox and try again.",
    );
    expect(retry).toHaveFocus();
    expect(retry).not.toHaveAttribute('aria-disabled');
  });

  it('shows a redacted reference as plain text, not a link', async () => {
    server.use(
      http.get('*/api/v1/messages', () =>
        HttpResponse.json({
          messages: [
            inboxMessage({
              id: '40000000-0000-4000-8000-000000000009',
              reference: 'APT-****-****',
              status: 'FAILED',
              failureReason: 'DELIVERY_FAILED',
              retryable: true,
              attempts: 5,
              sentAt: null,
              createdAt: '2026-10-26T10:40:00+02:00',
            }),
          ],
        }),
      ),
    );
    renderRoute('/messages');

    const [card] = within(await screen.findByRole('list', { name: 'Messages' })).getAllByRole(
      'listitem',
    );
    if (card === undefined) {
      throw new Error('Expected one message');
    }
    expect(card).toHaveTextContent('To 082 *** **** · APT-****-****');
    expect(within(card).queryByRole('link')).not.toBeInTheDocument();
    // A redacted reference does not tell messages apart, so the Retry button's name adds when it was made.
    expect(
      within(card).getByRole('button', {
        name: 'Retry the confirmation SMS to 082 *** ****, APT-****-****, created 10:40 SAST',
      }),
    ).toBeInTheDocument();
  });
});
