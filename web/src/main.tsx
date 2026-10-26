/**
 * Entry point: loads the font and the global styles, then mounts the app into the `#root` element of index.html.
 *
 * @packageDocumentation
 */
import '@fontsource/nunito-sans/latin-400.css';
import '@fontsource/nunito-sans/latin-600.css';
import '@fontsource/nunito-sans/latin-700.css';
import './ui/tokens.css';
import './ui/global.css';

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';

const container = document.getElementById('root');
if (container === null) {
  throw new Error('index.html must contain an element with id "root"');
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
