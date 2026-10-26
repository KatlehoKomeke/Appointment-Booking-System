import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../test/render';
import { ServiceStep } from './ServiceStep';

function renderStep(branch: string) {
  return renderWithProviders(<ServiceStep />, { path: `/book?branch=${branch}` });
}

// The branch's hours, a row each, as "Days: hours".
function hoursRows() {
  const hours = screen.getByLabelText('Opening hours');
  const times = within(hours).getAllByRole('definition');
  return within(hours)
    .getAllByRole('term')
    .map((days, index) => `${days.textContent}: ${times[index]?.textContent ?? ''}`);
}

describe('ServiceStep', () => {
  it("shows the branch's address and its hours, Monday first", async () => {
    renderStep('SAN');

    const branch = await screen.findByRole('region', { name: 'Sandton Summit Mall' });
    expect(screen.getByRole('heading', { level: 1, name: 'Choose a service' })).toBeInTheDocument();
    expect(document.title).toBe('Step 2 of 5: Service - BranchBook');
    expect(within(branch).getByText('Shop 4, 1 Protea Lane, Sandton, 2196')).toBeInTheDocument();
    expect(hoursRows()).toEqual([
      'Mon to Fri: 08:00 to 17:00',
      'Saturday: 08:00 to 13:00',
      'Sunday: 09:00 to 13:00',
    ]);
    expect(within(branch).getByRole('link', { name: 'Change branch' })).toHaveAttribute(
      'href',
      '/book',
    );
  });

  it('says which days a branch is closed', async () => {
    renderStep('RUS');

    await screen.findByRole('region', { name: 'Rustenburg Bojanala' });
    expect(hoursRows()).toEqual([
      'Mon to Fri: 08:00 to 17:00',
      'Saturday: 08:00 to 12:00',
      'Sunday: Closed',
    ]);
  });

  it('lists each service with its duration and what to bring, and Choose sets the service', async () => {
    const user = userEvent.setup();
    const { router } = renderStep('SAN');

    const services = await screen.findByRole('list', { name: 'Services at Sandton Summit Mall' });
    const cards = within(services)
      .getAllByRole('listitem')
      .filter((li) => li.parentElement === services);
    expect(cards).toHaveLength(6);

    const fica = cards[0];
    if (fica === undefined) {
      throw new Error('No FICA card');
    }
    expect(
      within(fica).getByRole('heading', { name: 'Update personal details (FICA)' }),
    ).toBeInTheDocument();
    expect(fica).toHaveTextContent('About 15 minutes');
    // What to bring opens without choosing the service.
    const toBring = within(fica).getByText('Valid SA ID or passport with permit');
    expect(toBring).not.toBeVisible();
    await user.click(within(fica).getByText('What to bring'));
    expect(toBring).toBeVisible();
    expect(within(fica).getByText('Supporting document for the change, if any')).toBeVisible();
    expect(router.state.location.search).toBe('?branch=SAN');

    expect(screen.getByRole('heading', { name: 'Home loan consultation' })).toBeInTheDocument();
    expect(screen.getAllByText('About 30 minutes')).toHaveLength(3);

    await user.click(
      within(fica).getByRole('button', { name: 'Choose update personal details (FICA)' }),
    );
    expect(new URLSearchParams(router.state.location.search).get('service')).toBe('FICA');
    expect(new URLSearchParams(router.state.location.search).get('branch')).toBe('SAN');
  });

  it('offers only the services the branch has', async () => {
    renderStep('GQE');

    const services = await screen.findByRole('list', { name: 'Services at Gqeberha Baywater' });
    expect(within(services).getAllByRole('button', { name: /^Choose / })).toHaveLength(5);
    expect(
      screen.queryByRole('button', { name: 'Choose home loan consultation' }),
    ).not.toBeInTheDocument();
  });
});
