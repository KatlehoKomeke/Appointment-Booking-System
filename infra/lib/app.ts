/**
 * Which of BranchBook's stacks exist in the CDK app, and where each is deployed, for a given set of deploy-time
 * variables. bin/branchbook.ts calls {@link buildApp} with the process's environment; the tests call it with their
 * own, so what they check is the app `cdk synth` builds.
 *
 * Stacks are split by lifecycle (docs/adr/0006-hosted-demo-on-aws-with-cdk.md):
 * - `BranchbookRegistry` (af-south-1): the ECR repository the pipeline pushes each commit's image to. Always in the
 *   app, and deployed through the bootstrap of the environment named by `deploy.registryEnvironment` in
 *   infra/config/account.json (staging).
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
 *
 * @packageDocumentation
 */
import { App, DefaultStackSynthesizer, Tags } from 'aws-cdk-lib/core';
import { ADMINISTRATOR_STACK_IDS, AccountStack, loadAccountConfig, loadWorkloadConfigs, WorkloadConfigs } from './account-stack';
import { EnvironmentName, loadEnvironmentConfig, loadRegistryConfig, requireImageDigest } from './config';
import { addEnvironment, EnvironmentStacks } from './environment';
import { GuardrailsStack } from './guardrails-stack';
import { REGISTRY_STACK_ID, RegistryStack } from './registry-stack';

/** The deploy-time variables the app reads: `process.env` in bin/branchbook.ts, a plain object in tests. */
export type AppVariables = Readonly<Record<string, string | undefined>>;

/** The stacks {@link buildApp} added, by role; those whose switch was off are absent. */
export interface BranchbookStacks {
  /** `BranchbookRegistry`, always present. */
  readonly registry: RegistryStack;
  /** `BranchbookAccount`, when `BRANCHBOOK_ALERT_EMAIL` is set. */
  readonly account?: AccountStack;
  /** `BranchbookGuardrails`, when `BRANCHBOOK_GUARDRAILS=true`. */
  readonly guardrails?: GuardrailsStack;
  /** The selected environment's three stacks, when `BRANCHBOOK_ENV` is set. */
  readonly environment?: EnvironmentStacks;
}

/**
 * Adds BranchBook's stacks to a CDK app.
 *
 * @param app - the CDK app, with infra/cdk.json's context
 * @param variables - the deploy-time variables, normally `process.env`
 * @throws {Error} when a variable is missing or invalid, or a configuration file is refused
 */
export function buildApp(app: App, variables: AppVariables): BranchbookStacks {
  // Reads a variable, treating an empty value as unset: a workflow passes a secret that nobody has set as an empty
  // string, which must not become an e-mail address.
  const optional = (name: string): string | undefined => {
    const value = variables[name];
    return value === undefined || value.trim() === '' ? undefined : value;
  };

  // The switch this app used before environments existed. Failing loudly beats deploying nothing in silence.
  if (variables.BRANCHBOOK_HOSTED_DEMO !== undefined) {
    throw new Error('BRANCHBOOK_HOSTED_DEMO is replaced by BRANCHBOOK_ENV=production (or staging) and BRANCHBOOK_IMAGE_DIGEST');
  }

  // The account comes from the credentials in use, so no account ID is ever written to the repository.
  const account = variables.CDK_DEFAULT_ACCOUNT;
  const registryConfig = loadRegistryConfig();
  const accountConfig = loadAccountConfig();
  // Each environment deploys through its own CDK bootstrap, named by its qualifier in account.json.
  const qualifier = (name: EnvironmentName): string => accountConfig.deploy.environments[name].cdkBootstrapQualifier;

  // The registry belongs to the environment whose pipeline identity creates it: the first job of every pipeline run,
  // which runs in that environment (staging) before any environment is deployed. Production only pulls from it by
  // digest and never changes it, so it is synthesised with that environment's bootstrap qualifier.
  const registry = new RegistryStack(app, REGISTRY_STACK_ID, {
    env: { account, region: registryConfig.region },
    synthesizer: new DefaultStackSynthesizer({ qualifier: qualifier(accountConfig.deploy.registryEnvironment) }),
    description: 'BranchBook: the ECR repository each commit\'s image is pushed to once and promoted from by digest.',
    config: registryConfig,
  });

  // The account-level stacks derive their names and scopes from every environment's configuration, read only when
  // one of them is in the app.
  let workloads: WorkloadConfigs | undefined;
  const allWorkloads = (): WorkloadConfigs => (workloads ??= loadWorkloadConfigs(registryConfig));

  // BranchbookAccount defines the roles the pipeline itself assumes, so the pipeline must never be able to change it.
  // It is therefore only part of the app when BRANCHBOOK_ALERT_EMAIL is set: on an administrator's machine, or with
  // a placeholder in the credential-free infra job. The deploy jobs never set it, so the pipeline cannot even
  // synthesise this stack, let alone deploy it. The address is personal data and is never committed.
  const alertEmail = optional('BRANCHBOOK_ALERT_EMAIL');
  const accountStack =
    alertEmail === undefined
      ? undefined
      : new AccountStack(app, ADMINISTRATOR_STACK_IDS.account, {
          env: { account, region: accountConfig.accountRegion },
          description: 'BranchBook: GitHub OIDC deploy roles and monthly cost budget (no customer data).',
          config: accountConfig,
          workloads: allWorkloads(),
          alertEmail,
        });

  // BranchbookGuardrails holds the audit trail, the detective controls and the policies that limit every
  // deployment, so, like BranchbookAccount, only an administrator deploys it, with their own credentials. It is part
  // of the app only when BRANCHBOOK_GUARDRAILS=true, which the deploy jobs never set.
  const guardrails =
    variables.BRANCHBOOK_GUARDRAILS === 'true'
      ? new GuardrailsStack(app, ADMINISTRATOR_STACK_IDS.guardrails, {
          // Where the workloads run: every environment must run in the registry's Region.
          env: { account, region: registryConfig.region },
          description: 'BranchBook: CloudTrail, GuardDuty, AWS Config, Security Hub, Access Analyzer and the deployment policies (no customer data).',
          config: accountConfig,
          workloads: allWorkloads(),
        })
      : undefined;

  // One environment, selected by BRANCHBOOK_ENV: the pipeline's deploy jobs set it per GitHub environment, the
  // credential-free infra job synthesises each in turn, and an administrator sets it to deploy by hand.
  const environmentName = optional('BRANCHBOOK_ENV');
  const environmentConfig = environmentName === undefined ? undefined : loadEnvironmentConfig(environmentName);
  const environment =
    environmentConfig === undefined
      ? undefined
      : addEnvironment(app, environmentConfig, registryConfig, {
          account,
          bootstrapQualifier: qualifier(environmentConfig.name),
          // The image the pipeline built, tested and pushed once; every environment runs it by digest.
          imageDigest: requireImageDigest(optional('BRANCHBOOK_IMAGE_DIGEST')),
          // GitHub Actions sets GITHUB_SHA to the commit being built; a manual deploy is labelled "local".
          version: variables.GITHUB_SHA ?? 'local',
          // Personal data, so never committed: an environment secret in the pipeline, if one is set. The alarms
          // still exist without it, on a topic with no subscriber.
          alertEmail: optional('BRANCHBOOK_DEMO_ALERT_EMAIL'),
          // BRANCHBOOK_SMS_AWS_DRY_RUN=true, the app's own setting's name, deploys a task that asks AWS to check each
          // send without sending it.
          smsDryRun: variables.BRANCHBOOK_SMS_AWS_DRY_RUN === 'true',
        });

  Tags.of(app).add('project', 'branchbook');
  Tags.of(app).add('managed-by', 'aws-cdk');
  return { registry, account: accountStack, guardrails, environment };
}
