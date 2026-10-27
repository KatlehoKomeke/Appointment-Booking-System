import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, describe, expect, it, type MockInstance, vi } from 'vitest';

import { Shell } from './Shell';

function renderShell(path = '/') {
  const router = createMemoryRouter(
    [
      {
        element: <Shell />,
        children: [
          { index: true, element: <h1>Home</h1> },
          { path: 'privacy', element: <h1>Privacy notice</h1> },
          { path: 'messages', element: <h1>Simulated inbox</h1> },
        ],
      },
    ],
    { initialEntries: [path] },
  );
  return { router, ...render(<RouterProvider router={router} />) };
}

describe('Shell', () => {
  let scrollTo: MockInstance<typeof window.scrollTo>;

  beforeEach(() => {
    // jsdom does not scroll; the router's scroll restoration calls this on each navigation.
    scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
  });

  it('puts the banner, header, page and footer in their landmarks, in that order', () => {
    renderShell();

    const about = screen.getByRole('complementary', { name: 'About this site' });
    expect(about).toHaveTextContent(
      "Fynbos Bank is fictional. BranchBook is an engineering assessment, not a real bank's service. The hosted demo texts the number you give, so use only your own; the demo numbers 082 555 0000 to 082 555 0199 are never texted. While AWS keeps the demo in its SMS sandbox, only numbers verified on its account receive the text; any other shows Not delivered, and the booking still stands.",
    );

    const header = screen.getByRole('banner');
    expect(within(header).getByRole('link', { name: 'BranchBook by Fynbos Bank' })).toHaveAttribute(
      'href',
      '/',
    );
    expect(within(header).getByText('Demo')).toBeInTheDocument();

    const main = screen.getByRole('main');
    expect(within(main).getByRole('heading', { level: 1, name: 'Home' })).toBeInTheDocument();

    const footer = screen.getByRole('contentinfo');
    const footerNav = within(footer).getByRole('navigation', { name: 'Footer' });
    expect(within(footerNav).getByRole('link', { name: 'Simulated inbox' })).toHaveAttribute(
      'href',
      '/messages',
    );
    expect(within(footerNav).getByRole('link', { name: 'Privacy' })).toHaveAttribute(
      'href',
      '/privacy',
    );
    expect(footer).toHaveTextContent(
      'Fynbos Bank is fictional. Branch hours are representative, not official.',
    );

    const order = [about, header, main, footer];
    order.slice(1).forEach((landmark, index) => {
      expect(order[index]?.compareDocumentPosition(landmark)).toBe(
        Node.DOCUMENT_POSITION_FOLLOWING,
      );
    });
  });

  it('has the skip link as the first stop, and it moves focus to the page', async () => {
    const user = userEvent.setup();
    renderShell();

    await user.tab();
    const skip = screen.getByRole('link', { name: 'Skip to main content' });
    expect(skip).toHaveFocus();

    await user.keyboard('{Enter}');
    expect(screen.getByRole('main')).toHaveFocus();
  });

  it('starts each new page at the top', async () => {
    const user = userEvent.setup();
    const { router } = renderShell();
    scrollTo.mockClear();

    await user.click(screen.getByRole('link', { name: 'Privacy' }));

    expect(router.state.location.pathname).toBe('/privacy');
    expect(screen.getByRole('heading', { level: 1, name: 'Privacy notice' })).toBeInTheDocument();
    expect(scrollTo).toHaveBeenCalledWith(0, 0);
    expect(screen.getByRole('link', { name: 'Privacy' })).toHaveAttribute('aria-current', 'page');
  });
});
