/**
 * The fake API every test talks to. `setup.ts` starts it before the tests, refuses any request it has no handler
 * for (MSW 3's `onUnhandledFrame: 'error'`, which replaced `onUnhandledRequest`), restores the default handlers after each test, and stops it at the end. Override a handler for one test with
 * `server.use(...)`.
 *
 * @packageDocumentation
 */
import { setupServer } from 'msw/node';

import { handlers } from './handlers';

/** The MSW server, with a handler for every API operation. */
export const server = setupServer(...handlers);
