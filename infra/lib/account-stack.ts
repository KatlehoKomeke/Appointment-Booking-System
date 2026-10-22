import { CfnOutput, Duration, Stack, StackProps } from 'aws-cdk-lib/core';
import * as budgets from 'aws-cdk-lib/aws-budgets';
import * as iam from 'aws-cdk-lib/aws-iam';
import { Construct } from 'constructs';

/** The issuer of the OpenID Connect tokens that GitHub Actions presents to AWS. */
export const GITHUB_OIDC_URL = 'https://token.actions.githubusercontent.com';

/** The audience GitHub Actions requests when it asks for a token meant for AWS STS. */
export const GITHUB_OIDC_AUDIENCE = 'sts.amazonaws.com';

/**
 * Name of the IAM role that GitHub Actions assumes. Fixed, so the role ARN is predictable and can be stored as the
 * secret `AWS_DEPLOY_ROLE_ARN` of the `aws-demo` GitHub environment (see infra/README.md).
 */
export const DEPLOY_ROLE_NAME = 'branchbook-github-deploy';

/**
 * Configuration for {@link AccountStack}.
 */
export interface AccountStackProps extends StackProps {
  /** The GitHub repository allowed to deploy, as `owner/name`, for example `KatlehoKomeke/Appointment-Booking-System`. */
  readonly githubRepository: string;

  /**
   * The numeric ID of the repository owner on GitHub. Public, not a secret (`gh api repos/OWNER/REPO --jq .owner.id`).
   * GitHub's immutable subject claims include it, so a deleted-and-recreated account with the same name cannot match.
   */
  readonly githubOwnerId: string;

  /**
   * The numeric ID of the repository on GitHub. Public, not a secret (`gh api repos/OWNER/REPO --jq .id`).
   * Included for the same reason: a new repository created later under the same name gets a different ID.
   */
  readonly githubRepositoryId: string;

  /**
   * The GitHub environment a workflow job must declare before it may assume the deploy role. Requiring an
   * environment, not just the repository, means a workflow on an unprotected branch or a fork cannot deploy.
   */
  readonly githubEnvironment: string;

  /** Where budget alerts are e-mailed. Supplied at deploy time and never committed (see bin/branchbook.ts). */
  readonly alertEmail: string;

  /** Monthly cost, in US dollars, at which the budget alerts are measured (they fire at 50, 80 and 100 percent). */
  readonly monthlyBudgetUsd: number;
}

/**
 * The account-level connection between GitHub and AWS.
 *
 * It creates three things, none of which costs anything to run:
 * - **GitHub as a trusted identity provider** (OpenID Connect), so workflows obtain short-lived AWS credentials
 *   and no long-lived access key exists anywhere to leak.
 * - **A deploy role** that only one repository's jobs in one GitHub environment can assume. The role can do nothing
 *   except assume the roles that `cdk bootstrap` created; those roles carry out the deployment.
 * - **A monthly cost budget** that e-mails at 50, 80 and 100 percent of the limit.
 *
 * IAM and AWS Budgets are global, so this stack is deployed once to `us-east-1`, where Budgets lives. It holds no
 * customer data; everything that does lives in `af-south-1` (see docs/adr/0006-hosted-demo-on-aws-with-cdk.md).
 */
export class AccountStack extends Stack {
  /** The role GitHub Actions assumes; its ARN is exported as the stack output `DeployRoleArn`. */
  public readonly deployRole: iam.Role;

  constructor(scope: Construct, id: string, props: AccountStackProps) {
    super(scope, id, props);
    validateProps(props);

    const github = new iam.OidcProviderNative(this, 'GitHubOidcProvider', {
      url: GITHUB_OIDC_URL,
      clientIds: [GITHUB_OIDC_AUDIENCE],
    });

    // The `sub` claim pins the trust to one repository AND one environment: a token minted for any other repository,
    // branch-only workflow or fork does not match, so it cannot assume the role. GitHub issues immutable subjects,
    // which carry the numeric owner and repository IDs next to their names (see githubSubject).
    this.deployRole = new iam.Role(this, 'DeployRole', {
      roleName: DEPLOY_ROLE_NAME,
      description: `Assumed by GitHub Actions in ${props.githubRepository} (environment ${props.githubEnvironment}) to run cdk deploy.`,
      maxSessionDuration: Duration.hours(1),
      assumedBy: new iam.WebIdentityPrincipal(github.oidcProviderArn, {
        StringEquals: {
          'token.actions.githubusercontent.com:aud': GITHUB_OIDC_AUDIENCE,
          'token.actions.githubusercontent.com:sub': githubSubject(props),
        },
      }),
    });

    // Least privilege: CDK deploys through the roles `cdk bootstrap` created (named cdk-hnb659fds-*), so this role
    // only needs permission to assume those. It cannot touch any resource directly.
    this.deployRole.addToPolicy(
      new iam.PolicyStatement({
        sid: 'AssumeCdkBootstrapRolesOnly',
        actions: ['sts:AssumeRole'],
        resources: [`arn:${this.partition}:iam::${this.account}:role/cdk-hnb659fds-*`],
      }),
    );

    new budgets.CfnBudget(this, 'MonthlyBudget', {
      budget: {
        budgetName: 'branchbook-monthly',
        budgetType: 'COST',
        timeUnit: 'MONTHLY',
        budgetLimit: { amount: props.monthlyBudgetUsd, unit: 'USD' },
      },
      notificationsWithSubscribers: [50, 80, 100].map((threshold) => ({
        notification: {
          notificationType: 'ACTUAL',
          comparisonOperator: 'GREATER_THAN',
          threshold,
          thresholdType: 'PERCENTAGE',
        },
        subscribers: [{ subscriptionType: 'EMAIL', address: props.alertEmail }],
      })),
    });

    // Store this value as the secret AWS_DEPLOY_ROLE_ARN of the aws-demo GitHub environment. It is kept out of the
    // repository because it contains the AWS account ID.
    new CfnOutput(this, 'DeployRoleArn', {
      value: this.deployRole.roleArn,
      description: 'Store as the secret AWS_DEPLOY_ROLE_ARN of the aws-demo GitHub environment.',
    });
  }
}

/**
 * The exact `sub` claim GitHub puts in the OIDC token of a job that declares the environment, in GitHub's immutable
 * subject format: `repo:OWNER@OWNER_ID/REPO@REPO_ID:environment:NAME`. The repository setting is visible with
 * `gh api repos/OWNER/REPO/actions/oidc/customization/sub` (`use_immutable_subject: true`).
 */
export function githubSubject(props: Pick<AccountStackProps, 'githubRepository' | 'githubOwnerId' | 'githubRepositoryId' | 'githubEnvironment'>): string {
  const [owner, repository] = props.githubRepository.split('/');
  return `repo:${owner}@${props.githubOwnerId}/${repository}@${props.githubRepositoryId}:environment:${props.githubEnvironment}`;
}

/**
 * Fails synthesis early, with a clear message, when the configuration would create a role that trusts the wrong
 * repository or a budget that cannot alert anyone.
 */
function validateProps(props: AccountStackProps): void {
  if (!/^[A-Za-z0-9-]+\/[A-Za-z0-9._-]+$/.test(props.githubRepository)) {
    throw new Error(`githubRepository must look like owner/name, got "${props.githubRepository}"`);
  }
  if (!/^\d+$/.test(props.githubOwnerId) || !/^\d+$/.test(props.githubRepositoryId)) {
    throw new Error('githubOwnerId and githubRepositoryId must be the numeric IDs GitHub assigns');
  }
  if (!/^[A-Za-z0-9._-]+$/.test(props.githubEnvironment)) {
    throw new Error(`githubEnvironment must be a plain environment name, got "${props.githubEnvironment}"`);
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(props.alertEmail)) {
    throw new Error('alertEmail must be an e-mail address (set BRANCHBOOK_ALERT_EMAIL before cdk synth or deploy)');
  }
  if (!(props.monthlyBudgetUsd > 0)) {
    throw new Error(`monthlyBudgetUsd must be a positive number, got ${props.monthlyBudgetUsd}`);
  }
}
