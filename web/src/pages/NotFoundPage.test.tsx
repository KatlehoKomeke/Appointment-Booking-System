import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';

import { NotFoundPage } from './NotFoundPage';

describe('NotFoundPage', () => {
  it('says calmly that there is no page here, and offers booking and the home page', () => {
    const router = createMemoryRouter([{ path: '*', element: <NotFoundPage /> }], {
      initialEntries: ['/no-such-page'],
    });
    render(<RouterProvider router={router} />);

    expect(screen.getByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument();
    expect(document.title).toBe('Page not found - BranchBook');
    expect(screen.getByRole('link', { name: 'Book an appointment' })).toHaveAttribute(
      'href',
      '/book',
    );
    expect(screen.getByRole('link', { name: 'BranchBook home page' })).toHaveAttribute('href', '/');
  });
});
