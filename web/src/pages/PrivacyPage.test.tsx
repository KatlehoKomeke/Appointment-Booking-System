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

    // The bank is fictional, so the notice names no contact and points to what the customer can do.
    expect(screen.getByRole('region', { name: 'Who is responsible' })).toHaveTextContent(
      'Fynbos Bank (fictional demo). Because it is fictional, it has no information officer to write to',
    );
    expect(screen.getByRole('region', { name: 'What we collect' })).toHaveTextContent(
      'Your first name, last name and cellphone number.',
    );
    expect(screen.getByRole('region', { name: 'What we collect' })).toHaveTextContent(
      "also log each request's IP address and browser in their firewall's log",
    );
    expect(screen.getByRole('region', { name: 'Why' })).toHaveTextContent(
      'To schedule, confirm and manage this appointment, and for nothing else:',
    );
    expect(screen.getByRole('region', { name: 'How long we keep it' })).toHaveTextContent(
      'branchbook.click, and its test copy at stg.branchbook.click, keep a booking, with your name and number, until its appointment is over, and delete it within the hour after it ends',
    );
    // The backups outlive the deletion, so the notice says how long they last at most, and that a
    // deleted database's final copy lasts until it is deleted too.
    expect(screen.getByRole('region', { name: 'How long we keep it' })).toHaveTextContent(
      'backups, which could still hold it, expire after at most 14 days',
    );
    expect(screen.getByRole('region', { name: 'How long we keep it' })).toHaveTextContent(
      "if either site's database is ever deleted, a final copy of it is kept until we delete that too",
    );
    expect(screen.getByRole('region', { name: 'How long we keep it' })).toHaveTextContent(
      'A booking you cancel is deleted at once; only the cancellation SMS is kept, until it has been sent or has failed for good.',
    );
    // What outlives a booking holds neither the name nor the number, only fingerprints that last about a day.
    expect(screen.getByRole('region', { name: 'How long we keep it' })).toHaveTextContent(
      "a one-way fingerprint of the booking's details is kept, never the details themselves",
    );
    expect(screen.getByRole('region', { name: 'Who sends the SMS' })).toHaveTextContent(
      'a keyed fingerprint of the number, never the number itself, for about a day after each message',
    );
    expect(screen.getByRole('region', { name: 'What we collect' })).toHaveTextContent(
      'Use only your own cellphone number: branchbook.click texts it.',
    );
    expect(screen.getByRole('region', { name: 'What we collect' })).toHaveTextContent(
      'the test copy at stg.branchbook.click accepts only those',
    );
    expect(screen.getByRole('region', { name: 'Who sends the SMS' })).toHaveTextContent(
      'On branchbook.click, AWS End User Messaging, in Cape Town, sends each SMS for us as our operator',
    );
    expect(screen.getByRole('region', { name: 'Your rights' })).toHaveTextContent(
      'Until it starts, you may cancel it, which deletes it at once',
    );
    expect(screen.getByRole('region', { name: 'Your rights' })).toHaveTextContent(
      'you may complain to the Information Regulator',
    );
  });
});
