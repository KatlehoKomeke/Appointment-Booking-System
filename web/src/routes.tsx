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

import { BookPage } from './booking/BookPage';
import { ConfirmationPage } from './pages/ConfirmationPage';
import { ErrorPage } from './pages/ErrorPage';
import { InboxPage } from './pages/InboxPage';
import { LandingPage } from './pages/LandingPage';
import { ManagePage } from './pages/ManagePage';
import { NotFoundPage } from './pages/NotFoundPage';
import { PrivacyPage } from './pages/PrivacyPage';
import { Shell } from './ui/Shell';

/**
 * The route table, shared by the browser router in `App.tsx` and the memory router the tests use. A page that fails
 * to render is replaced by the error page inside the shell, so the banner and footer stay.
 */
export const routes: RouteObject[] = [
  {
    element: <Shell />,
    children: [
      {
        errorElement: <ErrorPage />,
        children: [
          { index: true, element: <LandingPage /> },
          { path: 'book', element: <BookPage /> },
          { path: 'appointments/:reference', element: <ConfirmationPage /> },
          { path: 'manage', element: <ManagePage /> },
          { path: 'messages', element: <InboxPage /> },
          { path: 'privacy', element: <PrivacyPage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
];
