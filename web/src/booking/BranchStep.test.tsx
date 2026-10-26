import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';

import { problemResponse } from '../test/handlers';
import { renderWithProviders } from '../test/render';
import { server } from '../test/server';
import { BranchStep } from './BranchStep';

function renderStep() {
  return renderWithProviders(<BranchStep />, { path: '/book' });
}

describe('BranchStep', () => {
  it('lists every branch as a link to the service step, with its place, province and Sunday hours', async () => {
    renderStep();

    expect(screen.getByRole('heading', { level: 1, name: 'Choose a branch' })).toBeInTheDocument();
    expect(document.title).toBe('Step 1 of 5: Branch - BranchBook');
    // The count's region is there, empty, before the list arrives, so the count is announced.
    const status = screen.getByRole('status');
    expect(status).toBeEmptyDOMElement();
    expect(await screen.findByText('6 branches')).toBe(status);

    const list = screen.getByRole('list', { name: 'Branches' });
    const links = within(list).getAllByRole('link');
    expect(links).toHaveLength(6);

    const sandton = within(list).getByRole('link', { name: /Sandton Summit Mall/ });
    expect(sandton).toHaveAttribute('href', '/book?branch=SAN');
    expect(sandton).toHaveTextContent('Sandton, Johannesburg');
    expect(sandton).toHaveTextContent('Gauteng');
    expect(sandton).toHaveTextContent('Open Sundays');

    const bellville = within(list).getByRole('link', { name: /Bellville Kingsway/ });
    expect(bellville).toHaveTextContent('Bellville, Cape Town');
    expect(bellville).not.toHaveTextContent('Open Sundays');
    expect(within(list).getAllByText('Open Sundays')).toHaveLength(2);
  });

  it('filters by name, suburb or city as the customer types, and announces the count', async () => {
    const user = userEvent.setup();
    renderStep();
    await screen.findByRole('link', { name: /Umhlanga Ridge/ });
    const status = screen.getByRole('status');
    const search = screen.getByRole('searchbox', { name: 'Find a branch' });

    const list = screen.getByRole('list', { name: 'Branches' });
    await user.type(search, 'durban');
    expect(status).toHaveTextContent('1 branch');
    const links = within(list).getAllByRole('link');
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAccessibleName(/Umhlanga Ridge/);

    await user.clear(search);
    await user.type(search, '  CAPE town ');
    expect(status).toHaveTextContent('1 branch');
    expect(screen.getByRole('link', { name: /Bellville Kingsway/ })).toBeInTheDocument();

    await user.clear(search);
    await user.type(search, 'Mill Park');
    expect(screen.getByRole('link', { name: /Gqeberha Baywater/ })).toBeInTheDocument();

    await user.clear(search);
    await user.type(search, 'Kimberley');
    expect(status).toHaveTextContent(
      'No branch matches that search. Try a branch name, suburb or city.',
    );
    expect(within(list).queryAllByRole('link')).toEqual([]);

    await user.clear(search);
    expect(status).toHaveTextContent('6 branches');
  });

  it('says so when the branches cannot be loaded, and tries again', async () => {
    const user = userEvent.setup();
    server.use(http.get('*/api/v1/branches', () => problemResponse('INTERNAL_ERROR')));
    renderStep();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong on our side');
    expect(alert).toHaveTextContent('quote reference test-internal_error');

    server.resetHandlers();
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('link', { name: /Sandton Summit Mall/ })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
