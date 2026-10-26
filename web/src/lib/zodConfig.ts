/**
 * Zod's settings for the app, imported once by main.tsx before any form is shown.
 *
 * Zod 4 compiles fast object parsers with `new Function`, after probing whether the page allows it. The app's
 * Content Security Policy forbids eval, so the probe fails, safely, but every probe is reported as a CSP violation.
 * Jitless parsing never probes; the forms' few fields do not need the speed.
 *
 * @packageDocumentation
 */
import { z } from 'zod';

z.config({ jitless: true });
