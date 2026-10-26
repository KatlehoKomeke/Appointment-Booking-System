/**
 * Runs before every test file. It adds the DOM matchers such as `toBeInTheDocument`, starts the fake API
 * (`server.ts`), and after each test unmounts what it rendered, restores the default handlers, forgets the fake API's
 * bookings and clears session storage, so no test sees another's DOM, handlers or saved booking.
 *
 * @packageDocumentation
 */
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';

import { resetFakeApi } from './handlers';
import { server } from './server';

// jsdom does not implement scrolling, and the router's ScrollRestoration scrolls on every navigation.
window.scrollTo = () => undefined;

beforeAll(() => {
  // A request no handler expects is a bug in the test or the app, so it fails the test rather than reaching out.
  server.listen({ onUnhandledFrame: 'error' });
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  resetFakeApi();
  sessionStorage.clear();
});

afterAll(() => {
  server.close();
});
