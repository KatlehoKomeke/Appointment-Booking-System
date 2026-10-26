import { z } from 'zod';
import { describe, expect, it } from 'vitest';

import './zodConfig';

describe('zodConfig', () => {
  it('turns off the parser compilation that the CSP would report as an eval', () => {
    expect(z.config().jitless).toBe(true);
  });
});
