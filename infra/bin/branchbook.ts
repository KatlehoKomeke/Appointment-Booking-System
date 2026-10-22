#!/usr/bin/env node
/**
 * Entry point of the BranchBook CDK app: declares every stack and where it is deployed.
 *
 * Stacks are split by lifecycle (docs/adr/0006-hosted-demo-on-aws-with-cdk.md):
 * - `BranchbookAccount` (us-east-1): the GitHub-to-AWS connection and the cost budget. Deployed once, by hand.
 * - `BranchbookDns`, `BranchbookData` and `BranchbookApp` (af-south-1) arrive when the application exists to host.
 *
 * Configuration comes from `cdk.json` context (non-sensitive values only) and from environment variables for
 * anything personal. Nothing sensitive is ever written to the repository.
 */
import { App, Tags } from 'aws-cdk-lib/core';
import { AccountStack } from '../lib/account-stack';

const app = new App();

/** Reads a required context value from cdk.json, failing with a clear message when it is missing. */
function context(key: string): string {
  const value: unknown = app.node.tryGetContext(key);
  if (value === undefined || value === null || String(value).trim() === '') {
    throw new Error(`Missing context value "${key}" in infra/cdk.json`);
  }
  return String(value);
}

// The alert address is personal data, so it is read from the environment at synth time and never committed.
// CI synthesises with a placeholder; only the real deploy, from your own machine, uses your address.
const alertEmail = process.env.BRANCHBOOK_ALERT_EMAIL ?? '';

new AccountStack(app, 'BranchbookAccount', {
  // The account comes from the credentials in use, so no account ID is ever written to the repository.
  env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: 'us-east-1' },
  description: 'BranchBook: GitHub OIDC deploy role and monthly cost budget (no customer data).',
  githubRepository: context('githubRepository'),
  githubOwnerId: context('githubOwnerId'),
  githubRepositoryId: context('githubRepositoryId'),
  githubEnvironment: context('githubEnvironment'),
  alertEmail,
  monthlyBudgetUsd: Number(context('monthlyBudgetUsd')),
});

Tags.of(app).add('project', 'branchbook');
Tags.of(app).add('managed-by', 'aws-cdk');
