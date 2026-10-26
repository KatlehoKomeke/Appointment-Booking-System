/**
 * The whole app: the API cache and the router, around the routes in `routes.tsx`.
 *
 * @packageDocumentation
 */
import { QueryClientProvider } from '@tanstack/react-query';
import { createBrowserRouter, RouterProvider } from 'react-router';

import { createQueryClient } from './app/queryClient';
import { routes } from './routes';

const router = createBrowserRouter(routes);
const queryClient = createQueryClient();

/** The app, as the browser runs it. Tests render {@link routes} in a memory router instead (`src/test/render.tsx`). */
export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  );
}
