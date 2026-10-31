/**
 * Runs cdk-nag's AWS Solutions rule pack over every stack of both environments, the way `cdk synth` would build them.
 * Any finding that is not acknowledged in the stack's code, with its reason, fails synthesis and so this test.
 */
import { Validations } from 'aws-cdk-lib/core';
import { AwsSolutionsChecks } from 'cdk-nag';
import { buildApp } from '../lib/app';
import { ENVIRONMENT_NAMES } from '../lib/config';
import { newApp } from './cdk-app';

const DIGEST = `sha256:${'0'.repeat(64)}`;

describe.each(ENVIRONMENT_NAMES)('cdk-nag AwsSolutions on %s', (name) => {
  test('every stack synthesises with no unacknowledged finding', () => {
    const app = newApp();
    // The registry and the environment's three stacks, built as bin/branchbook.ts builds them.
    buildApp(app, {
      BRANCHBOOK_ENV: name,
      BRANCHBOOK_IMAGE_DIGEST: DIGEST,
      BRANCHBOOK_DEMO_ALERT_EMAIL: 'alerts@example.com',
      CDK_DEFAULT_ACCOUNT: '111111111111',
    });
    Validations.of(app).addPlugins(new AwsSolutionsChecks(app));

    // Synthesis throws, with the report of every violation, when any rule finds one that is not acknowledged.
    expect(() => app.synth()).not.toThrow();
  });
});
