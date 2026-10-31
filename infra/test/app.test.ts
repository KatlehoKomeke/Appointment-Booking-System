/**
 * Unit tests for {@link buildApp}: which stacks the CDK app holds for each combination of deploy-time variables, so
 * the pipeline can never synthesise a stack its switches leave out.
 */
import { Stack } from 'aws-cdk-lib/core';
import { AppVariables, buildApp } from '../lib/app';
import { newApp } from './cdk-app';

const DIGEST = `sha256:${'0'.repeat(64)}`;

/**
 * The IDs of the stacks the app holds for these variables, sorted.
 *
 * @param variables - the deploy-time variables
 */
function stackIds(variables: AppVariables): string[] {
  const app = newApp();
  buildApp(app, variables);
  return app.node.children.filter((child) => Stack.isStack(child)).map((stack) => stack.node.id).sort();
}

describe('buildApp', () => {
  test('holds only the registry when no environment and no administrator switch is set', () => {
    expect(stackIds({})).toEqual(['BranchbookRegistry']);
  });

  test('adds the account and guardrails stacks only when an administrator sets their switches', () => {
    expect(stackIds({ BRANCHBOOK_ALERT_EMAIL: 'alerts@example.com' })).toEqual(['BranchbookAccount', 'BranchbookRegistry']);
    expect(stackIds({ BRANCHBOOK_GUARDRAILS: 'true' })).toEqual(['BranchbookGuardrails', 'BranchbookRegistry']);
    // An empty secret, as a workflow passes one nobody has set, is not an address.
    expect(stackIds({ BRANCHBOOK_ALERT_EMAIL: ' ', BRANCHBOOK_GUARDRAILS: 'false' })).toEqual(['BranchbookRegistry']);
  });

  test.each([
    ['staging', ['BranchbookRegistry', 'BranchbookStagingApp', 'BranchbookStagingData', 'BranchbookStagingRelease']],
    ['production', ['BranchbookApp', 'BranchbookData', 'BranchbookRegistry', 'BranchbookRelease']],
  ])('holds only the %s stacks, beside the registry, when BRANCHBOOK_ENV selects it', (name, expected) => {
    expect(stackIds({ BRANCHBOOK_ENV: name, BRANCHBOOK_IMAGE_DIGEST: DIGEST })).toEqual(expected);
  });

  test('refuses an environment without an image digest, and the retired BRANCHBOOK_HOSTED_DEMO switch', () => {
    expect(() => stackIds({ BRANCHBOOK_ENV: 'staging' })).toThrow(/BRANCHBOOK_IMAGE_DIGEST/);
    expect(() => stackIds({ BRANCHBOOK_HOSTED_DEMO: 'true' })).toThrow(/replaced by BRANCHBOOK_ENV/);
  });
});
