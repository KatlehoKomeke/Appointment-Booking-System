/**
 * Checks the deployment policies against the stacks they must deploy, built exactly as `cdk synth` builds them: the
 * CloudFormation execution policy must allow every service those stacks' resources belong to, or a deployment under
 * the hardened bootstrap fails half-way, and every role those stacks create must carry the permissions boundary, or
 * the execution policy refuses to create it.
 */
import { Stack } from 'aws-cdk-lib/core';
import { Template } from 'aws-cdk-lib/assertions';
import { buildApp } from '../lib/app';
import { loadAccountConfig } from '../lib/account-stack';
import { ENVIRONMENT_NAMES, EnvironmentName } from '../lib/config';
import { newApp } from './cdk-app';

const DIGEST = `sha256:${'0'.repeat(64)}`;
const ACCOUNT = '111111111111';
const accountConfig = loadAccountConfig();

/**
 * The IAM service prefix of each CloudFormation namespace (`AWS::<namespace>::<type>`) the application stacks may
 * use. A namespace missing here fails the test, so a new kind of resource is a decision, not an accident.
 */
const SERVICE_PREFIXES: Readonly<Record<string, string>> = {
  ApplicationAutoScaling: 'application-autoscaling',
  CertificateManager: 'acm',
  CloudWatch: 'cloudwatch',
  EC2: 'ec2',
  ECR: 'ecr',
  ECS: 'ecs',
  ElasticLoadBalancingV2: 'elasticloadbalancing',
  FIS: 'fis',
  KMS: 'kms',
  Lambda: 'lambda',
  Logs: 'logs',
  RDS: 'rds',
  Route53: 'route53',
  S3: 's3',
  SecretsManager: 'secretsmanager',
  SMSVOICE: 'sms-voice',
  SNS: 'sns',
  SSM: 'ssm',
  StepFunctions: 'states',
  Synthetics: 'synthetics',
  WAFv2: 'wafv2',
};

/** The IAM resources the execution policy handles with its own narrow statements, rather than `iam:*`. */
const IAM_TYPES = ['AWS::IAM::Role', 'AWS::IAM::Policy', 'AWS::IAM::ManagedPolicy'];

/** The boundary infra/cdk.json attaches to every role the application stacks create. */
const BOUNDARY_ARN = `arn:aws:iam::${ACCOUNT}:policy/branchbook-permissions-boundary`;

/** The parts of a synthesised `AWS::IAM::Role` the assertions read. */
interface RoleResource {
  Properties: { PermissionsBoundary?: unknown };
}

/** The parts of a synthesised IAM statement the assertions read. */
interface Statement {
  Sid?: string;
  Action: string | string[];
}

/**
 * Builds the app for one environment, with the guardrails, the way bin/branchbook.ts does.
 *
 * @param name - the environment
 */
function build(name: EnvironmentName): { appStacks: Stack[]; adminStacks: Stack[]; execServices: string[] } {
  const stacks = buildApp(newApp(), {
    BRANCHBOOK_ENV: name,
    BRANCHBOOK_IMAGE_DIGEST: DIGEST,
    BRANCHBOOK_GUARDRAILS: 'true',
    BRANCHBOOK_ALERT_EMAIL: 'alerts@example.com',
    CDK_DEFAULT_ACCOUNT: ACCOUNT,
  });
  if (stacks.environment === undefined || stacks.guardrails === undefined || stacks.account === undefined) {
    throw new Error('the app did not build the environment, the account stack and the guardrails');
  }
  const { data, app, release } = stacks.environment;
  const policies = Template.fromStack(stacks.guardrails).findResources('AWS::IAM::ManagedPolicy') as Record<
    string,
    { Properties: { ManagedPolicyName: string; PolicyDocument: { Statement: Statement[] } } }
  >;
  const exec = Object.values(policies).find((policy) => policy.Properties.ManagedPolicyName === `branchbook-cdk-cfn-exec-${name}`);
  const services = exec?.Properties.PolicyDocument.Statement.find((statement) => statement.Sid === 'DeployTheApplicationServices');
  if (services === undefined) {
    throw new Error('no execution policy statement DeployTheApplicationServices');
  }
  return {
    appStacks: [stacks.registry, data, app, release],
    adminStacks: [stacks.account, stacks.guardrails],
    execServices: ([] as string[]).concat(services.Action),
  };
}

describe.each(ENVIRONMENT_NAMES)('the %s deployment', (name) => {
  const { appStacks, adminStacks, execServices } = build(name);

  test('the execution policy allows the service of every resource type the stacks it deploys contain', () => {
    const types = new Set(appStacks.flatMap((stack) => Object.values(Template.fromStack(stack).toJSON().Resources as Record<string, { Type: string }>).map((resource) => resource.Type)));
    const problems: string[] = [];
    for (const type of [...types].sort()) {
      if (type === 'AWS::CDK::Metadata' || type.startsWith('Custom::') || IAM_TYPES.includes(type)) {
        continue;
      }
      const namespace = /^AWS::([^:]+)::/.exec(type)?.[1];
      const prefix = namespace === undefined ? undefined : SERVICE_PREFIXES[namespace];
      if (prefix === undefined) {
        problems.push(`${type}: no IAM service prefix is mapped for its namespace`);
      } else if (!execServices.includes(`${prefix}:*`)) {
        problems.push(`${type}: the execution policy does not allow ${prefix}:*`);
      }
    }
    expect(problems).toEqual([]);
  });

  test('every stack it deploys, the registry included where it owns it, goes through its own bootstrap', () => {
    const own = accountConfig.deploy.environments[name].cdkBootstrapQualifier;
    const registryQualifier = accountConfig.deploy.environments[accountConfig.deploy.registryEnvironment].cdkBootstrapQualifier;
    for (const stack of appStacks) {
      const expected = stack.stackName === 'BranchbookRegistry' ? registryQualifier : own;
      const parameters = Template.fromStack(stack).toJSON().Parameters as Record<string, { Default?: string }>;
      expect([stack.stackName, parameters.BootstrapVersion.Default]).toEqual([stack.stackName, `/cdk-bootstrap/${expected}/version`]);
    }
  });

  test('every role the stacks it deploys create carries the permissions boundary, custom-resource roles included', () => {
    const roles = appStacks.flatMap((stack) =>
      Object.entries(Template.fromStack(stack).findResources('AWS::IAM::Role') as Record<string, RoleResource>),
    );
    const unbounded = roles.filter(([, role]) => role.Properties.PermissionsBoundary !== BOUNDARY_ARN).map(([id]) => id);
    expect(unbounded).toEqual([]);
    // The roles CDK creates for its own custom resources are among them, not just the ones the stacks declare.
    const ids = roles.map(([id]) => id);
    expect(ids).toEqual(expect.arrayContaining([expect.stringMatching(/^CustomVpcRestrictDefaultSGCustomResourceProviderRole/)]));
    expect(ids).toEqual(expect.arrayContaining([expect.stringMatching(/^AWS679f53fac002430cb0da5b7982bd2287ServiceRole/)]));
    expect(ids).toEqual(expect.arrayContaining([expect.stringMatching(/^MigrationProviderwaiterstatemachineRole/)]));
  });

  test('the administrator stacks keep their roles outside the boundary: the deploy roles must assume the bootstrap roles', () => {
    for (const stack of adminStacks) {
      const roles = Object.values(Template.fromStack(stack).findResources('AWS::IAM::Role') as Record<string, RoleResource>);
      for (const role of roles) {
        expect(role.Properties.PermissionsBoundary).toBeUndefined();
      }
    }
  });
});
