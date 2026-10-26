import { describe, expect, it } from 'vitest';

import { ApiProblem } from '../api/client';
import { problem } from '../test/fixtures';
import { createQueryClient, shouldRetryQuery } from './queryClient';

function refused(status: number) {
  return new ApiProblem(new Response(null, { status }), undefined);
}

describe('the query retry rule', () => {
  it('never retries a 4xx, which is an answer', () => {
    expect(shouldRetryQuery(0, refused(404))).toBe(false);
    expect(shouldRetryQuery(0, refused(429))).toBe(false);
    expect(
      shouldRetryQuery(
        0,
        new ApiProblem(
          new Response(null, { status: 422 }),
          problem('SERVICE_NOT_OFFERED_AT_BRANCH'),
        ),
      ),
    ).toBe(false);
  });

  it('retries a network failure or a 5xx once', () => {
    expect(shouldRetryQuery(0, new TypeError('Failed to fetch'))).toBe(true);
    expect(shouldRetryQuery(0, refused(503))).toBe(true);
    expect(shouldRetryQuery(1, refused(503))).toBe(false);
    expect(shouldRetryQuery(1, new TypeError('Failed to fetch'))).toBe(false);
  });

  it('is what the app cache uses, with no refetch on focus and no mutation retries', () => {
    const defaults = createQueryClient().getDefaultOptions();
    expect(defaults.queries?.retry).toBe(shouldRetryQuery);
    expect(defaults.queries?.refetchOnWindowFocus).toBe(false);
    expect(defaults.mutations?.retry).toBe(false);
  });
});
