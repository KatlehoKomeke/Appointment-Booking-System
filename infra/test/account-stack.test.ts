/**
 * Unit tests for {@link AccountStack} and the account configuration: they prove the security properties of the
 * GitHub-to-AWS connection on the synthesised CloudFormation template, so a regression fails CI before anything
 * reaches AWS.
 */
import { App } from 'aws-cdk-lib/core';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { AccountConfig, AccountStack, loadAccountConfig, parseAccountConfig } from '../lib/account-stack';

const config = loadAccountConfig();
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

function synth(overrides: Partial<AccountConfig> = {}, context: Record<string, unknown> = {}): Template {
  const app = new App({ context });
  return Template.fromStack(
    new AccountStack(app, 'Account', {
      env: { account: '111111111111', region: 'us-east-1' },
      config: { ...config, ...overrides },
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

const ENVIRONMENTS = [
  ['staging', 'branchbook-github-deploy-staging', 'branchbook-staging', 'production'],
  ['production', 'branchbook-github-deploy-production', 'branchbook', 'staging'],
] as const;

describe('the account configuration', () => {
  test('the committed file is valid, sets a 350 USD budget and defines a staging and a production environment', () => {
    expect(config.budget.monthlyUsd).toBe(350);
    expect(config.budget.alertThresholdsPercent).toEqual([50, 80, 100]);
    expect(Object.keys(config.deploy.environments).sort()).toEqual(['production', 'staging']);
    expect(config.deploy.environments.production.syntheticTrafficSecretName).toBeUndefined();
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
    ['two environments trusting the same GitHub environment', mutate((c) => (c.deploy.environments.staging.githubEnvironment = 'production')), /must differ/],
    ['a CAPTCHA bypass secret for production', mutate((c) => (c.deploy.environments.production.syntheticTrafficSecretName = 'branchbook/token')), /production has no CAPTCHA bypass/],
    ['a zero budget', mutate((c) => (c.budget.monthlyUsd = 0)), /budget.monthlyUsd.*whole number/],
    ['a misspelt workload Region', mutate((c) => (c.workloadRegion = 'cape-town')), /workloadRegion.*AWS Region/],
    ['a session longer than IAM allows', mutate((c) => (c.deploy.maxSessionMinutes = 721)), /maxSessionMinutes/],
    ['an unknown GuardDuty plan', mutate((c) => ((c.guardrails.guardDuty.features as Record<string, string>).EKS_RUNTIME_MONITORING = 'ENABLED')), /features.EKS_RUNTIME_MONITORING.*one of/],
    ['a missing section', mutate((c) => delete (c as Partial<typeof c>).guardrails), /"guardrails" must be an object/],
  ])('refuses %s', (_label, raw, message) => {
    expect(() => parseAccountConfig(raw)).toThrow(message);
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
    'the %s role may assume only the workload Region bootstrap roles, never cfn-exec or another Region',
    (_environment, roleName) => {
      const resources = statement(deployRole(template, roleName).statements, 'AssumeWorkloadRegionCdkBootstrapRoles');
      expect(resources.Action).toBe('sts:AssumeRole');
      const arns = (resources.Resource as unknown[]).map((arn) => JSON.stringify(arn));
      expect(arns).toHaveLength(4);
      for (const kind of ['deploy', 'file-publishing', 'image-publishing', 'lookup']) {
        expect(arns.some((arn) => arn.includes(`:role/cdk-hnb659fds-${kind}-role-111111111111-af-south-1`))).toBe(true);
      }
      expect(arns.join()).not.toContain('cfn-exec');
      expect(arns.join()).not.toContain('us-east-1');
      expect(arns.join()).not.toContain('*');
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
    const bounded = synth({}, { '@aws-cdk/core:permissionsBoundary': { name: 'branchbook-permissions-boundary' } });
    const roles = Object.values(bounded.findResources('AWS::IAM::Role') as Record<string, RoleResource>);
    expect(roles).toHaveLength(2);
    for (const role of roles) {
      expect(role.Properties.PermissionsBoundary).toBeUndefined();
    }
  });

  test('e-mails budget alerts at 50, 80 and 100 percent of a 350 USD monthly limit', () => {
    template.hasResourceProperties('AWS::Budgets::Budget', {
      Budget: Match.objectLike({ BudgetName: 'branchbook-monthly', BudgetLimit: { Amount: 350, Unit: 'USD' }, TimeUnit: 'MONTHLY' }),
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
          alertEmail: '',
        }),
    ).toThrow(/BRANCHBOOK_ALERT_EMAIL/);
  });
});
