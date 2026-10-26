import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';

import { lookupDigits } from '../booking/bookingSession';
import { DEMO_REFERENCE } from '../test/fixtures';
import { problemResponse } from '../test/handlers';
import { renderRoute } from '../test/render';
import { server } from '../test/server';

async function findAppointment(reference: string, digits: string) {
  const user = userEvent.setup();
  const rendered = renderRoute('/manage');
  await user.type(await screen.findByRole('textbox', { name: 'Reference' }), reference);
  await user.type(
    screen.getByRole('textbox', { name: 'Last four digits of your cellphone number' }),
    digits,
  );
  await user.click(screen.getByRole('button', { name: 'Find appointment' }));
  return rendered;
}

const NOT_FOUND =
  "We couldn't find an appointment with those details. Check the reference and the last four digits, then try again.";

describe('ManagePage', () => {
  it.each([
    ['a wrong reference', 'APT-2222-3333', '0101'],
    ['wrong digits', DEMO_REFERENCE, '0110'],
  ])('gives the same answer for %s', async (_case, reference, digits) => {
    await findAppointment(reference, digits);

    expect(await screen.findByRole('alert')).toHaveTextContent(NOT_FOUND);
  });

  it('says to try later when the reference is paused', async () => {
    server.use(
      http.get('*/api/v1/appointments/:reference', () =>
        problemResponse('LOOKUP_RATE_LIMITED', {}, { 'Retry-After': '3600' }),
      ),
    );
    await findAppointment(DEMO_REFERENCE, '0101');

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many attempts. Try again later.',
    );
  });

  it('opens the appointment, whatever the case and hyphens of the reference', async () => {
    const { router } = await findAppointment('apt7k3m9q2x', '0101');

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Your appointment is confirmed' }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(`/appointments/${DEMO_REFERENCE}`);
    expect(lookupDigits(DEMO_REFERENCE)).toBe('0101');
  });

  it('tries once more, quietly, while other lookups of the reference are still being checked', async () => {
    server.use(
      http.get(
        '*/api/v1/appointments/:reference',
        () => problemResponse('LOOKUP_RATE_LIMITED', {}, { 'Retry-After': '1' }),
        { once: true },
      ),
    );
    const { router } = await findAppointment(DEMO_REFERENCE, '0101');

    expect(
      await screen.findByRole(
        'heading',
        { level: 1, name: 'Your appointment is confirmed' },
        { timeout: 3000 },
      ),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(`/appointments/${DEMO_REFERENCE}`);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('checks both fields before asking the API', async () => {
    const user = userEvent.setup();
    renderRoute('/manage');

    await user.click(await screen.findByRole('button', { name: 'Find appointment' }));

    expect(screen.getByRole('textbox', { name: 'Reference' })).toHaveAccessibleDescription(
      'It starts with APT. Hyphens are optional. Enter the reference from your SMS.',
    );
    expect(
      screen.getByRole('textbox', { name: 'Last four digits of your cellphone number' }),
    ).toHaveAccessibleDescription('Enter the last four digits of your cellphone number.');
    expect(screen.getByText(/find your SMS\? The demo numbers/)).toBeInTheDocument();
  });
});
