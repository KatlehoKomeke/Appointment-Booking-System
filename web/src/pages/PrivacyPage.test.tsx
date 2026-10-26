import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';

import { PrivacyPage } from './PrivacyPage';

function renderPrivacy() {
  const router = createMemoryRouter([{ path: '/privacy', element: <PrivacyPage /> }], {
    initialEntries: ['/privacy'],
  });
  return render(<RouterProvider router={router} />);
}

describe('PrivacyPage', () => {
  it('gives the POPIA section 18 notice, one named section per fact', () => {
    renderPrivacy();

    expect(screen.getByRole('heading', { level: 1, name: 'Privacy notice' })).toBeInTheDocument();
    expect(document.title).toBe('Privacy notice - BranchBook');
    expect(
      screen.getByText(/as section 18 of the Protection of Personal Information Act/),
    ).toBeInTheDocument();

    expect(screen.getByRole('region', { name: 'Who is responsible' })).toHaveTextContent(
      'Fynbos Bank (fictional demo)',
    );
    expect(screen.getByRole('region', { name: 'What we collect' })).toHaveTextContent(
      'Your first name, last name and cellphone number.',
    );
    expect(screen.getByRole('region', { name: 'Why' })).toHaveTextContent(
      'To schedule, confirm and manage this appointment, and for nothing else.',
    );
    expect(screen.getByRole('region', { name: 'How long we keep it' })).toHaveTextContent(
      'The hosted demo deletes every booking, with your name and number, after 48 hours.',
    );
    expect(screen.getByRole('region', { name: 'Your rights' })).toHaveTextContent(
      'you may complain to the Information Regulator',
    );
  });
});
