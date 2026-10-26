import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { availability, DEMO_DATE, NOW } from '../test/fixtures';
import { renderWithProviders } from '../test/render';
import { server } from '../test/server';
import { DateTimeStep, STALE_AFTER_MS } from './DateTimeStep';

const SAN_FICA = '/book?branch=SAN&service=FICA';

function renderStep(query = '') {
  return renderWithProviders(<DateTimeStep />, { path: `${SAN_FICA}${query}` });
}

function params(router: ReturnType<typeof renderStep>['router']) {
  return Object.fromEntries(new URLSearchParams(router.state.location.search));
}

// The action bar's line, whose times are in a <strong> of their own.
function choiceLine(text: string) {
  return screen.getByText((_, element) => element?.tagName === 'P' && element.textContent === text);
}

async function timesFor(day: string) {
  return screen.findByRole('group', { name: new RegExp(`^Available times on ${day}`) });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(NOW));
});

afterEach(() => {
  vi.useRealTimers();
});

describe('DateTimeStep', () => {
  it('opens on the first open date without adding a history entry, and marks today', async () => {
    const { router } = renderStep();

    await waitFor(() => {
      expect(params(router).date).toBe('2026-10-26');
    });
    expect(router.state.historyAction).toBe('REPLACE');
    expect(screen.getByRole('heading', { level: 1, name: 'Choose a date and time' })).toBeVisible();
    expect(document.title).toBe('Step 3 of 5: Date and time - BranchBook');

    expect(screen.getByRole('heading', { name: '26 October to 1 November 2026' })).toBeVisible();
    const today = screen.getByRole('button', { name: 'Monday 26 October, today' });
    // The router's state moves first; React draws the chosen date in a transition just after.
    await waitFor(() => {
      expect(today).toHaveAttribute('aria-pressed', 'true');
    });
    expect(today).toHaveTextContent('Today');
    // Today's first time is two hours after 08:00.
    const times = await timesFor('Monday 26 October');
    expect(within(times).getAllByRole('radio')[0]).toHaveAccessibleName('10:00');
  });

  it('chooses a date and a time into the URL and the action bar, and only then continues', async () => {
    const user = userEvent.setup();
    const { router } = renderStep('&date=2026-10-26');
    await timesFor('Monday 26 October');

    const continueButton = screen.getByRole('button', { name: 'Continue' });
    expect(continueButton).toBeDisabled();
    expect(choiceLine('Choose a time to continue.')).toBeVisible();

    await user.click(screen.getByRole('button', { name: 'Tuesday 27 October' }));
    expect(params(router).date).toBe(DEMO_DATE);
    expect(router.state.historyAction).toBe('REPLACE');
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Tuesday 27 October' })).toHaveAttribute(
        'aria-pressed',
        'true',
      );
    });

    const times = await timesFor('Tuesday 27 October');
    expect(times).toHaveAccessibleName(
      'Available times on Tuesday 27 October In South African Standard Time (SAST)',
    );
    await user.click(within(times).getByRole('radio', { name: '09:30' }));

    expect(params(router)).toMatchObject({ date: DEMO_DATE, slot: '09:30' });
    expect(params(router).step).toBeUndefined();
    expect(router.state.historyAction).toBe('REPLACE');
    expect(within(times).getByRole('radio', { name: '09:30' })).toBeChecked();
    expect(choiceLine('Tue 27 Oct, 09:30 to 09:45 SAST')).toBeVisible();
    expect(continueButton).toBeEnabled();

    await user.click(continueButton);
    expect(params(router)).toMatchObject({ date: DEMO_DATE, slot: '09:30', step: 'details' });
    expect(router.state.historyAction).toBe('PUSH');
  });

  it('shows full times as disabled, the last seat as "1 left", and the chosen one as Selected', async () => {
    const user = userEvent.setup();
    renderStep(`&date=${DEMO_DATE}`);
    const times = await timesFor('Tuesday 27 October');

    expect(within(times).getByRole('group', { name: 'Morning' })).toBeVisible();
    expect(within(times).getByRole('group', { name: 'Afternoon' })).toBeVisible();
    expect(within(times).getAllByRole('radio')).toHaveLength(36);

    const full = within(times).getByRole('radio', { name: '08:30, Full' });
    expect(full).toBeDisabled();
    expect(within(times).getAllByRole('radio', { name: /, Full$/ })).toHaveLength(5);

    const lastSeat = within(times).getByRole('radio', { name: '08:45, 1 left' });
    expect(lastSeat).toBeEnabled();
    expect(within(times).getAllByRole('radio', { name: /, 1 left$/ })).toHaveLength(6);

    await user.click(lastSeat);
    // "Selected" is for the eye; a screen reader says "checked" and still hears the last seat.
    expect(lastSeat).toBeChecked();
    expect(lastSeat).toHaveAccessibleName('08:45, 1 left');
    const tile = lastSeat.closest('label');
    expect(tile).toHaveTextContent('08:45Selected');
    expect(within(tile ?? document.body).getByText('Selected')).toHaveAttribute(
      'aria-hidden',
      'true',
    );
  });

  it('marks the election day closed on the second page of dates, with the reason under the strip', async () => {
    const user = userEvent.setup();
    const { router } = renderStep(`&date=${DEMO_DATE}`);
    await timesFor('Tuesday 27 October');

    await user.click(screen.getByRole('button', { name: 'Next 7 days' }));
    expect(screen.getByRole('heading', { name: '2 to 8 November 2026' })).toBeVisible();

    const election = screen.getByRole('button', {
      name: 'Wednesday 4 November, closed: public holiday, Local Government Elections',
    });
    expect(election).toBeDisabled();
    expect(election).toHaveTextContent('Holiday');
    expect(
      screen.getByText('Wed 4 Nov: closed. Public holiday: Local Government Elections.'),
    ).toBeVisible();

    await user.click(screen.getByRole('button', { name: 'Tuesday 3 November' }));
    expect(params(router).date).toBe('2026-11-03');
    await timesFor('Tuesday 3 November');
    expect(screen.getByRole('heading', { name: '2 to 8 November 2026' })).toBeVisible();
  });

  it('pages only within the booking window', async () => {
    const user = userEvent.setup();
    renderStep(`&date=${DEMO_DATE}`);
    await timesFor('Tuesday 27 October');

    const previous = screen.getByRole('button', { name: 'Previous 7 days' });
    const next = screen.getByRole('button', { name: 'Next 7 days' });
    expect(previous).toHaveAttribute('aria-disabled', 'true');
    await user.click(previous);
    expect(screen.getByRole('heading', { name: '26 October to 1 November 2026' })).toBeVisible();

    for (let page = 0; page < 4; page += 1) {
      await user.click(next);
    }
    // 30 dates from 26 October: four weeks, then the last two days.
    expect(screen.getByRole('heading', { name: '23 to 24 November 2026' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Tuesday 24 November' })).toBeVisible();
    expect(screen.queryByRole('button', { name: /^Wednesday 25 November/ })).toBeNull();
    expect(next).toHaveAttribute('aria-disabled', 'true');
    expect(previous).toHaveAttribute('aria-disabled', 'false');

    await user.click(next);
    expect(screen.getByRole('heading', { name: '23 to 24 November 2026' })).toBeVisible();
    expect(next).toHaveFocus();
  });

  it('gives a closed day its reason instead of times, and points a full day to the next one', async () => {
    const user = userEvent.setup();
    const { unmount } = renderStep('&date=2026-11-04');

    expect(
      await screen.findByRole('heading', { name: 'The branch is closed on Wednesday 4 November' }),
    ).toBeVisible();
    expect(screen.getByText(/Local Government Elections\. Choose another date\./)).toBeVisible();
    expect(screen.queryByRole('radio')).toBeNull();
    unmount();

    server.use(
      http.get('*/api/v1/branches/:code/availability', ({ request }) => {
        const date = new URL(request.url).searchParams.get('date') ?? '';
        const day = availability('SAN', 'FICA', date);
        return HttpResponse.json(
          date === DEMO_DATE
            ? { ...day, slots: day.slots.map((slot) => ({ ...slot, seatsAvailable: 0 })) }
            : day,
        );
      }),
    );
    const { router } = renderStep(`&date=${DEMO_DATE}`);
    expect(await screen.findByText(/Every time on Tuesday 27 October is taken\./)).toBeVisible();
    expect(screen.getAllByRole('radio').every((radio) => radio.hasAttribute('disabled'))).toBe(
      true,
    );
    await user.click(screen.getByRole('button', { name: 'See Wednesday 28 October' }));
    await timesFor('Wednesday 28 October');
    expect(params(router).date).toBe('2026-10-28');
    // The See button has gone with the old day's message, so focus goes to the date it chose.
    expect(screen.getByRole('button', { name: 'Wednesday 28 October' })).toHaveFocus();
  });

  it('shows times in SAST whatever zone the device is in', async () => {
    renderStep(`&date=${DEMO_DATE}`);
    const times = await timesFor('Tuesday 27 October');

    const radios = within(times).getAllByRole('radio');
    expect(radios[0]).toHaveAccessibleName('08:00');
    expect(radios[radios.length - 1]).toHaveAccessibleName('16:45');
    expect(within(times).getByText('Times as of 08:00.')).toBeVisible();
  });

  it('says the times may have changed after ten minutes without activity, and Refresh fetches them again', async () => {
    vi.useFakeTimers({
      toFake: ['Date', 'setTimeout', 'clearTimeout'],
      shouldAdvanceTime: true,
    });
    vi.setSystemTime(new Date(NOW));
    let requests = 0;
    server.use(
      http.get('*/api/v1/branches/:code/availability', () => {
        requests += 1;
        // By the second request, 09:30 has gone.
        return HttpResponse.json(
          availability('SAN', 'FICA', DEMO_DATE, requests > 1 ? { seats: { '09:30': 0 } } : {}),
        );
      }),
    );
    const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) });
    renderStep(`&date=${DEMO_DATE}&slot=09:30`);
    const times = await timesFor('Tuesday 27 October');
    expect(within(times).getByRole('radio', { name: '09:30' })).toBeChecked();

    const status = screen.getByRole('status');
    expect(status).toBeEmptyDOMElement();

    // Activity restarts the wait.
    act(() => {
      vi.advanceTimersByTime(STALE_AFTER_MS - 60_000);
    });
    await user.keyboard('{Shift}');
    act(() => {
      vi.advanceTimersByTime(STALE_AFTER_MS - 60_000);
    });
    expect(status).toBeEmptyDOMElement();

    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    expect(status).toHaveTextContent('Times may have changed. Refresh to see the latest.');

    await user.click(within(status).getByRole('button', { name: 'Refresh' }));
    expect(status).toBeEmptyDOMElement();
    expect(screen.getByRole('button', { name: 'Refresh' })).toHaveFocus();
    expect(await within(times).findByRole('radio', { name: '09:30, Full' })).toBeDisabled();
    expect(requests).toBe(2);
    expect(choiceLine('Choose a time to continue.')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();
  });
});
