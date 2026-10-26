import { render, screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';

import { Stepper } from './Stepper';

function renderInRouter(ui: ReactNode) {
  const router = createMemoryRouter([{ path: '*', element: ui }], { initialEntries: ['/book'] });
  return render(<RouterProvider router={router} />);
}

describe('Stepper', () => {
  it('names the previous step on the Back link and marks the current step', () => {
    renderInRouter(<Stepper current={3} backTo="/book?branch=SAN" backLabel="Service" />);

    const nav = screen.getByRole('navigation', { name: 'Booking steps' });
    const back = within(nav).getByRole('link', { name: 'Back to service' });
    expect(back).toHaveAttribute('href', '/book?branch=SAN');
    expect(back).toHaveTextContent('Service');
    expect(within(nav).getByText('Step 3 of 5')).toBeInTheDocument();

    const segments = within(nav).getAllByRole('listitem');
    expect(segments).toHaveLength(5);
    expect(segments.map((segment) => segment.getAttribute('aria-current'))).toEqual([
      null,
      null,
      'step',
      null,
      null,
    ]);
    expect(segments[0]).toHaveTextContent('Branch, done');
  });

  it('takes the label it is given, such as "Your details" on the review step', () => {
    renderInRouter(<Stepper current={5} backTo="/book?step=details" backLabel="Your details" />);

    expect(screen.getByRole('link', { name: 'Back to your details' })).toHaveTextContent(
      'Your details',
    );
    expect(screen.getByText('Step 5 of 5')).toBeInTheDocument();
  });

  it('goes home from the first step', () => {
    renderInRouter(<Stepper current={1} />);

    expect(screen.getByRole('link', { name: 'Back to home' })).toHaveAttribute('href', '/');
  });
});
