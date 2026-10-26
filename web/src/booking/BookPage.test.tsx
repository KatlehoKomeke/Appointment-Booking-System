import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { DEMO_MOBILE, NOW } from '../test/fixtures';
import { renderRoute } from '../test/render';
import { saveDetails } from './bookingSession';

const CHOSEN = '/book?branch=SAN&service=FICA&date=2026-10-27&slot=09:30';

function stepper() {
  return screen.getByRole('navigation', { name: 'Booking steps' });
}

// The booking flow's code is fetched the first time /book opens, so a test waits for its stepper before reading it.
function stepperLoaded() {
  return screen.findByRole('navigation', { name: 'Booking steps' });
}

// The step the stepper says the customer is on, such as "Step 3 of 5".
function stepShown() {
  return within(stepper()).getByText(/^Step \d of 5$/).textContent;
}

// A link's target as the customer would read it: the URL encodes the colon in a time as %3A.
function target(link: HTMLElement) {
  return decodeURIComponent(link.getAttribute('href') ?? '');
}

function param(router: ReturnType<typeof renderRoute>['router'], name: string) {
  return new URLSearchParams(router.state.location.search).get(name);
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(NOW));
});

afterEach(() => {
  vi.useRealTimers();
});

describe('BookPage', () => {
  it('goes from the branch list through a service to the date and time step, the choices in the URL', async () => {
    const user = userEvent.setup();
    const { router } = renderRoute('/book');

    await stepperLoaded();
    expect(stepShown()).toBe('Step 1 of 5');
    expect(within(stepper()).getByRole('link', { name: 'Back to home' })).toHaveAttribute(
      'href',
      '/',
    );
    await user.click(await screen.findByRole('link', { name: /Sandton Summit Mall/ }));

    const serviceHeading = await screen.findByRole('heading', {
      level: 1,
      name: 'Choose a service',
    });
    expect(serviceHeading).toHaveFocus();
    expect(document.title).toBe('Step 2 of 5: Service - BranchBook');
    expect(stepShown()).toBe('Step 2 of 5');
    expect(router.state.location.search).toBe('?branch=SAN');

    await user.click(
      await screen.findByRole('button', { name: 'Choose update personal details (FICA)' }),
    );

    await waitFor(() => {
      expect(stepShown()).toBe('Step 3 of 5');
    });
    expect(param(router, 'branch')).toBe('SAN');
    expect(param(router, 'service')).toBe('FICA');
    expect(document.title).toBe('Step 3 of 5: Date and time - BranchBook');
    const soFar = await screen.findByRole('region', { name: 'Your booking so far' });
    expect(soFar).toHaveTextContent('Sandton Summit Mall');
    expect(soFar).toHaveTextContent('Update personal details (FICA)');
  });

  it('Back names the previous step and returns to it, keeping the earlier choices', async () => {
    const user = userEvent.setup();
    const { router } = renderRoute('/book?branch=SAN&service=FICA');

    const toService = within(await stepperLoaded()).getByRole('link', { name: 'Back to service' });
    expect(toService).toHaveTextContent('Service');
    await user.click(toService);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Choose a service' }),
    ).toBeInTheDocument();
    expect(router.state.location.search).toBe('?branch=SAN');

    await user.click(within(stepper()).getByRole('link', { name: 'Back to branch' }));
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Choose a branch' }),
    ).toBeInTheDocument();
    expect(router.state.location.search).toBe('');
  });

  it('Back from the details step returns to the time step with the time still chosen', async () => {
    renderRoute(`${CHOSEN}&step=details`);

    await stepperLoaded();
    expect(stepShown()).toBe('Step 4 of 5');
    expect(target(within(stepper()).getByRole('link', { name: 'Back to date and time' }))).toBe(
      CHOSEN,
    );
  });

  it('Change links go back to their step, clearing only what depended on the choice', async () => {
    const user = userEvent.setup();
    const { router } = renderRoute(`${CHOSEN}&step=details`);

    const soFar = await screen.findByRole('region', { name: 'Your booking so far' });
    expect(soFar).toHaveTextContent('Tue 27 Oct, 09:30 to 09:45 SAST');

    await user.click(within(soFar).getByRole('link', { name: 'Change date and time' }));
    await waitFor(() => {
      expect(stepShown()).toBe('Step 3 of 5');
    });
    expect(decodeURIComponent(router.state.location.search)).toBe(CHOSEN.slice('/book'.length));

    await user.click(
      within(await screen.findByRole('region', { name: 'Your booking so far' })).getByRole('link', {
        name: 'Change service',
      }),
    );
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Choose a service' }),
    ).toBeInTheDocument();
    expect(router.state.location.search).toBe('?branch=SAN');

    await user.click(screen.getByRole('link', { name: 'Change branch' }));
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Choose a branch' }),
    ).toBeInTheDocument();
    expect(router.state.location.search).toBe('');
  });

  it('shows the details step for a review this tab has no details for, and corrects the URL', async () => {
    const { router } = renderRoute(`${CHOSEN}&step=review`);

    await stepperLoaded();
    expect(stepShown()).toBe('Step 4 of 5');
    await waitFor(() => {
      expect(param(router, 'step')).toBe('details');
    });
    expect(param(router, 'slot')).toBe('09:30');
  });

  it('shows the review once the details are saved', async () => {
    saveDetails({ firstName: 'Nomvula', lastName: 'Dlamini', mobile: DEMO_MOBILE });
    const { router } = renderRoute(`${CHOSEN}&step=review`);

    await stepperLoaded();
    expect(stepShown()).toBe('Step 5 of 5');
    expect(target(within(stepper()).getByRole('link', { name: 'Back to your details' }))).toBe(
      `${CHOSEN}&step=details`,
    );
    expect(param(router, 'step')).toBe('review');
    expect(screen.queryByRole('region', { name: 'Your booking so far' })).not.toBeInTheDocument();
  });

  it('shows the branch list, with the reason, for a branch code the API does not know', async () => {
    const user = userEvent.setup();
    const { router } = renderRoute('/book?branch=XYZ&service=FICA&date=2026-10-27');

    // The time step's own request for this branch can fail first, with an alert of its own, before the page moves
    // to the branch list; so wait for the step, then read the alert.
    await waitFor(() => {
      expect(stepShown()).toBe('Step 1 of 5');
    });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent("We couldn't find that branch.");
    expect(alert).toHaveTextContent('Choose a branch from the list.');
    expect(screen.getByRole('heading', { level: 1, name: 'Choose a branch' })).toBeInTheDocument();

    await user.click(await screen.findByRole('link', { name: /Sandton Summit Mall/ }));
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Choose a service' }),
    ).toBeInTheDocument();
    expect(router.state.location.search).toBe('?branch=SAN');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('treats a branch code that is not a code at all as unknown', async () => {
    renderRoute('/book?branch=S4N&service=FICA');

    // As above: the step first, since the time step's request can fail before the page moves on.
    await waitFor(() => {
      expect(stepShown()).toBe('Step 1 of 5');
    });
    expect(await screen.findByRole('alert')).toHaveTextContent("We couldn't find that branch.");
  });

  it("shows the branch's services, with the reason, for a service the branch does not offer", async () => {
    const user = userEvent.setup();
    const { router } = renderRoute('/book?branch=GQE&service=HOME&date=2026-10-27&slot=09:00');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent("This branch doesn't offer that service.");
    expect(alert).toHaveTextContent('Choose another service, or another branch.');
    expect(stepShown()).toBe('Step 2 of 5');

    await user.click(screen.getByRole('button', { name: 'Choose card replacement or collection' }));
    await waitFor(() => {
      expect(stepShown()).toBe('Step 3 of 5');
    });
    expect(param(router, 'service')).toBe('CARD');
    expect(param(router, 'slot')).toBeNull();
  });
});
