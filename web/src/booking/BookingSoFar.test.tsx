import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../test/render';
import { BookingSoFar } from './BookingSoFar';

describe('BookingSoFar', () => {
  it('on the time step, shows the branch and the service with its length, each with a Change link', async () => {
    renderWithProviders(<BookingSoFar />, {
      path: '/book?branch=SAN&service=FICA&date=2026-10-27&slot=09:30',
    });

    const soFar = await screen.findByRole('region', { name: 'Your booking so far' });
    expect(soFar).toHaveTextContent('Sandton Summit Mall');
    expect(soFar).toHaveTextContent('Update personal details (FICA)');
    expect(soFar).toHaveTextContent('About 15 minutes');
    // The chosen time is repeated above Continue on this step, so it is not here yet.
    expect(soFar).not.toHaveTextContent('09:30');

    expect(within(soFar).getByRole('link', { name: 'Change branch' })).toHaveAttribute(
      'href',
      '/book',
    );
    expect(within(soFar).getByRole('link', { name: 'Change service' })).toHaveAttribute(
      'href',
      '/book?branch=SAN',
    );
    expect(
      within(soFar).queryByRole('link', { name: 'Change date and time' }),
    ).not.toBeInTheDocument();
  });

  it('after the time step, adds the date and time in SAST, and Change keeps the time chosen', async () => {
    renderWithProviders(<BookingSoFar />, {
      path: '/book?branch=SAN&service=FICA&date=2026-10-27&slot=09:30&step=details',
    });

    const soFar = await screen.findByRole('region', { name: 'Your booking so far' });
    expect(soFar).toHaveTextContent('Tue 27 Oct, 09:30 to 09:45 SAST');
    expect(soFar).not.toHaveTextContent('About 15 minutes');
    // The URL encodes the colon in the time.
    expect(within(soFar).getByRole('link', { name: 'Change date and time' })).toHaveAttribute(
      'href',
      '/book?branch=SAN&service=FICA&date=2026-10-27&slot=09%3A30',
    );
    expect(within(soFar).getByRole('link', { name: 'Change service' })).toHaveAttribute(
      'href',
      '/book?branch=SAN',
    );
  });

  it('works out the end from the service: a 45-minute home loan at 16:00 ends at 16:45', async () => {
    renderWithProviders(<BookingSoFar />, {
      path: '/book?branch=UMH&service=HOME&date=2026-10-29&slot=16:00&step=review',
    });

    expect(await screen.findByRole('region', { name: 'Your booking so far' })).toHaveTextContent(
      'Thu 29 Oct, 16:00 to 16:45 SAST',
    );
  });
});
