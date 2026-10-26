/// <reference types="vitest/config" />
/**
 * Vite settings for the dev server, the production build and the Vitest unit tests.
 *
 * In production the Spring Boot jar serves the built files and the API from one origin. The dev server keeps that
 * shape by proxying API and probe calls to the API on port 8080 (`make dev-api`), so the app never needs CORS.
 */
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// API_URL points the dev server at an API on another port, for example the jar that `APP_PORT=18080 make up` runs.
const api = process.env.API_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  build: {
    // The Content Security Policy allows fonts only from the app's own files, so a font must never be inlined as a
    // data: URI, however small. Other small assets keep Vite's default.
    assetsInlineLimit: (file) => (/\.(woff2?|ttf|otf)$/.test(file) ? false : undefined),
  },
  server: {
    proxy: {
      '/api': api,
      '/actuator': api,
      '/readyz': api,
      '/livez': api,
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // Each test starts with its spies and mocks restored, so no test depends on another's leftovers.
    restoreMocks: true,
  },
});
