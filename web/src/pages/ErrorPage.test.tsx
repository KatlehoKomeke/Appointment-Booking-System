import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';

import { routes } from '../routes';
import { Shell } from '../ui/Shell';
import { ErrorPage } from './ErrorPage';

function Broken(): never {
  throw new Error('a render failure');
}

describe('ErrorPage', () => {
  it('replaces a page that fails to render, inside the shell, with the customer copy and a way on', () => {
    // React reports the caught error on the console; the test is about what the customer sees instead.
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const router = createMemoryRouter(
      [
        {
          element: <Shell />,
          children: [
            { errorElement: <ErrorPage />, children: [{ path: '*', element: <Broken /> }] },
          ],
        },
      ],
      { initialEntries: ['/book'] },
    );
    render(<RouterProvider router={router} />);

    expect(
      screen.getByRole('heading', { level: 1, name: 'Something went wrong on our side' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('complementary', { name: 'About this site' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Start a booking again' })).toHaveAttribute(
      'href',
      '/book',
    );
    expect(screen.queryByText('a render failure')).not.toBeInTheDocument();
  });

  it('is wired into the app routes, under the shell', () => {
    const shell = routes[0];
    expect(shell?.children?.[0]?.errorElement).toEqual(<ErrorPage />);
  });
});
