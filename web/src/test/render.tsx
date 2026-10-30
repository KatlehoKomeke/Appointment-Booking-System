/**
 * Renders the app, or one component, the way the browser does, for tests: inside a router and a fresh API cache.
 *
 * Each render gets its own `QueryClient`, so no answer is cached from one test to the next, and it never retries,
 * so a refused request fails the test at once instead of after a delay.
 *
 * @packageDocumentation
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactNode } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';

import { DEFAULT_STALE_TIME_MS } from '../app/queryClient';
import { CaptchaProvider } from '../captcha/CaptchaProvider';
import { routes } from '../routes';

/**
 * A cache for one test: the app's settings, without retries.
 */
export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false, staleTime: DEFAULT_STALE_TIME_MS },
      mutations: { retry: false },
    },
  });
}

/**
 * Renders the app's routes at a path, in a memory router, with the CAPTCHA controller around them, as `App.tsx`
 * renders them.
 *
 * @example
 * ```tsx
 * const { router } = renderRoute('/book?branch=SAN');
 * await user.click(await screen.findByRole('button', { name: /choose update personal details/i }));
 * expect(router.state.location.search).toContain('service=FICA');
 * ```
 * @param path - the path and query to open, such as `/book?branch=SAN`
 * @returns Testing Library's result, the router (its `state.location` is the current URL) and the cache
 */
export function renderRoute(path: string) {
  const queryClient = createTestQueryClient();
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const result = render(
    <QueryClientProvider client={queryClient}>
      <CaptchaProvider>
        <RouterProvider router={router} />
      </CaptchaProvider>
    </QueryClientProvider>,
  );
  return { ...result, router, queryClient };
}

/** Options for {@link renderWithProviders}. */
export interface RenderWithProvidersOptions {
  /** The URL the component sees, such as `/book?branch=SAN&service=FICA`. Defaults to `/`. */
  path?: string;
}

/**
 * Renders one component with what the app gives every component: a router (on any path) and a fresh cache.
 *
 * @param ui - the component to render
 * @param options - the URL it sees
 * @returns Testing Library's result, the router and the cache
 */
export function renderWithProviders(ui: ReactNode, options: RenderWithProvidersOptions = {}) {
  const path = options.path ?? '/';
  const queryClient = createTestQueryClient();
  const router = createMemoryRouter([{ path: '*', element: ui }], { initialEntries: [path] });
  const result = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { ...result, router, queryClient };
}
