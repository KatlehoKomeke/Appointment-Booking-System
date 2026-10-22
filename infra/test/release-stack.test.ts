/**
 * Unit tests for {@link ReleaseStack}: the deploy pipeline relies on this parameter to prove that the commit it just
 * deployed is the one AWS now records.
 */
import { App } from 'aws-cdk-lib/core';
import { Template } from 'aws-cdk-lib/assertions';
import { RELEASE_PARAMETER_NAME, ReleaseStack } from '../lib/release-stack';

function synth(version: string): Template {
  const app = new App();
  return Template.fromStack(
    new ReleaseStack(app, 'Release', { env: { account: '111111111111', region: 'af-south-1' }, version }),
  );
}

describe('ReleaseStack', () => {
  test('records the deployed commit in a standard SSM parameter', () => {
    const sha = 'daf7d9c0123456789abcdef0123456789abcdef0';
    synth(sha).hasResourceProperties('AWS::SSM::Parameter', {
      Name: RELEASE_PARAMETER_NAME,
      Type: 'String',
      Value: sha,
      Tier: 'Standard',
    });
  });

  test('labels a deploy from an administrator machine as "local"', () => {
    synth('local').hasResourceProperties('AWS::SSM::Parameter', { Value: 'local' });
  });

  test.each([['an empty version', ''], ['a branch name', 'main'], ['an upper-case SHA', 'DAF7D9C']])(
    'refuses to synthesise with %s',
    (_label, version) => {
      expect(() => synth(version)).toThrow(/git commit SHA/);
    },
  );
});
