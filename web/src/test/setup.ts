/**
 * Runs before every test file. It adds the DOM matchers such as `toBeInTheDocument`, and unmounts what each test
 * rendered, so no test sees another's DOM.
 *
 * @packageDocumentation
 */
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(() => {
  cleanup();
});
