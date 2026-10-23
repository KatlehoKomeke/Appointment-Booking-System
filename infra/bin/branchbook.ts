#!/usr/bin/env node
/**
 * Entry point of the BranchBook CDK app: declares every stack and where it is deployed.
 *
 * Stacks are split by lifecycle (docs/adr/0006-hosted-demo-on-aws-with-cdk.md):
 * - `BranchbookAccount` (us-east-1): the GitHub-to-AWS connection and the cost budget. Deployed by an administrator
 *   from their own machine, never by the pipeline (see below).
 * - `BranchbookRelease` (af-south-1): records the deployed commit. Deployed by the pipeline on every push to `main`
 *   (docs/adr/0007-continuous-deployment-from-main.md).
 * - `BranchbookDns`, `BranchbookData` and `BranchbookApp` (af-south-1) join the pipeline when the application exists.
 *
 * Configuration comes from `cdk.json` context (non-sensitive values only) and from environment variables for
 * anything personal. Nothing sensitive is ever written to the repository.
 */
import { App, Tags } from 'aws-cdk-lib/core';
import { AccountStack } from '../lib/account-stack';
import { ReleaseStack } from '../lib/release-stack';

const app = new App();

/**
 * Reads a required string or number from cdk.json context, failing with a clear message when it is missing.
 *
 * @param key - the context key, for example `githubRepository`
 */
function context(key: string): string {
  const value: unknown = app.node.tryGetContext(key);
  if (typeof value === 'string' && value.trim() !== '') {
    return value;
  }
  if (typeof value === 'number') {
    return value.toString();
  }
  throw new Error(`Missing or invalid context value "${key}" in infra/cdk.json`);
}

// The account comes from the credentials in use, so no account ID is ever written to the repository.
const account = process.env.CDK_DEFAULT_ACCOUNT;

// BranchbookAccount defines the role the pipeline itself assumes, so the pipeline must never be able to change it.
// It is therefore only part of the app when BRANCHBOOK_ALERT_EMAIL is set: on an administrator's machine, or with a
// placeholder in the credential-free infra workflow. The deploy workflow never sets it, so CI cannot even
// synthesise this stack, let alone deploy it. The address is personal data and is never committed.
const alertEmail = process.env.BRANCHBOOK_ALERT_EMAIL;
if (alertEmail !== undefined) {
  new AccountStack(app, 'BranchbookAccount', {
    env: { account, region: 'us-east-1' },
    description: 'BranchBook: GitHub OIDC deploy role and monthly cost budget (no customer data).',
    githubRepository: context('githubRepository'),
    githubOwnerId: context('githubOwnerId'),
    githubRepositoryId: context('githubRepositoryId'),
    githubEnvironment: context('githubEnvironment'),
    alertEmail,
    monthlyBudgetUsd: Number(context('monthlyBudgetUsd')),
    releaseRegion: 'af-south-1',
  });
}

new ReleaseStack(app, 'BranchbookRelease', {
  env: { account, region: 'af-south-1' },
  description: 'BranchBook: records the git commit most recently deployed by the pipeline.',
  // GitHub Actions sets GITHUB_SHA to the commit being built; a manual deploy is labelled "local".
  version: process.env.GITHUB_SHA ?? 'local',
});

Tags.of(app).add('project', 'branchbook');
Tags.of(app).add('managed-by', 'aws-cdk');
