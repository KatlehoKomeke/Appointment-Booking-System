#!/usr/bin/env node
/**
 * Entry point of the BranchBook CDK app: declares every stack and where it is deployed.
 *
 * Stacks are split by lifecycle (docs/adr/0006-hosted-demo-on-aws-with-cdk.md):
 * - `BranchbookRegistry` (af-south-1): the ECR repository the pipeline pushes each commit's image to. Always in the
 *   app.
 * - `BranchbookAccount` (us-east-1): the GitHub-to-AWS connection and the cost budget. Deployed by an administrator
 *   from their own machine, never by the pipeline (see below).
 * - `BranchbookGuardrails` (af-south-1): the audit trail, the detective controls and the policies that limit every
 *   deployment. Deployed by an administrator, never by the pipeline (see below).
 * - One environment's three stacks, when `BRANCHBOOK_ENV` is `staging` or `production`: `<prefix>Data` (network and
 *   database), `<prefix>App` (WAF, load balancer, service, migration) and `<prefix>Release` (the deployed commit and
 *   image digest). The prefix is `Branchbook` for production, the IDs its live stacks were deployed under, and
 *   `BranchbookStaging` for staging. Only the selected environment's stacks exist in the app.
 *
 * Every per-environment value comes from infra/config/<env>.json, checked by lib/config.ts before any stack is
 * built. Deploy-time values come from environment variables: the image digest, the commit, and anything personal.
 * Nothing sensitive is ever written to the repository.
 */
import { App, Tags } from 'aws-cdk-lib/core';
import { AccountStack, loadAccountConfig } from '../lib/account-stack';
import { loadEnvironmentConfig, loadRegistryConfig, requireImageDigest } from '../lib/config';
import { addEnvironment } from '../lib/environment';
import { GuardrailsStack } from '../lib/guardrails-stack';
import { RegistryStack } from '../lib/registry-stack';

const app = new App();

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

// The switch this app used before environments existed. Failing loudly beats deploying nothing in silence.
if (process.env.BRANCHBOOK_HOSTED_DEMO !== undefined) {
  throw new Error('BRANCHBOOK_HOSTED_DEMO is replaced by BRANCHBOOK_ENV=production (or staging) and BRANCHBOOK_IMAGE_DIGEST');
}

// The account comes from the credentials in use, so no account ID is ever written to the repository.
const account = process.env.CDK_DEFAULT_ACCOUNT;
const registry = loadRegistryConfig();

new RegistryStack(app, 'BranchbookRegistry', {
  env: { account, region: registry.region },
  description: 'BranchBook: the ECR repository each commit\'s image is pushed to once and promoted from by digest.',
  config: registry,
});

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

// One environment, selected by BRANCHBOOK_ENV: the pipeline's deploy jobs set it per GitHub environment, the
// credential-free infra job synthesises each in turn, and an administrator sets it to deploy by hand.
const environmentName = optionalEnv('BRANCHBOOK_ENV');
if (environmentName !== undefined) {
  addEnvironment(app, loadEnvironmentConfig(environmentName), registry, {
    account,
    // The image the pipeline built, tested and pushed once; every environment runs it by digest.
    imageDigest: requireImageDigest(optionalEnv('BRANCHBOOK_IMAGE_DIGEST')),
    // GitHub Actions sets GITHUB_SHA to the commit being built; a manual deploy is labelled "local".
    version: process.env.GITHUB_SHA ?? 'local',
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
