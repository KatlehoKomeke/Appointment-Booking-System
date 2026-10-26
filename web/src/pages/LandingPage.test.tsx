import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';

import { LandingPage } from './LandingPage';

function renderLanding() {
  const router = createMemoryRouter(
    [
      { path: '/', element: <LandingPage /> },
      { path: '/book', element: <h1>Choose a branch</h1> },
      { path: '/manage', element: <h1>Find your appointment</h1> },
    ],
    { initialEntries: ['/'] },
  );
  return { router, ...render(<RouterProvider router={router} />) };
}

describe('LandingPage', () => {
  it('says what BranchBook does, and offers booking and managing as links', () => {
    renderLanding();

    expect(
      screen.getByRole('heading', { level: 1, name: 'Book a branch appointment' }),
    ).toBeInTheDocument();
    expect(document.title).toBe('Book a branch appointment - BranchBook');
    expect(screen.getByRole('link', { name: 'Book an appointment' })).toHaveAttribute(
      'href',
      '/book',
    );
    expect(screen.getByRole('link', { name: 'Manage an appointment' })).toHaveAttribute(
      'href',
      '/manage',
    );
  });

  it('explains how it works in three steps, and what to bring', () => {
    renderLanding();

    const howItWorks = screen.getByRole('region', { name: 'How it works' });
    const steps = within(howItWorks).getAllByRole('listitem');
    expect(steps.map((step) => within(step).getByRole('heading').textContent)).toEqual([
      'Choose a branch and a service',
      'Pick a time',
      'Get your reference by SMS',
    ]);
    expect(howItWorks).toHaveTextContent('South African Standard Time (SAST)');

    expect(screen.getByRole('region', { name: 'Before you visit' })).toHaveTextContent(
      'Bring your ID and the documents your service lists, and arrive 10 minutes early. Booking is free.',
    );
  });

  it('starts a booking', async () => {
    const user = userEvent.setup();
    const { router } = renderLanding();

    await user.click(screen.getByRole('link', { name: 'Book an appointment' }));

    expect(router.state.location.pathname).toBe('/book');
  });
});
