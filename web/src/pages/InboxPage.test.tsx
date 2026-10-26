import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { inboxMessage } from '../test/fixtures';
import { notFoundResponse, problemResponse } from '../test/handlers';
import { renderRoute } from '../test/render';
import { server } from '../test/server';

describe('InboxPage', () => {
  it('lists every message newest first, with its status, attempts and failure in words', async () => {
    renderRoute('/messages');

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Simulated inbox' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Simulated: no real messages are sent.')).toBeInTheDocument();
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

    // Retrying and cancelling are Tier 2.
    expect(
      within(screen.getByRole('list', { name: 'Messages' })).queryAllByRole('button'),
    ).toHaveLength(0);
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
});
