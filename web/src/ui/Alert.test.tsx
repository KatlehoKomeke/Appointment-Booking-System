import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Alert } from './Alert';
import { Badge } from './Badge';
import { Button } from './Button';

describe('Alert', () => {
  it('announces an error as an alert, with its title, text and actions', () => {
    render(
      <Alert
        tone="error"
        title="That time was just taken"
        actions={<Button variant="secondary">09:45</Button>}
      >
        Choose another time on Tuesday 27 October:
      </Alert>,
    );

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('That time was just taken');
    expect(alert).toHaveTextContent('Choose another time on Tuesday 27 October:');
    expect(screen.getByRole('button', { name: '09:45' })).toBeInTheDocument();
  });

  it('leaves announcing a status to the region around it, and never announces info', () => {
    render(
      <>
        <div role="status">
          <Alert tone="status" actions={<Button variant="secondary">Refresh</Button>}>
            Times may have changed. Refresh to see the latest.
          </Alert>
        </div>
        <Alert tone="info" title="Simulated: no real messages are sent.">
          This demo operator view is switched off in production.
        </Alert>
      </>,
    );

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Times may have changed.');
    expect(screen.getByText('Simulated: no real messages are sent.').tagName).toBe('STRONG');
  });
});

describe('Badge', () => {
  it('says what it means in words', () => {
    render(<Badge tone="failed">Not delivered</Badge>);

    expect(screen.getByText('Not delivered')).toBeInTheDocument();
  });
});
