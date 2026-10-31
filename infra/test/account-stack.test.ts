/**
 * Unit tests for {@link AccountStack} and the account configuration: they prove the security properties of the
 * GitHub-to-AWS connection on the synthesised CloudFormation template, so a regression fails CI before anything
 * reaches AWS.
 */
import { App } from 'aws-cdk-lib/core';
import { Match, Template } from 'aws-cdk-lib/assertions';
import {
  AccountConfig,
  AccountStack,
  deployTargets,
  loadAccountConfig,
  loadWorkloadConfigs,
  parseAccountConfig,
  WorkloadConfigs,
} from '../lib/account-stack';

const config = loadAccountConfig();
const workloads = loadWorkloadConfigs();
const OWNER = 'KatlehoKomeke@88820937/Appointment-Booking-System@1423867330';
const SUBJECT_KEY = 'token.actions.githubusercontent.com:sub';

/** A deep copy of a configuration that a test may change before validating it. */
type Mutable<T> = { -readonly [K in keyof T]: Mutable<T[K]> };

/** The parts of a synthesised IAM statement that the assertions below read. */
interface Statement {
  Sid?: string;
  Effect: string;
  Action: string | string[];
  Resource: unknown;
  Condition?: Record<string, Record<string, unknown>>;
}

/** The parts of a synthesised `AWS::IAM::Role` resource that the assertions below read. */
interface RoleResource {
  Properties: { RoleName: string; AssumeRolePolicyDocument: { Statement: Statement[] }; PermissionsBoundary?: unknown };
}

/** The parts of a synthesised `AWS::IAM::Policy` resource that the assertions below read. */
interface PolicyResource {
  Properties: { Roles: { Ref: string }[]; PolicyDocument: { Statement: Statement[] } };
}

/** The parts of a synthesised `AWS::IAM::ManagedPolicy` resource that the assertions below read. */
interface ManagedPolicyResource {
  Properties: { ManagedPolicyName: string; Roles: string[]; PolicyDocument: { Statement: Statement[] } };
}

function synth(context: Record<string, unknown> = {}): Template {
  // Partition literals, as infra/cdk.json sets them, so ARNs in a stack with a known Region are plain strings.
  const app = new App({ context: { '@aws-cdk/core:enablePartitionLiterals': true, ...context } });
  return Template.fromStack(
    new AccountStack(app, 'Account', {
      env: { account: '111111111111', region: 'us-east-1' },
      config,
      workloads,
      alertEmail: 'alerts@example.com',
    }),
  );
}

/**
 * The role with the given name: its trust statements and the statements of the policies attached to it.
 *
 * @param template - the synthesised stack
 * @param roleName - the role's name
 */
function deployRole(template: Template, roleName: string): { trust: Statement[]; statements: Statement[] } {
  const roles = template.findResources('AWS::IAM::Role') as Record<string, RoleResource>;
  const [logicalId, role] = Object.entries(roles).find(([, candidate]) => candidate.Properties.RoleName === roleName) ?? [];
  if (logicalId === undefined || role === undefined) {
    throw new Error(`no role named ${roleName}`);
  }
  const policies = Object.values(template.findResources('AWS::IAM::Policy') as Record<string, PolicyResource>);
  const statements = policies
    .filter((policy) => policy.Properties.Roles.some((reference) => reference.Ref === logicalId))
    .flatMap((policy) => policy.Properties.PolicyDocument.Statement);
  return { trust: role.Properties.AssumeRolePolicyDocument.Statement, statements };
}

function statement(statements: Statement[], sid: string): Statement {
  const found = statements.find((candidate) => candidate.Sid === sid);
  if (found === undefined) {
    throw new Error(`no statement ${sid}`);
  }
  return found;
}

// Environment, deploy role, resourcePrefix (from config/<env>.json), the other environment, bootstrap qualifier.
const ENVIRONMENTS = [
  ['staging', 'branchbook-github-deploy-staging', 'branchbook-staging', 'production', 'bbstg'],
  ['production', 'branchbook-github-deploy-production', 'branchbook', 'staging', 'hnb659fds'],
] as const;

describe('the account configuration', () => {
  test('the committed file is valid, sets a 450 USD budget and gives staging and production their own bootstraps', () => {
    expect(config.budget.monthlyUsd).toBe(450);
    expect(config.budget.alertThresholdsPercent).toEqual([50, 80, 100]);
    expect(config.deploy.environments.production.cdkBootstrapQualifier).toBe('hnb659fds');
    expect(config.deploy.environments.staging.cdkBootstrapQualifier).toBe('bbstg');
    expect(config.deploy.registryEnvironment).toBe('staging');
  });

  const mutate = (change: (copy: Mutable<AccountConfig>) => void): unknown => {
    const copy = structuredClone(config) as Mutable<AccountConfig>;
    change(copy);
    return copy;
  };

  test.each([
    ['a repository without an owner', mutate((c) => (c.github.repository = 'Appointment-Booking-System')), /github.repository.*owner\/name/],
    ['a non-numeric repository ID', mutate((c) => (c.github.repositoryId = 'Appointment-Booking-System')), /github.repositoryId.*numeric ID/],
    ['a GitHub environment name with spaces', mutate((c) => (c.deploy.environments.staging.githubEnvironment = 'aws demo')), /githubEnvironment.*environment name/],
    ['two environments trusting the same GitHub environment', mutate((c) => (c.deploy.environments.staging.githubEnvironment = 'production')), /githubEnvironment" must differ/],
    ['two environments sharing a bootstrap', mutate((c) => (c.deploy.environments.staging.cdkBootstrapQualifier = 'hnb659fds')), /cdkBootstrapQualifier" must differ/],
    ['a qualifier CDK would refuse', mutate((c) => (c.deploy.environments.staging.cdkBootstrapQualifier = 'Branchbook-Staging')), /cdkBootstrapQualifier.*CDK bootstrap qualifier/],
    ['an environment without a deploy role', mutate((c) => delete (c.deploy.environments as Partial<typeof c.deploy.environments>).staging), /environments.staging" is missing/],
    ['an environment the app cannot build', mutate((c) => ((c.deploy.environments as Record<string, unknown>).qa = { githubEnvironment: 'qa', cdkBootstrapQualifier: 'bbqa' })), /environments.qa" is not a known setting/],
    ['a registry owned by no environment', mutate((c) => ((c.deploy as { registryEnvironment: string }).registryEnvironment = 'qa')), /registryEnvironment.*one of staging, production/],
    ['a retired setting', mutate((c) => ((c as Record<string, unknown>).workloadRegion = 'af-south-1')), /"workloadRegion" is not a known setting/],
    ['a zero budget', mutate((c) => (c.budget.monthlyUsd = 0)), /budget.monthlyUsd.*whole number/],
    ['a misspelt account Region', mutate((c) => (c.accountRegion = 'virginia')), /accountRegion.*AWS Region/],
    ['a session longer than IAM allows', mutate((c) => (c.deploy.maxSessionMinutes = 721)), /maxSessionMinutes/],
    ['an unknown GuardDuty plan', mutate((c) => ((c.guardrails.guardDuty.features as Record<string, string>).EKS_RUNTIME_MONITORING = 'ENABLED')), /features.EKS_RUNTIME_MONITORING" is not a known setting/],
    ['a missing section', mutate((c) => delete (c as Partial<typeof c>).guardrails), /"guardrails" must be an object/],
  ])('refuses %s', (_label, raw, message) => {
    expect(() => parseAccountConfig(raw)).toThrow(message);
  });
});

describe('the deploy targets', () => {
  test('derive every name from the environments\' and the registry\'s own configuration, never a copy', () => {
    const targets = deployTargets(config, workloads);
    expect(targets.map((target) => [target.name, target.resourcePrefix, target.region])).toEqual([
      ['staging', workloads.environments.staging.resourcePrefix, workloads.registry.region],
      ['production', workloads.environments.production.resourcePrefix, workloads.registry.region],
    ]);
  });

  test('give each environment its own three stacks, and the registry to staging, whose first job creates it', () => {
    expect(deployTargets(config, workloads).map((target) => [target.name, [...target.stackNames].sort()])).toEqual([
      ['staging', ['BranchbookRegistry', 'BranchbookStagingApp', 'BranchbookStagingData', 'BranchbookStagingRelease']],
      ['production', ['BranchbookApp', 'BranchbookData', 'BranchbookRelease']],
    ]);
  });

  test('refuse an environment outside the registry\'s Region, which no deploy policy covers', () => {
    const elsewhere: WorkloadConfigs = {
      ...workloads,
      environments: { ...workloads.environments, staging: { ...workloads.environments.staging, region: 'eu-west-1' } },
    };
    expect(() => deployTargets(config, elsewhere)).toThrow(/staging runs in eu-west-1, but the image registry/);
  });
});

describe('AccountStack', () => {
  const template = synth();

  test('trusts GitHub Actions as an OpenID Connect provider for AWS STS', () => {
    template.hasResourceProperties('AWS::IAM::OIDCProvider', {
      Url: 'https://token.actions.githubusercontent.com',
      ClientIdList: ['sts.amazonaws.com'],
    });
  });

  test('creates exactly one deploy role per environment and no other role', () => {
    const names = Object.values(template.findResources('AWS::IAM::Role') as Record<string, RoleResource>).map(
      (role) => role.Properties.RoleName,
    );
    expect(names.sort()).toEqual(['branchbook-github-deploy-production', 'branchbook-github-deploy-staging']);
  });

  test.each(ENVIRONMENTS)(
    'lets only this repository, by its immutable IDs, in the %s GitHub environment, assume %s for at most an hour',
    (environment, roleName) => {
      template.hasResourceProperties('AWS::IAM::Role', {
        RoleName: roleName,
        MaxSessionDuration: 3600,
        AssumeRolePolicyDocument: {
          Statement: [
            Match.objectLike({
              Action: 'sts:AssumeRoleWithWebIdentity',
              Condition: {
                StringEquals: {
                  'token.actions.githubusercontent.com:aud': 'sts.amazonaws.com',
                  [SUBJECT_KEY]: `repo:${OWNER}:environment:${environment}`,
                },
              },
            }),
          ],
        },
      });
    },
  );

  test.each(ENVIRONMENTS)(
    'the %s role trusts neither the other environment nor any branch, tag or wildcard subject',
    (_environment, roleName, _prefix, other) => {
      const { trust } = deployRole(template, roleName);
      expect(trust).toHaveLength(1);
      const condition = trust[0].Condition ?? {};
      expect(Object.keys(condition)).toEqual(['StringEquals']);
      const subject = JSON.stringify(condition.StringEquals[SUBJECT_KEY]);
      expect(subject).not.toContain(`:environment:${other}`);
      expect(subject).not.toContain(':ref:');
      expect(subject).not.toContain('*');
      expect(JSON.stringify(trust)).not.toContain('aws-demo');
    },
  );

  test.each(ENVIRONMENTS)(
    'the %s role may assume only its own bootstrap\'s roles in the workload Region, never cfn-exec, another bootstrap or Region',
    (_environment, roleName, _prefix, other, qualifier) => {
      const resources = statement(deployRole(template, roleName).statements, 'AssumeOwnCdkBootstrapRoles');
      expect(resources.Action).toBe('sts:AssumeRole');
      const arns = (resources.Resource as unknown[]).map((arn) => JSON.stringify(arn));
      expect(arns).toHaveLength(4);
      for (const kind of ['deploy', 'file-publishing', 'image-publishing', 'lookup']) {
        expect(arns.some((arn) => arn.includes(`:role/cdk-${qualifier}-${kind}-role-111111111111-af-south-1"`))).toBe(true);
      }
      const otherQualifier = config.deploy.environments[other].cdkBootstrapQualifier;
      expect(arns.join()).not.toContain(`cdk-${otherQualifier}-`);
      expect(arns.join()).not.toContain('cfn-exec');
      expect(arns.join()).not.toContain('us-east-1');
      expect(arns.join()).not.toContain('*');
    },
  );

  test.each(ENVIRONMENTS)(
    'keeps the %s bootstrap\'s deploy role off the other environment\'s stacks and the administrator\'s',
    (environment, _roleName, _prefix, other, qualifier) => {
      const policies = Object.values(template.findResources('AWS::IAM::ManagedPolicy') as Record<string, ManagedPolicyResource>);
      const scope = policies.find((policy) => policy.Properties.ManagedPolicyName === `branchbook-cdk-deploy-scope-${environment}`);
      expect(scope?.Properties.Roles).toEqual([`cdk-${qualifier}-deploy-role-111111111111-af-south-1`]);
      const [deny] = scope?.Properties.PolicyDocument.Statement ?? [];
      expect(deny).toMatchObject({ Effect: 'Deny', Action: 'cloudformation:*' });
      const denied = (deny.Resource as string[]).map((arn) => /:stack\/([^/]+)\/\*$/.exec(arn)?.[1]);
      const own = deployTargets(config, workloads).find((target) => target.name === environment)?.stackNames ?? [];
      const others = deployTargets(config, workloads).find((target) => target.name === other)?.stackNames ?? [];
      expect(denied.sort()).toEqual([...others, 'BranchbookAccount', 'BranchbookGuardrails'].sort());
      for (const stack of own) {
        expect(denied).not.toContain(stack);
      }
      expect(JSON.stringify(deny.Resource)).toContain(':cloudformation:*:111111111111:stack/');
    },
  );

  test.each(ENVIRONMENTS)(
    'the %s role reads only its own release and release-image parameters',
    (_environment, roleName, prefix) => {
      const read = statement(deployRole(template, roleName).statements, 'ReadOwnReleaseRecords');
      expect(read.Action).toBe('ssm:GetParameter');
      const arns = JSON.stringify(read.Resource);
      expect(read.Resource).toHaveLength(2);
      expect(arns).toContain(`:ssm:af-south-1:111111111111:parameter/${prefix}/release"`);
      expect(arns).toContain(`:ssm:af-south-1:111111111111:parameter/${prefix}/release-image"`);
      expect(arns).not.toContain('*');
    },
  );

  test.each(ENVIRONMENTS)(
    'the %s role may log in to ECR anywhere but push and pull only the branchbook repository in the workload Region',
    (_environment, roleName) => {
      const { statements } = deployRole(template, roleName);
      expect(statement(statements, 'LogInToEcr')).toMatchObject({ Action: 'ecr:GetAuthorizationToken', Resource: '*' });
      const repository = statement(statements, 'PushAndPullTheImageRepository');
      expect(repository.Action).toEqual(
        expect.arrayContaining(['ecr:PutImage', 'ecr:InitiateLayerUpload', 'ecr:BatchGetImage', 'ecr:GetDownloadUrlForLayer']),
      );
      expect(JSON.stringify(repository.Resource)).toContain(':ecr:af-south-1:111111111111:repository/branchbook"');
      // The authorization token is the only action that may name every resource.
      const wildcards = statements.filter((candidate) => JSON.stringify(candidate.Resource) === '"*"');
      expect(wildcards.map((candidate) => candidate.Sid)).toEqual(['LogInToEcr']);
      expect(JSON.stringify(statements.map((candidate) => candidate.Action))).not.toMatch(/"[a-z0-9-]+:\*"|"\*"/);
    },
  );

  test('only the staging role may read the staging synthetic-traffic token, and only that secret', () => {
    const staging = statement(deployRole(template, 'branchbook-github-deploy-staging').statements, 'ReadSyntheticTrafficToken');
    expect(staging.Action).toBe('secretsmanager:GetSecretValue');
    expect(JSON.stringify(staging.Resource)).toContain(
      ':secretsmanager:af-south-1:111111111111:secret:branchbook-staging/synthetic-traffic-token-??????"',
    );
    const production = deployRole(template, 'branchbook-github-deploy-production').statements;
    expect(JSON.stringify(production)).not.toContain('secretsmanager');
  });

  test('outputs each deploy role ARN for its GitHub environment secret', () => {
    template.hasOutput('StagingDeployRoleArn', { Description: Match.stringLikeRegexp('staging GitHub environment') });
    template.hasOutput('ProductionDeployRoleArn', { Description: Match.stringLikeRegexp('production GitHub environment') });
  });

  test('keeps the deploy roles outside the application permissions boundary, even when the app applies it everywhere', () => {
    const bounded = synth({ '@aws-cdk/core:permissionsBoundary': { name: 'branchbook-permissions-boundary' } });
    const roles = Object.values(bounded.findResources('AWS::IAM::Role') as Record<string, RoleResource>);
    expect(roles).toHaveLength(2);
    for (const role of roles) {
      expect(role.Properties.PermissionsBoundary).toBeUndefined();
    }
  });

  test('e-mails budget alerts at 50, 80 and 100 percent of a 450 USD monthly limit', () => {
    template.hasResourceProperties('AWS::Budgets::Budget', {
      Budget: Match.objectLike({ BudgetName: 'branchbook-monthly', BudgetLimit: { Amount: 450, Unit: 'USD' }, TimeUnit: 'MONTHLY' }),
      NotificationsWithSubscribers: [50, 80, 100].map((threshold) =>
        Match.objectLike({ Notification: Match.objectLike({ Threshold: threshold }) }),
      ),
    });
  });

  test('refuses to synthesise without an alert address', () => {
    expect(
      () =>
        new AccountStack(new App(), 'Account', {
          env: { account: '111111111111', region: 'us-east-1' },
          config,
          workloads,
          alertEmail: '',
        }),
    ).toThrow(/BRANCHBOOK_ALERT_EMAIL/);
  });
});
