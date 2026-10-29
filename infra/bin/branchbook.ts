#!/usr/bin/env node
/**
 * Entry point of the BranchBook CDK app: declares every stack and where it is deployed.
 *
 * Stacks are split by lifecycle (docs/adr/0006-hosted-demo-on-aws-with-cdk.md):
 * - `BranchbookAccount` (us-east-1): the GitHub-to-AWS connection and the cost budget. Deployed by an administrator
 *   from their own machine, never by the pipeline (see below).
 * - `BranchbookRelease` (af-south-1): records the deployed commit. Deployed by the pipeline on every push to `main`
 *   (docs/adr/0007-continuous-deployment-from-main.md).
 * - `BranchbookData` and `BranchbookApp` (af-south-1): the hosted demo, deployed by an administrator (see below). The
 *   domain's hosted zone comes from its Route 53 registration, so there is no separate DNS stack.
 *
 * Configuration comes from `cdk.json` context (non-sensitive values only) and from environment variables for
 * anything personal. Nothing sensitive is ever written to the repository.
 */
import { App, Tags } from 'aws-cdk-lib/core';
import { AccountStack, loadAccountConfig } from '../lib/account-stack';
import { AppStack } from '../lib/app-stack';
import { DataStack } from '../lib/data-stack';
import { GuardrailsStack } from '../lib/guardrails-stack';
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

/**
 * Reads an optional environment variable, treating an empty value as unset: a workflow passes a secret that nobody
 * has set as an empty string, which must not become an e-mail address.
 *
 * @param name - the variable's name, for example `BRANCHBOOK_DEMO_ALERT_EMAIL`
 */
function optionalEnv(name: string): string | undefined {
  const value = process.env[name];
  return value === undefined || value.trim() === '' ? undefined : value;
}

// The account comes from the credentials in use, so no account ID is ever written to the repository.
const account = process.env.CDK_DEFAULT_ACCOUNT;

// BranchbookAccount defines the role the pipeline itself assumes, so the pipeline must never be able to change it.
// It is therefore only part of the app when BRANCHBOOK_ALERT_EMAIL is set: on an administrator's machine, or with a
// placeholder in the credential-free infra job. The deploy job never sets it, so the pipeline cannot even
// synthesise this stack, let alone deploy it. The address is personal data and is never committed.
const accountConfig = loadAccountConfig();
const alertEmail = optionalEnv('BRANCHBOOK_ALERT_EMAIL');
if (alertEmail !== undefined) {
  new AccountStack(app, 'BranchbookAccount', {
    env: { account, region: accountConfig.accountRegion },
    description: 'BranchBook: GitHub OIDC deploy roles and monthly cost budget (no customer data).',
    config: accountConfig,
    alertEmail,
  });
}

// BranchbookGuardrails holds the audit trail, the detective controls and the policies that limit every deployment,
// so, like BranchbookAccount, only an administrator deploys it, with their own credentials. It is part of the app
// only when BRANCHBOOK_GUARDRAILS=true, which the deploy job never sets.
if (process.env.BRANCHBOOK_GUARDRAILS === 'true') {
  new GuardrailsStack(app, 'BranchbookGuardrails', {
    env: { account, region: accountConfig.workloadRegion },
    description: 'BranchBook: CloudTrail, GuardDuty, AWS Config, Security Hub, Access Analyzer and the deployment policies (no customer data).',
    config: accountConfig,
  });
}

new ReleaseStack(app, 'BranchbookRelease', {
  env: { account, region: 'af-south-1' },
  description: 'BranchBook: records the git commit most recently deployed by the pipeline.',
  // GitHub Actions sets GITHUB_SHA to the commit being built; a manual deploy is labelled "local".
  version: process.env.GITHUB_SHA ?? 'local',
});

// The hosted demo (ADR-0006) is part of the app only when BRANCHBOOK_HOSTED_DEMO is set: in the pipeline's deploy
// job, which deploys it on every push to main (ADR-0007), in the credential-free infra job, which only synthesises
// it, or on an administrator's machine.
if (process.env.BRANCHBOOK_HOSTED_DEMO === 'true') {
  const data = new DataStack(app, 'BranchbookData', {
    env: { account, region: 'af-south-1' },
    description: 'BranchBook hosted demo: the network and the PostgreSQL 17 database (synthetic data only).',
    availabilityZones: ['af-south-1a', 'af-south-1b'],
  });
  new AppStack(app, 'BranchbookApp', {
    env: { account, region: 'af-south-1' },
    description: 'BranchBook hosted demo: WAF, the HTTPS load balancer and the Fargate task.',
    vpc: data.vpc,
    database: data.database,
    smsCapKey: data.smsCapKey,
    domainName: context('demoDomain'),
    hostedZoneId: context('demoHostedZoneId'),
    // Personal data, so never committed: an environment secret in the pipeline, if one is set. The alarms still
    // exist without it, on a topic with no subscriber.
    alertEmail: optionalEnv('BRANCHBOOK_DEMO_ALERT_EMAIL'),
    // BRANCHBOOK_SMS_AWS_DRY_RUN=true, the app's own setting's name, deploys a task that asks AWS to check each send
    // without sending it.
    smsDryRun: process.env.BRANCHBOOK_SMS_AWS_DRY_RUN === 'true',
  });
}

Tags.of(app).add('project', 'branchbook');
Tags.of(app).add('managed-by', 'aws-cdk');
