/**
 * Unit tests for {@link RegistryStack}: the repository each commit's image is pushed to once and promoted from.
 */
import { App } from 'aws-cdk-lib/core';
import { Template } from 'aws-cdk-lib/assertions';
import { loadRegistryConfig } from '../lib/config';
import { RegistryStack } from '../lib/registry-stack';

const registry = loadRegistryConfig();
const template = Template.fromStack(
  new RegistryStack(new App(), 'Registry', { env: { account: '111111111111', region: registry.region }, config: registry }),
);

describe('RegistryStack', () => {
  test('creates the branchbook repository with immutable tags and a scan of every push', () => {
    template.hasResourceProperties('AWS::ECR::Repository', {
      RepositoryName: 'branchbook',
      ImageTagMutability: 'IMMUTABLE',
      ImageScanningConfiguration: { ScanOnPush: true },
    });
  });

  test('keeps the newest 50 images and expires the rest', () => {
    const repositories = Object.values(template.findResources('AWS::ECR::Repository')) as {
      Properties: { LifecyclePolicy: { LifecyclePolicyText: string } };
    }[];
    const policy = JSON.parse(repositories[0].Properties.LifecyclePolicy.LifecyclePolicyText) as {
      rules: { selection: { tagStatus: string; countType: string; countNumber: number }; action: { type: string } }[];
    };
    expect(policy.rules).toHaveLength(1);
    expect(policy.rules[0].selection).toEqual({ tagStatus: 'any', countType: 'imageCountMoreThan', countNumber: 50 });
    expect(policy.rules[0].action.type).toBe('expire');
  });

  test('keeps the repository, and the images environments run, if the stack is deleted', () => {
    template.hasResource('AWS::ECR::Repository', { DeletionPolicy: 'Retain', UpdateReplacePolicy: 'Retain' });
  });
});
