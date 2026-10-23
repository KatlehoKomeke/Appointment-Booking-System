import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { App } from './App';

describe('App', () => {
  it('says the bank is fictional before any other content', () => {
    render(<App />);

    const notice = screen.getByRole('complementary', { name: 'About this site' });
    const heading = screen.getByRole('heading', { level: 1 });

    expect(notice).toHaveTextContent('Fynbos Bank is fictional.');
    expect(notice.compareDocumentPosition(heading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('names the page with a single top-level heading', () => {
    render(<App />);

    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(
      'Book a branch appointment',
    );
  });
});
