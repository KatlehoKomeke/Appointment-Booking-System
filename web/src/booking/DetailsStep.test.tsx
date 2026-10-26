import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { DEMO_MOBILE } from '../test/fixtures';
import { renderWithProviders } from '../test/render';
import { loadDetails, saveDetails } from './bookingSession';
import { DetailsStep } from './DetailsStep';

const DETAILS = '/book?branch=SAN&service=FICA&date=2026-10-27&slot=09%3A30&step=details';

function renderDetails() {
  return renderWithProviders(<DetailsStep />, { path: DETAILS });
}

function field(name: string) {
  return screen.getByRole('textbox', { name });
}

describe('DetailsStep', () => {
  it('checks each field when it is left, in the words of the design', async () => {
    const user = userEvent.setup();
    renderDetails();

    expect(screen.getByRole('heading', { level: 1, name: 'Your details' })).toBeInTheDocument();
    expect(document.title).toBe('Step 4 of 5: Your details - BranchBook');
    expect(screen.getByRole('link', { name: 'Privacy notice' })).toHaveAttribute(
      'href',
      '/privacy',
    );
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();

    // Nothing is said before a field is left.
    await user.click(field('First name'));
    expect(screen.queryByText('Enter your first name.')).not.toBeInTheDocument();
    await user.tab();
    expect(field('First name')).toHaveAttribute('aria-invalid', 'true');
    expect(field('First name')).toHaveAccessibleDescription('Enter your first name.');

    // Once left, the field is checked again as it changes.
    await user.type(field('First name'), 'N0mvula');
    expect(field('First name')).toHaveAccessibleDescription(
      'Enter your first name using letters, spaces, hyphens or apostrophes.',
    );
    await user.clear(field('First name'));
    await user.type(field('First name'), "Nomvula-Thandi O'Neil");
    expect(field('First name')).not.toHaveAttribute('aria-invalid');

    await user.type(field('Cellphone number'), '082 555 010');
    await user.tab();
    expect(field('Cellphone number')).toHaveAccessibleDescription(
      'We SMS your confirmation to this number, for example 082 555 0101. We need a cellphone number to SMS your confirmation.',
    );
  });

  it('refuses a landline, and stays on the step until the number is a cellphone', async () => {
    const user = userEvent.setup();
    const { router } = renderDetails();

    await user.type(field('First name'), 'Nomvula');
    await user.type(field('Last name'), 'Dlamini');
    await user.type(field('Cellphone number'), '021 555 0101');
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(
      screen.getByText('We need a cellphone number to SMS your confirmation.'),
    ).toBeInTheDocument();
    expect(field('Cellphone number')).toHaveFocus();
    expect(router.state.location.search).toContain('step=details');
    expect(loadDetails()).toBeUndefined();

    await user.clear(field('Cellphone number'));
    await user.type(field('Cellphone number'), '+27 82 555 0101');
    expect(
      screen.queryByText('We need a cellphone number to SMS your confirmation.'),
    ).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(router.state.location.search).toContain('step=review');
  });

  it('keeps the details in the tab, so they survive leaving the step and a refresh', async () => {
    const user = userEvent.setup();
    const first = renderDetails();

    await user.type(field('First name'), 'Nomvula');
    await user.type(field('Last name'), 'Dlamini');
    await user.type(field('Cellphone number'), DEMO_MOBILE);
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(first.router.state.location.search).toContain('step=review');
    // Never in the URL.
    expect(first.router.state.location.search).not.toMatch(/Nomvula|555/);
    first.unmount();

    renderDetails();
    expect(field('First name')).toHaveValue('Nomvula');
    expect(field('Last name')).toHaveValue('Dlamini');
    expect(field('Cellphone number')).toHaveValue(DEMO_MOBILE);
  });

  it('opens with the details saved earlier', () => {
    saveDetails({ firstName: 'Sipho', lastName: 'Ndlovu', mobile: '072 555 0199' });
    renderDetails();
    expect(field('First name')).toHaveValue('Sipho');
    expect(field('Cellphone number')).toHaveValue('072 555 0199');
  });
});
