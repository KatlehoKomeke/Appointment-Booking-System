/**
 * Unit tests for {@link ReleaseStack}: the deploy pipeline relies on these parameters to prove that the commit it just
 * deployed is the one AWS now records, and to know which image to roll back to.
 */
import { App } from 'aws-cdk-lib/core';
import { Template } from 'aws-cdk-lib/assertions';
import { RELEASE_PARAMETER_NAME, ReleaseStack, releaseImageParameterName, releaseParameterName } from '../lib/release-stack';

const DIGEST = `sha256:${'ab'.repeat(32)}`;

function synth(version: string, imageDigest = DIGEST, resourcePrefix = 'branchbook'): Template {
  const app = new App();
  return Template.fromStack(
    new ReleaseStack(app, 'Release', {
      env: { account: '111111111111', region: 'af-south-1' },
      resourcePrefix,
      version,
      imageDigest,
    }),
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

  test('records the deployed image digest beside it, for the pipeline\'s rollback', () => {
    synth('local').hasResourceProperties('AWS::SSM::Parameter', {
      Name: '/branchbook/release-image',
      Value: DIGEST,
      Tier: 'Standard',
    });
  });

  test('keeps production\'s parameter names and prefixes staging\'s', () => {
    expect(RELEASE_PARAMETER_NAME).toBe('/branchbook/release');
    expect(releaseParameterName('branchbook-staging')).toBe('/branchbook-staging/release');
    expect(releaseImageParameterName('branchbook-staging')).toBe('/branchbook-staging/release-image');
    synth('local', DIGEST, 'branchbook-staging').hasResourceProperties('AWS::SSM::Parameter', {
      Name: '/branchbook-staging/release',
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

  test('refuses an image named by tag instead of digest', () => {
    expect(() => synth('local', 'latest')).toThrow(/sha256/);
  });
});
