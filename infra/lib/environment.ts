import { App } from 'aws-cdk-lib/core';
import { AppStack } from './app-stack';
import { EnvironmentConfig, RegistryConfig } from './config';
import { DataStack } from './data-stack';
import { ReleaseStack } from './release-stack';

/**
 * What the pipeline, or an administrator, supplies at deploy time for one environment.
 */
export interface EnvironmentInputs {
  /** The account, from the credentials in use; undefined during a credential-free synth. */
  readonly account?: string;
  /** The image every task runs, from `BRANCHBOOK_IMAGE_DIGEST`. */
  readonly imageDigest: string;
  /** The commit being deployed, from `GITHUB_SHA`, or `local`. */
  readonly version: string;
  /** Where the alarms are e-mailed, if anywhere (personal data, never committed). */
  readonly alertEmail?: string;
  /** Whether AWS only checks each SMS send (`BRANCHBOOK_SMS_AWS_DRY_RUN=true`). */
  readonly smsDryRun?: boolean;
}

/** One environment's three stacks. */
export interface EnvironmentStacks {
  /** `<prefix>Data`: the network and the database. */
  readonly data: DataStack;
  /** `<prefix>App`: WAF, the load balancer, the service and the migration. */
  readonly app: AppStack;
  /** `<prefix>Release`: the deployed commit and image digest. */
  readonly release: ReleaseStack;
}

/**
 * Adds one environment's stacks to the CDK app, named from its `stackIdPrefix`: production keeps the IDs its live
 * stacks were deployed under (`BranchbookData`, `BranchbookApp`, `BranchbookRelease`), so nothing live is renamed or
 * replaced, and staging's are `BranchbookStagingData`, `BranchbookStagingApp` and `BranchbookStagingRelease`.
 *
 * @param app - the CDK app
 * @param config - the environment, from infra/config
 * @param registry - the registry the image is pulled from
 * @param inputs - the deploy-time values
 * @throws {Error} when the environment does not run in the registry's Region
 */
export function addEnvironment(
  app: App,
  config: EnvironmentConfig,
  registry: RegistryConfig,
  inputs: EnvironmentInputs,
): EnvironmentStacks {
  if (config.region !== registry.region) {
    throw new Error(`${config.name} runs in ${config.region}, but the image registry is in ${registry.region}`);
  }
  const env = { account: inputs.account, region: config.region };
  const id = config.stackIdPrefix;

  const data = new DataStack(app, `${id}Data`, {
    env,
    description: `BranchBook ${config.name}: the network and the PostgreSQL 17 database.`,
    config,
  });
  const appStack = new AppStack(app, `${id}App`, {
    env,
    description: `BranchBook ${config.name}: WAF, the HTTPS load balancer, the Fargate service and the schema migration.`,
    config,
    vpc: data.vpc,
    database: data.database,
    smsCapKey: data.smsCapKey,
    repositoryName: registry.repositoryName,
    imageDigest: inputs.imageDigest,
    alertEmail: inputs.alertEmail,
    smsDryRun: inputs.smsDryRun,
  });
  const release = new ReleaseStack(app, `${id}Release`, {
    env,
    description: `BranchBook ${config.name}: records the git commit and image digest most recently deployed.`,
    resourcePrefix: config.resourcePrefix,
    version: inputs.version,
    imageDigest: inputs.imageDigest,
  });
  return { data, app: appStack, release };
}
