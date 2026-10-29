/**
 * Runs cdk-nag's AWS Solutions rule pack over every stack of both environments, the way `cdk synth` would build them.
 * Any finding that is not acknowledged in the stack's code, with its reason, fails synthesis and so this test.
 */
import { Validations } from 'aws-cdk-lib/core';
import { AwsSolutionsChecks } from 'cdk-nag';
import { ENVIRONMENT_NAMES, loadEnvironmentConfig, loadRegistryConfig } from '../lib/config';
import { addEnvironment } from '../lib/environment';
import { RegistryStack } from '../lib/registry-stack';
import { newApp } from './cdk-app';

const DIGEST = `sha256:${'0'.repeat(64)}`;

describe.each(ENVIRONMENT_NAMES)('cdk-nag AwsSolutions on %s', (name) => {
  test('every stack synthesises with no unacknowledged finding', () => {
    const app = newApp();
    const registry = loadRegistryConfig();
    new RegistryStack(app, 'BranchbookRegistry', { env: { account: '111111111111', region: registry.region }, config: registry });
    addEnvironment(app, loadEnvironmentConfig(name), registry, {
      account: '111111111111',
      imageDigest: DIGEST,
      version: 'local',
      alertEmail: 'alerts@example.com',
    });
    Validations.of(app).addPlugins(new AwsSolutionsChecks(app));

    // Synthesis throws, with the report of every violation, when any rule finds one that is not acknowledged.
    expect(() => app.synth()).not.toThrow();
  });
});
