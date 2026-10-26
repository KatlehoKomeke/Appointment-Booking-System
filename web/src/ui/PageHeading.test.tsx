import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState, type ReactNode } from 'react';
import { createMemoryRouter, Link, RouterProvider, type RouteObject } from 'react-router';
import { describe, expect, it } from 'vitest';

import { PageHeading } from './PageHeading';

function renderRoutes(routes: RouteObject[], path = '/') {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  return render(<RouterProvider router={router} />);
}

function renderInRouter(ui: ReactNode) {
  return renderRoutes([{ path: '/', element: ui }]);
}

describe('PageHeading', () => {
  it("is the page's h1 and names the tab after it", () => {
    renderInRouter(<PageHeading title="Privacy notice" />);

    expect(screen.getByRole('heading', { level: 1, name: 'Privacy notice' })).toBeInTheDocument();
    expect(document.title).toBe('Privacy notice - BranchBook');
  });

  it('names the tab by docTitle when it is given', () => {
    renderInRouter(
      <PageHeading title="Choose a date and time" docTitle="Step 3 of 5: Date and time" />,
    );

    expect(document.title).toBe('Step 3 of 5: Date and time - BranchBook');
  });

  it('leaves focus alone on the first page the browser loads', () => {
    renderInRouter(<PageHeading title="Book a branch appointment" />);

    expect(screen.getByRole('heading', { level: 1 })).not.toHaveFocus();
  });

  it('takes focus when its focusKey changes', async () => {
    const user = userEvent.setup();

    function Steps() {
      const [step, setStep] = useState(1);
      return (
        <>
          <PageHeading title={`Step ${String(step)}`} focusKey={step} />
          <button
            type="button"
            onClick={() => {
              setStep(step + 1);
            }}
          >
            Continue
          </button>
        </>
      );
    }

    renderInRouter(<Steps />);
    const heading = screen.getByRole('heading', { level: 1, name: 'Step 1' });
    expect(heading).not.toHaveFocus();

    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(screen.getByRole('heading', { level: 1, name: 'Step 2' })).toHaveFocus();
  });

  it('takes focus when it arrives with an in-app navigation', async () => {
    const user = userEvent.setup();
    // Two page components, as in the app, so the second page mounts its own heading.
    function Home() {
      return (
        <>
          <PageHeading title="Home" />
          <Link to="/privacy">Privacy</Link>
        </>
      );
    }
    function Privacy() {
      return <PageHeading title="Privacy notice" />;
    }
    renderRoutes([
      { path: '/', element: <Home /> },
      { path: '/privacy', element: <Privacy /> },
    ]);

    await user.click(screen.getByRole('link', { name: 'Privacy' }));

    expect(screen.getByRole('heading', { level: 1, name: 'Privacy notice' })).toHaveFocus();
    expect(document.title).toBe('Privacy notice - BranchBook');
  });
});
