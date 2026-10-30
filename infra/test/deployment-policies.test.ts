/**
 * Checks the deployment policies against the stacks they must deploy, built exactly as `cdk synth` builds them: the
 * CloudFormation execution policy must allow every service those stacks' resources belong to, or a deployment under
 * the hardened bootstrap fails half-way.
 */
import { Stack } from 'aws-cdk-lib/core';
import { Template } from 'aws-cdk-lib/assertions';
import { buildApp } from '../lib/app';
import { ENVIRONMENT_NAMES, EnvironmentName } from '../lib/config';
import { newApp } from './cdk-app';

const DIGEST = `sha256:${'0'.repeat(64)}`;
const ACCOUNT = '111111111111';

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
function build(name: EnvironmentName): { appStacks: Stack[]; execServices: string[] } {
  const stacks = buildApp(newApp(), {
    BRANCHBOOK_ENV: name,
    BRANCHBOOK_IMAGE_DIGEST: DIGEST,
    BRANCHBOOK_GUARDRAILS: 'true',
    CDK_DEFAULT_ACCOUNT: ACCOUNT,
  });
  if (stacks.environment === undefined || stacks.guardrails === undefined) {
    throw new Error('the app did not build the environment and the guardrails');
  }
  const { data, app, release } = stacks.environment;
  const policies = Template.fromStack(stacks.guardrails).findResources('AWS::IAM::ManagedPolicy') as Record<
    string,
    { Properties: { ManagedPolicyName: string; PolicyDocument: { Statement: Statement[] } } }
  >;
  const exec = Object.values(policies).find((policy) => policy.Properties.ManagedPolicyName === 'branchbook-cdk-cfn-exec');
  const services = exec?.Properties.PolicyDocument.Statement.find((statement) => statement.Sid === 'DeployTheApplicationServices');
  if (services === undefined) {
    throw new Error('no execution policy statement DeployTheApplicationServices');
  }
  return {
    appStacks: [stacks.registry, data, app, release],
    execServices: ([] as string[]).concat(services.Action),
  };
}

describe.each(ENVIRONMENT_NAMES)('the %s deployment', (name) => {
  const { appStacks, execServices } = build(name);

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
});
