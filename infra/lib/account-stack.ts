import { readFileSync } from 'node:fs';
import * as path from 'node:path';
import { CfnOutput, Duration, Stack, StackProps } from 'aws-cdk-lib/core';
import * as budgets from 'aws-cdk-lib/aws-budgets';
import * as iam from 'aws-cdk-lib/aws-iam';
import { Construct } from 'constructs';
import {
  ENVIRONMENT_NAMES,
  EnvironmentConfig,
  EnvironmentName,
  loadEnvironmentConfig,
  loadRegistryConfig,
  RegistryConfig,
} from './config';
import { syntheticTrafficSecretName } from './data-stack';
import { environmentStackIds } from './environment';
import { REGISTRY_STACK_ID } from './registry-stack';
import { releaseImageParameterName, releaseParameterName } from './release-stack';

/** The issuer of the OpenID Connect tokens that GitHub Actions presents to AWS. */
export const GITHUB_OIDC_URL = 'https://token.actions.githubusercontent.com';

/** The audience GitHub Actions requests when it asks for a token meant for AWS STS. */
export const GITHUB_OIDC_AUDIENCE = 'sts.amazonaws.com';

/** Where the account-level configuration lives. Every value in it is public; nothing personal or secret belongs there. */
export const ACCOUNT_CONFIG_PATH = path.join(__dirname, '..', 'config', 'account.json');

/**
 * The IDs of the stacks only an administrator deploys, with their own credentials. No environment's pipeline identity
 * may touch them.
 */
export const ADMINISTRATOR_STACK_IDS = { account: 'BranchbookAccount', guardrails: 'BranchbookGuardrails' } as const;

/**
 * The roles `cdk bootstrap` creates that a deployment assumes. The cfn-exec role is left out on purpose: only
 * CloudFormation may assume it, so naming it would grant nothing and suggest otherwise.
 */
export const CDK_BOOTSTRAP_ROLE_KINDS = ['deploy', 'file-publishing', 'image-publishing', 'lookup'] as const;

/**
 * The GuardDuty protection plans the configuration may switch on or off, by their API names
 * (`DetectorFeatureConfiguration`). `EKS_RUNTIME_MONITORING` is left out because `RUNTIME_MONITORING` replaces it, and
 * naming both is an error.
 */
export const GUARDDUTY_FEATURES = [
  'S3_DATA_EVENTS',
  'EKS_AUDIT_LOGS',
  'EBS_MALWARE_PROTECTION',
  'RDS_LOGIN_EVENTS',
  'LAMBDA_NETWORK_LOGS',
  'RUNTIME_MONITORING',
] as const;

/** A GuardDuty protection plan's API name. */
export type GuardDutyFeature = (typeof GUARDDUTY_FEATURES)[number];

const FEATURE_STATUSES = ['ENABLED', 'DISABLED'] as const;
const FINDING_FREQUENCIES = ['FIFTEEN_MINUTES', 'ONE_HOUR', 'SIX_HOURS'] as const;
const RECORDING_FREQUENCIES = ['CONTINUOUS', 'DAILY'] as const;
const SNAPSHOT_FREQUENCIES = ['One_Hour', 'Three_Hours', 'Six_Hours', 'Twelve_Hours', 'TwentyFour_Hours'] as const;

/** The repository allowed to deploy. Its numeric IDs are public, not secrets (`gh api repos/OWNER/REPO`). */
export interface GitHubRepositoryConfig {
  /** `owner/name`, for example `KatlehoKomeke/Appointment-Booking-System`. */
  readonly repository: string;
  /**
   * The owner's numeric ID (`.owner.id`). GitHub's immutable subject claims include it, so a deleted-and-recreated
   * account with the same name cannot match.
   */
  readonly ownerId: string;
  /** The repository's numeric ID (`.id`): a repository created later under the same name gets a different one. */
  readonly repositoryId: string;
}

/**
 * How one environment's pipeline reaches AWS. Everything else about the environment (its Region, names and stacks)
 * comes from infra/config/<name>.json, so it is never written twice.
 */
export interface DeployEnvironmentConfig {
  /**
   * The GitHub environment a workflow job must declare before it may assume this environment's deploy role.
   * Requiring an environment, not just the repository, means a job on an unprotected branch or a fork cannot deploy.
   */
  readonly githubEnvironment: string;
  /**
   * The qualifier of the CDK bootstrap this environment deploys through. Each environment has its own, so its deploy
   * role can assume only its own bootstrap roles, whose execution policy changes only its own stacks' IAM roles.
   * Production keeps `hnb659fds`, CDK's default, which its live stacks were deployed with.
   */
  readonly cdkBootstrapQualifier: string;
}

/** What the pipeline's deploy roles may do, and for which environments. */
export interface DeployConfig {
  /** The longest a deploy role's session may last, in minutes (IAM allows 60 to 720). */
  readonly maxSessionMinutes: number;
  /**
   * The environment whose pipeline identity deploys `BranchbookRegistry`: the registry is created by the first job of
   * every pipeline run, which runs in that environment before any environment is deployed. The others only pull from
   * it by digest.
   */
  readonly registryEnvironment: EnvironmentName;
  /** Each environment, by the name `BRANCHBOOK_ENV` selects it with. */
  readonly environments: Readonly<Record<EnvironmentName, DeployEnvironmentConfig>>;
}

/** The monthly cost budget. */
export interface BudgetConfig {
  /** The monthly limit, in US dollars. */
  readonly monthlyUsd: number;
  /** The percentages of the limit at which actual spend e-mails an alert. */
  readonly alertThresholdsPercent: readonly number[];
}

/** The account guardrails' settings (see guardrails-stack.ts). */
export interface GuardrailsConfig {
  /** How many days the audit bucket keeps CloudTrail logs and AWS Config history. */
  readonly auditLogRetentionDays: number;
  /** How many days the audit bucket keeps an overwritten or deleted object's previous version. */
  readonly auditLogNoncurrentVersionDays: number;
  /** GuardDuty's settings. */
  readonly guardDuty: {
    /** How often GuardDuty republishes updates to an active finding. */
    readonly findingPublishingFrequency: (typeof FINDING_FREQUENCIES)[number];
    /** The protection plans to switch on or off explicitly; any plan left out keeps GuardDuty's default. */
    readonly features: Readonly<Partial<Record<GuardDutyFeature, (typeof FEATURE_STATUSES)[number]>>>;
  };
  /** AWS Config's settings. */
  readonly config: {
    /** Record every change as it happens (`CONTINUOUS`), or the day's last state once a day (`DAILY`, cheaper). */
    readonly recordingFrequency: (typeof RECORDING_FREQUENCIES)[number];
    /** How often AWS Config writes a full configuration snapshot to the audit bucket. */
    readonly snapshotDeliveryFrequency: (typeof SNAPSHOT_FREQUENCIES)[number];
  };
}

/** The account-level configuration in infra/config/account.json, validated by {@link parseAccountConfig}. */
export interface AccountConfig {
  /** The prefix of every account-level name, for example `branchbook` in `branchbook-github-deploy-staging`. */
  readonly namePrefix: string;
  /** The repository allowed to deploy. */
  readonly github: GitHubRepositoryConfig;
  /** Where `BranchbookAccount` lives: IAM is global and AWS Budgets lives in `us-east-1`. */
  readonly accountRegion: string;
  /** The monthly cost budget. */
  readonly budget: BudgetConfig;
  /** The pipeline's deploy roles. */
  readonly deploy: DeployConfig;
  /** The account guardrails. */
  readonly guardrails: GuardrailsConfig;
}

/**
 * Reads and validates the account-level configuration.
 *
 * @param file - the JSON file to read; infra/config/account.json unless a test passes another
 */
export function loadAccountConfig(file: string = ACCOUNT_CONFIG_PATH): AccountConfig {
  let raw: unknown;
  try {
    raw = JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`${file}: cannot be read as JSON (${String(error)})`, { cause: error });
  }
  return parseAccountConfig(raw, file);
}

/**
 * Validates an already-parsed account configuration, failing synthesis with a message that names the file, the
 * field and what was expected, so a typo never reaches AWS as a role that trusts the wrong subject. A key nobody reads
 * is refused too, so a misspelt or retired setting cannot be ignored in silence.
 *
 * @param raw - the parsed JSON
 * @param source - where it came from, for error messages
 */
export function parseAccountConfig(raw: unknown, source = 'infra/config/account.json'): AccountConfig {
  const fail = (at: string, problem: string): never => {
    throw new Error(`${source}: "${at}" ${problem}`);
  };
  const object = (value: unknown, at: string, keys: readonly string[]): Record<string, unknown> => {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) {
      return fail(at, 'must be an object');
    }
    const unknown = Object.keys(value).find((key) => !keys.includes(key));
    if (unknown !== undefined) {
      fail(at === '(root)' ? unknown : `${at}.${unknown}`, 'is not a known setting; check its spelling');
    }
    return value as Record<string, unknown>;
  };
  const text = (value: unknown, at: string, pattern: RegExp, expected: string): string =>
    typeof value === 'string' && pattern.test(value) ? value : fail(at, `must be ${expected}, got ${JSON.stringify(value)}`);
  const whole = (value: unknown, at: string, min: number, max: number): number =>
    typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max
      ? value
      : fail(at, `must be a whole number from ${String(min)} to ${String(max)}, got ${JSON.stringify(value)}`);
  const oneOf = <T extends string>(value: unknown, at: string, allowed: readonly T[]): T =>
    allowed.includes(value as T) ? (value as T) : fail(at, `must be one of ${allowed.join(', ')}, got ${JSON.stringify(value)}`);
  const list = (value: unknown, at: string): unknown[] =>
    Array.isArray(value) && value.length > 0 ? value : fail(at, 'must be a non-empty list');

  const root = object(raw, '(root)', ['namePrefix', 'github', 'accountRegion', 'budget', 'deploy', 'guardrails']);
  const github = object(root.github, 'github', ['repository', 'ownerId', 'repositoryId']);
  const budget = object(root.budget, 'budget', ['monthlyUsd', 'alertThresholdsPercent']);
  const deploy = object(root.deploy, 'deploy', ['maxSessionMinutes', 'registryEnvironment', 'environments']);
  const guardrails = object(root.guardrails, 'guardrails', ['auditLogRetentionDays', 'auditLogNoncurrentVersionDays', 'guardDuty', 'config']);
  const guardDuty = object(guardrails.guardDuty, 'guardrails.guardDuty', ['findingPublishingFrequency', 'features']);
  const awsConfig = object(guardrails.config, 'guardrails.config', ['recordingFrequency', 'snapshotDeliveryFrequency']);

  // Every environment the CDK app can build needs a deploy role, and nothing else may have one.
  const rawEnvironments = object(deploy.environments, 'deploy.environments', ENVIRONMENT_NAMES);
  const environments = {} as Record<EnvironmentName, DeployEnvironmentConfig>;
  for (const name of ENVIRONMENT_NAMES) {
    const at = `deploy.environments.${name}`;
    if (!(name in rawEnvironments)) {
      fail(at, 'is missing: every environment needs a deploy role');
    }
    const environment = object(rawEnvironments[name], at, ['githubEnvironment', 'cdkBootstrapQualifier']);
    environments[name] = {
      githubEnvironment: text(environment.githubEnvironment, `${at}.githubEnvironment`, /^[A-Za-z0-9._-]+$/, 'a plain GitHub environment name'),
      cdkBootstrapQualifier: text(
        environment.cdkBootstrapQualifier,
        `${at}.cdkBootstrapQualifier`,
        /^[a-z0-9]{1,10}$/,
        'a CDK bootstrap qualifier (up to 10 lower-case letters and digits)',
      ),
    };
  }
  // Two environments that shared a GitHub environment, or a bootstrap, would not be separate.
  for (const key of ['githubEnvironment', 'cdkBootstrapQualifier'] as const) {
    const values = ENVIRONMENT_NAMES.map((name) => environments[name][key]);
    if (new Set(values).size !== values.length) {
      fail(`deploy.environments.*.${key}`, 'must differ between environments');
    }
  }

  const features: Partial<Record<GuardDutyFeature, (typeof FEATURE_STATUSES)[number]>> = {};
  const rawFeatures = object(guardDuty.features, 'guardrails.guardDuty.features', GUARDDUTY_FEATURES);
  for (const [name, status] of Object.entries(rawFeatures)) {
    features[name as GuardDutyFeature] = oneOf(status, `guardrails.guardDuty.features.${name}`, FEATURE_STATUSES);
  }

  return {
    namePrefix: text(root.namePrefix, 'namePrefix', /^[a-z][a-z0-9-]{0,30}$/, 'a short lower-case name prefix'),
    github: {
      repository: text(github.repository, 'github.repository', /^[A-Za-z0-9-]+\/[A-Za-z0-9._-]+$/, 'owner/name'),
      ownerId: text(github.ownerId, 'github.ownerId', /^\d+$/, 'the numeric ID GitHub assigns'),
      repositoryId: text(github.repositoryId, 'github.repositoryId', /^\d+$/, 'the numeric ID GitHub assigns'),
    },
    accountRegion: text(root.accountRegion, 'accountRegion', /^[a-z]{2}(-[a-z]+)+-\d$/, 'an AWS Region name such as us-east-1'),
    budget: {
      monthlyUsd: whole(budget.monthlyUsd, 'budget.monthlyUsd', 1, 100_000),
      alertThresholdsPercent: list(budget.alertThresholdsPercent, 'budget.alertThresholdsPercent').map((value, index) =>
        whole(value, `budget.alertThresholdsPercent[${String(index)}]`, 1, 100),
      ),
    },
    deploy: {
      maxSessionMinutes: whole(deploy.maxSessionMinutes, 'deploy.maxSessionMinutes', 60, 720),
      registryEnvironment: oneOf(deploy.registryEnvironment, 'deploy.registryEnvironment', ENVIRONMENT_NAMES),
      environments,
    },
    guardrails: {
      auditLogRetentionDays: whole(guardrails.auditLogRetentionDays, 'guardrails.auditLogRetentionDays', 1, 3650),
      auditLogNoncurrentVersionDays: whole(guardrails.auditLogNoncurrentVersionDays, 'guardrails.auditLogNoncurrentVersionDays', 1, 3650),
      guardDuty: {
        findingPublishingFrequency: oneOf(guardDuty.findingPublishingFrequency, 'guardrails.guardDuty.findingPublishingFrequency', FINDING_FREQUENCIES),
        features,
      },
      config: {
        recordingFrequency: oneOf(awsConfig.recordingFrequency, 'guardrails.config.recordingFrequency', RECORDING_FREQUENCIES),
        snapshotDeliveryFrequency: oneOf(awsConfig.snapshotDeliveryFrequency, 'guardrails.config.snapshotDeliveryFrequency', SNAPSHOT_FREQUENCIES),
      },
    },
  };
}

/** The configuration of everything the pipeline deploys, which the account-level stacks derive their names from. */
export interface WorkloadConfigs {
  /** Each environment, from infra/config/<name>.json. */
  readonly environments: Readonly<Record<EnvironmentName, EnvironmentConfig>>;
  /** The image registry, from infra/config/registry.json. */
  readonly registry: RegistryConfig;
}

/**
 * Reads every environment's configuration and the registry's.
 *
 * @param registry - the registry configuration, when the caller has already read it
 */
export function loadWorkloadConfigs(registry: RegistryConfig = loadRegistryConfig()): WorkloadConfigs {
  const environments = {} as Record<EnvironmentName, EnvironmentConfig>;
  for (const name of ENVIRONMENT_NAMES) {
    environments[name] = loadEnvironmentConfig(name);
  }
  return { environments, registry };
}

/** One environment as the account-level stacks see it: every value derived from the configuration, none repeated. */
export interface DeployTarget {
  /** The environment's name, for example `staging`. */
  readonly name: EnvironmentName;
  /** The GitHub environment whose jobs may assume the deploy role. */
  readonly githubEnvironment: string;
  /** The qualifier of the CDK bootstrap the environment deploys through. */
  readonly bootstrapQualifier: string;
  /** The Region the environment, its bootstrap and the image registry are in. */
  readonly region: string;
  /** The prefix of the environment's physical names, from its `resourcePrefix`. */
  readonly resourcePrefix: string;
  /** The secret holding the synthetic-traffic token, if the environment lets synthetic traffic skip the CAPTCHA. */
  readonly syntheticTrafficSecretName?: string;
  /** Every stack the environment's pipeline identity deploys: its own three, and the registry if it owns that. */
  readonly stackNames: readonly string[];
}

/**
 * Derives each environment's deploy target from the account configuration and the environments' own files.
 *
 * @param config - the account configuration
 * @param workloads - the environments' and the registry's configuration
 * @throws {Error} when an environment does not run in the registry's Region, the only one its policies cover
 */
export function deployTargets(config: AccountConfig, workloads: WorkloadConfigs): DeployTarget[] {
  return ENVIRONMENT_NAMES.map((name) => {
    const environment = workloads.environments[name];
    if (environment.region !== workloads.registry.region) {
      throw new Error(`${name} runs in ${environment.region}, but the image registry, and every deploy policy, is in ${workloads.registry.region}`);
    }
    const stacks = environmentStackIds(environment);
    return {
      name,
      githubEnvironment: config.deploy.environments[name].githubEnvironment,
      bootstrapQualifier: config.deploy.environments[name].cdkBootstrapQualifier,
      region: environment.region,
      resourcePrefix: environment.resourcePrefix,
      syntheticTrafficSecretName: environment.waf.syntheticTrafficBypass ? syntheticTrafficSecretName(environment.resourcePrefix) : undefined,
      stackNames: [stacks.data, stacks.app, stacks.release, ...(config.deploy.registryEnvironment === name ? [REGISTRY_STACK_ID] : [])],
    };
  });
}

/**
 * The fixed name of an environment's deploy role, for example `branchbook-github-deploy-staging`. Fixed, so the ARN
 * is predictable and can be stored as the secret `AWS_DEPLOY_ROLE_ARN` of the GitHub environment.
 *
 * @param config - the account configuration, for its name prefix
 * @param environment - the environment's name, for example `staging`
 */
export function deployRoleName(config: Pick<AccountConfig, 'namePrefix'>, environment: string): string {
  return `${config.namePrefix}-github-deploy-${environment}`;
}

/**
 * The name `cdk bootstrap` gives one of its roles, for example `cdk-bbstg-deploy-role-<account>-af-south-1`.
 *
 * @param qualifier - the bootstrap's qualifier
 * @param kind - which role: `deploy`, `file-publishing`, `image-publishing`, `lookup` or `cfn-exec`
 * @param account - the account ID, or the token that stands for it
 * @param region - the Region the bootstrap is in
 */
export function bootstrapRoleName(qualifier: string, kind: string, account: string, region: string): string {
  return `cdk-${qualifier}-${kind}-role-${account}-${region}`;
}

/**
 * The exact `sub` claim GitHub puts in the OIDC token of a job that declares the environment, in GitHub's immutable
 * subject format: `repo:OWNER@OWNER_ID/REPO@REPO_ID:environment:NAME`. The repository setting is visible with
 * `gh api repos/OWNER/REPO/actions/oidc/customization/sub` (`use_immutable_subject: true`).
 *
 * @param github - the repository's name and numeric IDs
 * @param githubEnvironment - the GitHub environment the job declares
 */
export function githubSubject(github: GitHubRepositoryConfig, githubEnvironment: string): string {
  const [owner, repository] = github.repository.split('/');
  return `repo:${owner}@${github.ownerId}/${repository}@${github.repositoryId}:environment:${githubEnvironment}`;
}

/**
 * Configuration for {@link AccountStack}.
 */
export interface AccountStackProps extends StackProps {
  /** The account-level configuration, from {@link loadAccountConfig}. */
  readonly config: AccountConfig;

  /** The environments' and the registry's configuration, which the roles' names and scopes derive from. */
  readonly workloads: WorkloadConfigs;

  /** Where budget alerts are e-mailed. Supplied at deploy time and never committed (see lib/app.ts). */
  readonly alertEmail: string;
}

/**
 * The account-level connection between GitHub and AWS.
 *
 * It creates these, none of which costs anything to run:
 * - **GitHub as a trusted identity provider** (OpenID Connect), so workflows obtain short-lived AWS credentials
 *   and no long-lived access key exists anywhere to leak.
 * - **One deploy role per environment** (`branchbook-github-deploy-staging`, `branchbook-github-deploy-production`).
 *   Each trusts only jobs in this repository that declare its own GitHub environment, and may only assume its own
 *   CDK bootstrap's roles, read its own release records, push to and pull from the image repository, and, where the
 *   environment has one, read its synthetic-traffic token.
 * - **A deploy scope for each environment's bootstrap deploy role**: CDK's bootstrap lets that role run CloudFormation
 *   on any stack, so a deny keeps it off every other environment's stacks and the administrator's.
 * - **A monthly cost budget** that e-mails at the configured percentages of the limit.
 *
 * IAM and AWS Budgets are global, so this stack is deployed once to `us-east-1`, where Budgets lives. It holds no
 * customer data; everything that does lives in the workload Region (see docs/adr/0006-hosted-demo-on-aws-with-cdk.md).
 */
export class AccountStack extends Stack {
  /** Each environment's deploy role, by environment name; each ARN is a stack output. */
  public readonly deployRoles: Readonly<Record<string, iam.Role>>;

  constructor(scope: Construct, id: string, props: AccountStackProps) {
    super(scope, id, props);
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(props.alertEmail)) {
      throw new Error('alertEmail must be an e-mail address (set BRANCHBOOK_ALERT_EMAIL before cdk synth or deploy)');
    }
    const { config } = props;
    const targets = deployTargets(config, props.workloads);

    // The permissions boundary caps the roles the pipeline's stacks create, and forbids them to assume other roles
    // (guardrails-stack.ts). These roles are the pipeline's own identities, created by an administrator, and must
    // assume the bootstrap roles, so the boundary must never reach them, even when the app applies it to every stack.
    iam.PermissionsBoundary.of(this).clear();

    const github = new iam.OidcProviderNative(this, 'GitHubOidcProvider', {
      url: GITHUB_OIDC_URL,
      clientIds: [GITHUB_OIDC_AUDIENCE],
    });

    const roles: Record<string, iam.Role> = {};
    for (const target of targets) {
      const label = target.name.charAt(0).toUpperCase() + target.name.slice(1);
      const role = this.deployRole(`${label}DeployRole`, github, config, props.workloads.registry, target);
      roles[target.name] = role;
      // Stored as the secret AWS_DEPLOY_ROLE_ARN of the GitHub environment. It is kept out of the repository because
      // it contains the AWS account ID.
      new CfnOutput(this, `${label}DeployRoleArn`, {
        value: role.roleArn,
        description: `Store as the secret AWS_DEPLOY_ROLE_ARN of the ${target.githubEnvironment} GitHub environment.`,
      });
      this.deployScope(`${label}BootstrapDeployScope`, config, target, targets);
    }
    this.deployRoles = roles;

    new budgets.CfnBudget(this, 'MonthlyBudget', {
      budget: {
        budgetName: `${config.namePrefix}-monthly`,
        budgetType: 'COST',
        timeUnit: 'MONTHLY',
        budgetLimit: { amount: config.budget.monthlyUsd, unit: 'USD' },
      },
      notificationsWithSubscribers: config.budget.alertThresholdsPercent.map((threshold) => ({
        notification: {
          notificationType: 'ACTUAL',
          comparisonOperator: 'GREATER_THAN',
          threshold,
          thresholdType: 'PERCENTAGE',
        },
        subscribers: [{ subscriptionType: 'EMAIL', address: props.alertEmail }],
      })),
    });
  }

  /**
   * One environment's deploy role: trusted by that GitHub environment only, and able to do nothing beyond what the
   * pipeline needs for that environment.
   *
   * @param id - the role's construct ID
   * @param github - the GitHub OIDC provider the role trusts
   * @param config - the account configuration
   * @param registry - the image registry
   * @param target - the environment
   */
  private deployRole(
    id: string,
    github: iam.OidcProviderNative,
    config: AccountConfig,
    registry: RegistryConfig,
    target: DeployTarget,
  ): iam.Role {
    const { partition, account } = this;
    const { region } = target;

    // The `sub` claim pins the trust to one repository AND one environment: a token minted for any other repository,
    // environment, branch-only workflow or fork does not match. GitHub issues immutable subjects, which carry the
    // numeric owner and repository IDs next to their names (see githubSubject).
    const role = new iam.Role(this, id, {
      roleName: deployRoleName(config, target.name),
      description: `Assumed by GitHub Actions in ${config.github.repository} (environment ${target.githubEnvironment}) to deploy ${target.name}.`,
      maxSessionDuration: Duration.minutes(config.deploy.maxSessionMinutes),
      assumedBy: new iam.WebIdentityPrincipal(github.oidcProviderArn, {
        StringEquals: {
          'token.actions.githubusercontent.com:aud': GITHUB_OIDC_AUDIENCE,
          'token.actions.githubusercontent.com:sub': githubSubject(config.github, target.githubEnvironment),
        },
      }),
    });

    // CDK deploys through the roles `cdk bootstrap` created, so the role needs to assume those: only its own
    // bootstrap's, in its own Region. Another environment's bootstrap, and the us-east-1 one that deploys this stack,
    // stay out of reach.
    role.addToPolicy(
      new iam.PolicyStatement({
        sid: 'AssumeOwnCdkBootstrapRoles',
        actions: ['sts:AssumeRole'],
        resources: CDK_BOOTSTRAP_ROLE_KINDS.map(
          (kind) => `arn:${partition}:iam::${account}:role/${bootstrapRoleName(target.bootstrapQualifier, kind, account, region)}`,
        ),
      }),
    );

    // After deploying, the pipeline reads its own environment's release records back to prove what is live, and its
    // rollback reads the previous image digest. Another environment's records are out of reach.
    role.addToPolicy(
      new iam.PolicyStatement({
        sid: 'ReadOwnReleaseRecords',
        actions: ['ssm:GetParameter'],
        resources: [releaseParameterName(target.resourcePrefix), releaseImageParameterName(target.resourcePrefix)].map(
          (parameter) => `arn:${partition}:ssm:${region}:${account}:parameter${parameter}`,
        ),
      }),
    );

    // ECR issues its registry login token without naming a repository, so this one action cannot be scoped further.
    role.addToPolicy(
      new iam.PolicyStatement({
        sid: 'LogInToEcr',
        actions: ['ecr:GetAuthorizationToken'],
        resources: ['*'],
      }),
    );

    // The image is built once and pushed by digest, then each environment promotes that same digest. Describing images
    // lets the pipeline check a digest exists before it promotes it.
    role.addToPolicy(
      new iam.PolicyStatement({
        sid: 'PushAndPullTheImageRepository',
        actions: [
          'ecr:BatchCheckLayerAvailability',
          'ecr:BatchGetImage',
          'ecr:CompleteLayerUpload',
          'ecr:DescribeImages',
          'ecr:GetDownloadUrlForLayer',
          'ecr:InitiateLayerUpload',
          'ecr:PutImage',
          'ecr:UploadLayerPart',
        ],
        resources: [`arn:${partition}:ecr:${registry.region}:${account}:repository/${registry.repositoryName}`],
      }),
    );

    // The smoke test and end-to-end run send this token so the WAF CAPTCHA lets them through. Secrets Manager appends
    // six random characters to a secret's ARN; `??????` matches exactly those, and no secret that merely shares the
    // name as a prefix.
    if (target.syntheticTrafficSecretName !== undefined) {
      role.addToPolicy(
        new iam.PolicyStatement({
          sid: 'ReadSyntheticTrafficToken',
          actions: ['secretsmanager:GetSecretValue'],
          resources: [`arn:${partition}:secretsmanager:${region}:${account}:secret:${target.syntheticTrafficSecretName}-??????`],
        }),
      );
    }
    return role;
  }

  /**
   * Keeps one environment's bootstrap deploy role off every stack it does not own. `cdk bootstrap` gives that role
   * CloudFormation on every stack in the account, so without this deny, staging's pipeline could hand production's
   * stacks a change set, run by staging's execution role.
   *
   * @param id - the policy's construct ID
   * @param config - the account configuration
   * @param target - the environment whose deploy role is scoped
   * @param targets - every environment
   */
  private deployScope(id: string, config: AccountConfig, target: DeployTarget, targets: readonly DeployTarget[]): void {
    const { partition, account } = this;
    const others = [
      ...targets.filter((other) => other !== target).flatMap((other) => other.stackNames),
      ...Object.values(ADMINISTRATOR_STACK_IDS),
    ];
    new iam.ManagedPolicy(this, id, {
      managedPolicyName: `${config.namePrefix}-cdk-deploy-scope-${target.name}`,
      description: `Keeps the ${target.name} CDK bootstrap's deploy role off every stack ${target.name} does not own.`,
      roles: [iam.Role.fromRoleName(this, `${id}Role`, bootstrapRoleName(target.bootstrapQualifier, 'deploy', account, target.region))],
      statements: [
        new iam.PolicyStatement({
          sid: 'OnlyThisEnvironmentsStacks',
          effect: iam.Effect.DENY,
          actions: ['cloudformation:*'],
          // Any Region: the role is global, and so could reach a stack anywhere.
          resources: others.map((stack) => `arn:${partition}:cloudformation:*:${account}:stack/${stack}/*`),
        }),
      ],
    });
  }
}
