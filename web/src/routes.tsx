/**
 * The app's routes. Every page renders inside the shell: the fictional-bank banner, the header, the page and the
 * footer.
 *
 * The server forwards each of these paths to index.html (`SpaForwardingController`), so a refresh or a shared link
 * opens the same page. A path it does not forward never reaches the app.
 *
 * @packageDocumentation
 */
import type { RouteObject } from 'react-router';

import { ErrorPage } from './pages/ErrorPage';
import { LandingPage } from './pages/LandingPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { PageLoading } from './ui/PageLoading';
import { Shell } from './ui/Shell';

/**
 * The route table, shared by the browser router in `App.tsx` and the memory router the tests use. A page that fails
 * to render is replaced by the error page inside the shell, so the banner and footer stay.
 *
 * The landing page, the shell, the error page and the not-found page are in the first download, so the home page
 * and anything that goes wrong show at once. Every other page, with the libraries only it uses (the forms and their
 * validation), is fetched the first time it is opened, which keeps the first download small. Moving to such a page
 * keeps the current one on screen until the new one is ready. A page opened straight from a link or a refresh shows
 * the shell with {@link PageLoading} in the meantime. If its code cannot be fetched, say after a new release has
 * replaced it, the error page offers a fresh start, which reloads the app.
 */
export const routes: RouteObject[] = [
  {
    element: <Shell />,
    children: [
      {
        errorElement: <ErrorPage />,
        // On this route rather than the shell's, so the shell stays on screen around it.
        hydrateFallbackElement: <PageLoading />,
        children: [
          { index: true, element: <LandingPage /> },
          {
            path: 'book',
            lazy: async () => ({ Component: (await import('./booking/BookPage')).BookPage }),
          },
          {
            path: 'appointments/:reference',
            lazy: async () => ({
              Component: (await import('./pages/ConfirmationPage')).ConfirmationPage,
            }),
          },
          {
            path: 'manage',
            lazy: async () => ({ Component: (await import('./pages/ManagePage')).ManagePage }),
          },
          {
            path: 'messages',
            lazy: async () => ({ Component: (await import('./pages/InboxPage')).InboxPage }),
          },
          {
            path: 'privacy',
            lazy: async () => ({ Component: (await import('./pages/PrivacyPage')).PrivacyPage }),
          },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
];
