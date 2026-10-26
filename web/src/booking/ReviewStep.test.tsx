import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { DEMO_MOBILE } from '../test/fixtures';
import { problemResponse } from '../test/handlers';
import { renderWithProviders } from '../test/render';
import { server } from '../test/server';
import { useBookingParams } from './bookingParams';
import { loadDetails, lookupDigits, saveDetails } from './bookingSession';
import { DetailsStep } from './DetailsStep';
import { ReviewStep } from './ReviewStep';

const BOOKINGS = '*/api/v1/appointments';
const REVIEW = '/book?branch=SAN&service=FICA&date=2026-10-27&slot=09%3A30&step=review';

// The last two steps as the booking page switches between them, without the rest of the page.
function LastSteps() {
  const { step } = useBookingParams();
  if (step === 'details') {
    return <DetailsStep />;
  }
  if (step === 'review') {
    return <ReviewStep />;
  }
  return <p>Step {step}</p>;
}

function renderReview() {
  saveDetails({ firstName: 'Nomvula', lastName: 'Dlamini', mobile: DEMO_MOBILE });
  return renderWithProviders(<LastSteps />, { path: REVIEW });
}

// Records the Idempotency-Key of every booking request, then lets the request through to the next handler. Call it
// after any other override, so it sees every request first.
function recordBookingKeys(): string[] {
  const keys: string[] = [];
  server.use(
    http.post(BOOKINGS, ({ request }) => {
      keys.push(request.headers.get('Idempotency-Key') ?? '');
    }),
  );
  return keys;
}

async function confirmButton() {
  return screen.findByRole('button', { name: 'Confirm appointment' });
}

describe('ReviewStep', () => {
  it('shows every fact once, each with an Edit link back to its step', async () => {
    renderReview();

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Check and confirm' }),
    ).toBeInTheDocument();
    expect(document.title).toBe('Step 5 of 5: Review - BranchBook');
    const summary = await screen.findByRole('region', { name: 'Your appointment' });
    for (const text of [
      'Update personal details (FICA)',
      'About 15 minutes',
      'Sandton Summit Mall',
      'Shop 4, 1 Protea Lane, Sandton, 2196',
      'Tuesday 27 October 2026',
      '09:30 to 09:45 SAST',
      'Nomvula Dlamini',
      '082 555 0101',
    ]) {
      expect(within(summary).getByText(text)).toBeInTheDocument();
    }
    expect(screen.getByRole('link', { name: 'Edit service' })).toHaveAttribute(
      'href',
      '/book?branch=SAN',
    );
    expect(screen.getByRole('link', { name: 'Edit branch' })).toHaveAttribute('href', '/book');
    expect(screen.getByRole('link', { name: 'Edit date and time' })).toHaveAttribute(
      'href',
      '/book?branch=SAN&service=FICA&date=2026-10-27&slot=09%3A30',
    );
    expect(screen.getByRole('link', { name: 'Edit your details' })).toHaveAttribute(
      'href',
      '/book?branch=SAN&service=FICA&date=2026-10-27&slot=09%3A30&step=details',
    );

    const bring = screen.getByRole('region', { name: 'What to bring' });
    expect(
      within(bring)
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual([
      'Valid SA ID or passport with permit',
      'Supporting document for the change, if any',
    ]);
    expect(await confirmButton()).toHaveAccessibleDescription(
      'We will SMS your confirmation to 082 555 0101.',
    );
  });

  it('books once, however often Confirm is pressed, and opens the confirmation', async () => {
    const user = userEvent.setup();
    const keys = recordBookingKeys();
    const { router } = renderReview();

    await user.dblClick(await confirmButton());

    await waitFor(() => {
      expect(router.state.location.pathname).toMatch(/^\/appointments\/APT-/);
    });
    expect(router.state.historyAction).toBe('REPLACE');
    expect(keys).toHaveLength(1);
    const reference = router.state.location.pathname.split('/').pop() ?? '';
    expect(lookupDigits(reference)).toBe('0101');
    // The booking in progress is forgotten, so the next one in the tab starts afresh.
    expect(loadDetails()).toBeUndefined();
  });

  it('says it is booking while the request is out, and ignores Confirm meanwhile', async () => {
    const user = userEvent.setup();
    const gate: { open?: () => void } = {};
    const answered = new Promise<void>((resolve) => {
      gate.open = resolve;
    });
    // Holds every booking until the test releases it, then lets it through to the default handler.
    server.use(
      http.post(BOOKINGS, async () => {
        await answered;
      }),
    );
    const keys = recordBookingKeys();
    const { router } = renderReview();

    const status = await screen.findByRole('status');
    expect(status).toBeEmptyDOMElement();
    await user.click(await confirmButton());

    const busy = screen.getByRole('button', { name: 'Confirming…' });
    expect(busy).toHaveAttribute('aria-disabled', 'true');
    expect(status).toHaveTextContent('Confirming your appointment.');
    await user.click(busy);

    gate.open?.();
    await waitFor(() => {
      expect(router.state.location.pathname).toMatch(/^\/appointments\//);
    });
    expect(keys).toHaveLength(1);
  });

  it('offers the later times when the time was just taken, and books the one chosen', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(
        BOOKINGS,
        () =>
          problemResponse('SLOT_FULL', {
            alternatives: ['09:45', '10:00', '10:15'].map((time) => ({
              startsAt: `2026-10-27T${time}:00+02:00`,
              endsAt: `2026-10-27T${time}:00+02:00`,
            })),
          }),
        { once: true },
      ),
    );
    const keys = recordBookingKeys();
    const { router } = renderReview();

    await user.click(await confirmButton());

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText('That time was just taken')).toBeInTheDocument();
    expect(alert).toHaveTextContent(
      'The last seat at 09:30 was booked while you were filling in your details. Your details are saved. Choose another time on Tuesday 27 October:',
    );
    expect(within(alert).getAllByRole('button')).toHaveLength(3);
    for (const time of ['09:45', '10:00', '10:15']) {
      expect(within(alert).getByRole('button', { name: `${time} SAST instead` })).toHaveTextContent(
        time,
      );
    }
    expect(within(alert).getByRole('link', { name: 'See all times' })).toHaveAttribute(
      'href',
      '/book?branch=SAN&service=FICA&date=2026-10-27',
    );
    expect(screen.getByText('No longer free')).toBeInTheDocument();

    // Confirm waits for a new time.
    const confirm = screen.getByRole('button', { name: 'Confirm appointment' });
    expect(confirm).toHaveAttribute('aria-disabled', 'true');
    expect(confirm).toHaveAccessibleDescription('Choose a new time to confirm.');
    await user.click(confirm);
    expect(keys).toHaveLength(1);

    await user.click(screen.getByRole('button', { name: '09:45 SAST instead' }));
    expect(router.state.location.search).toContain('slot=09%3A45');
    expect(router.state.location.search).toContain('step=review');
    expect(router.state.historyAction).toBe('REPLACE');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText('09:45 to 10:00 SAST')).toBeInTheDocument();
    expect(confirm).toHaveFocus();
    expect(confirm).not.toHaveAttribute('aria-disabled');

    await user.click(confirm);
    await waitFor(() => {
      expect(router.state.location.pathname).toMatch(/^\/appointments\//);
    });
    // Another time is another request, so it gets its own key.
    expect(keys).toHaveLength(2);
    expect(keys[1]).not.toBe(keys[0]);
  });

  it('retries a TRY_AGAIN once with the same key, and books', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(BOOKINGS, () => problemResponse('TRY_AGAIN', {}, { 'Retry-After': '0' }), {
        once: true,
      }),
    );
    const keys = recordBookingKeys();
    const { router } = renderReview();

    await user.click(await confirmButton());

    await waitFor(() => {
      expect(router.state.location.pathname).toMatch(/^\/appointments\//);
    });
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it('asks to try again after a second TRY_AGAIN, and tries again with the same key', async () => {
    const user = userEvent.setup();
    server.use(http.post(BOOKINGS, () => problemResponse('TRY_AGAIN', {}, { 'Retry-After': '0' })));
    const keys = recordBookingKeys();
    renderReview();

    await user.click(await confirmButton());

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent("We couldn't finish that just now");
    expect(alert).toHaveTextContent(
      'Nothing was booked twice. Please try again in a moment. If it keeps happening, quote reference test-try_again.',
    );
    await user.click(screen.getByRole('button', { name: 'Confirm appointment' }));
    await waitFor(() => {
      expect(keys).toHaveLength(4);
    });
    expect(new Set(keys).size).toBe(1);
  });

  it('gives a changed detail a new key', async () => {
    const user = userEvent.setup();
    let calls = 0;
    server.use(
      http.post(BOOKINGS, () => {
        calls += 1;
        // The first attempt and its retry fail; after that, the default handler books.
        return calls <= 2 ? problemResponse('TRY_AGAIN', {}, { 'Retry-After': '0' }) : undefined;
      }),
    );
    const keys = recordBookingKeys();
    const { router } = renderReview();

    await user.click(await confirmButton());
    await screen.findByRole('alert');

    await user.click(screen.getByRole('link', { name: 'Edit your details' }));
    const lastName = await screen.findByRole('textbox', { name: 'Last name' });
    expect(lastName).toHaveValue('Dlamini');
    await user.clear(lastName);
    await user.type(lastName, 'Dlamini-Khumalo');
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByText('Nomvula Dlamini-Khumalo')).toBeInTheDocument();
    await user.click(await confirmButton());
    await waitFor(() => {
      expect(router.state.location.pathname).toMatch(/^\/appointments\//);
    });
    expect(keys).toHaveLength(3);
    expect(keys[1]).toBe(keys[0]);
    expect(keys[2]).not.toBe(keys[0]);
  });

  it('sends a refused detail back to its field on the details step', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(
        BOOKINGS,
        () =>
          problemResponse('VALIDATION_FAILED', {
            errors: [
              { field: 'customer.lastName', code: 'NAME_INVALID', message: 'must be a name' },
            ],
          }),
        { once: true },
      ),
    );
    const { router } = renderReview();

    await user.click(await confirmButton());

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Your details' }),
    ).toBeInTheDocument();
    expect(router.state.location.search).toContain('step=details');
    expect(screen.getByRole('alert')).toHaveTextContent('Some details need checking');
    const lastName = screen.getByRole('textbox', { name: 'Last name' });
    expect(lastName).toHaveAttribute('aria-invalid', 'true');
    expect(lastName).toHaveAccessibleDescription(
      'Enter your last name using letters, spaces, hyphens or apostrophes.',
    );
    // The errors are dropped from the history entry once shown.
    await waitFor(() => {
      expect(router.state.location.state).toBeNull();
    });
  });

  it.each([
    [
      'MOBILE_NUMBER_REQUIRED',
      'We need a cellphone number to SMS your confirmation.',
      'We need a cellphone number to SMS your confirmation.',
    ],
    [
      'DEMO_NUMBER_REQUIRED',
      'Use a demo number',
      'This demo accepts only 082 555 0000 to 082 555 0199, for example 082 555 0101.',
    ],
  ] as const)(
    'sends %s back to the cellphone field on the details step',
    async (code, title, fieldError) => {
      const user = userEvent.setup();
      server.use(
        http.post(
          BOOKINGS,
          () =>
            problemResponse(code, {
              errors: [{ field: 'customer.mobile', code, message: 'a hint for developers' }],
            }),
          { once: true },
        ),
      );
      const { router } = renderReview();

      await user.click(await confirmButton());

      expect(
        await screen.findByRole('heading', { level: 1, name: 'Your details' }),
      ).toBeInTheDocument();
      expect(router.state.location.search).toContain('step=details');
      expect(screen.getByRole('alert')).toHaveTextContent(title);
      const mobile = screen.getByRole('textbox', { name: 'Cellphone number' });
      expect(mobile).toHaveAttribute('aria-invalid', 'true');
      expect(mobile).toHaveAccessibleDescription(
        `We SMS your confirmation to this number, for example 082 555 0101. ${fieldError}`,
      );
      expect(screen.queryByText('a hint for developers')).not.toBeInTheDocument();
    },
  );

  // A refusal of the number belongs on its field even when the body names no field, so the customer is never left on
  // Review with "Something went wrong".
  it.each([
    ['MOBILE_NUMBER_REQUIRED', 'We need a cellphone number to SMS your confirmation.'],
    [
      'DEMO_NUMBER_REQUIRED',
      'This demo accepts only 082 555 0000 to 082 555 0199, for example 082 555 0101.',
    ],
  ] as const)('sends %s without errors[] back to the cellphone field', async (code, fieldError) => {
    const user = userEvent.setup();
    server.use(http.post(BOOKINGS, () => problemResponse(code), { once: true }));
    const { router } = renderReview();

    await user.click(await confirmButton());

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Your details' }),
    ).toBeInTheDocument();
    expect(router.state.location.search).toContain('step=details');
    const mobile = screen.getByRole('textbox', { name: 'Cellphone number' });
    expect(mobile).toHaveAttribute('aria-invalid', 'true');
    expect(mobile).toHaveAccessibleDescription(
      `We SMS your confirmation to this number, for example 082 555 0101. ${fieldError}`,
    );
  });

  it.each([
    [
      'DUPLICATE_APPOINTMENT',
      'You already have an upcoming appointment for this service.',
      'Find your appointment',
      '/manage',
    ],
    [
      'SLOT_WITHIN_LEAD_TIME',
      'That time is too soon.',
      'Choose another time',
      '/book?branch=SAN&service=FICA&date=2026-10-27',
    ],
  ] as const)('words %s by its code, with where to go next', async (code, title, link, href) => {
    const user = userEvent.setup();
    server.use(http.post(BOOKINGS, () => problemResponse(code)));
    renderReview();

    await user.click(await confirmButton());

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(title);
    expect(within(alert).getByRole('link', { name: link })).toHaveAttribute('href', href);
  });

  it('gives the id to quote when something fails on our side', async () => {
    const user = userEvent.setup();
    server.use(http.post(BOOKINGS, () => problemResponse('INTERNAL_ERROR')));
    renderReview();

    await user.click(await confirmButton());

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong on our side');
    expect(alert).toHaveTextContent('If it keeps happening, quote reference test-internal_error.');
  });

  it('says when BranchBook cannot be reached', async () => {
    const user = userEvent.setup();
    server.use(http.post(BOOKINGS, () => HttpResponse.error()));
    renderReview();

    await user.click(await confirmButton());

    expect(await screen.findByRole('alert')).toHaveTextContent("We couldn't reach BranchBook");
    expect(screen.getByRole('button', { name: 'Confirm appointment' })).not.toHaveAttribute(
      'aria-disabled',
    );
  });
});
