/**
 * Unit tests for the hosted demo, {@link DataStack} and {@link AppStack} (ADR-0006): they prove the properties a
 * reviewer relies on, on the synthesised CloudFormation, before anything reaches AWS.
 */
import { App } from 'aws-cdk-lib/core';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { AppStack, AppStackProps, BOOKING_RATE_LIMIT, DEMO_GATE_USER } from '../lib/app-stack';
import { DataStack } from '../lib/data-stack';

const env = { account: '111111111111', region: 'af-south-1' };
const password = 'not-a-real-password';

function synth(overrides: Partial<AppStackProps> = {}): { data: Template; app: Template } {
  const app = new App();
  const data = new DataStack(app, 'Data', { env, availabilityZones: ['af-south-1a', 'af-south-1b'] });
  const appStack = new AppStack(app, 'App', {
    env,
    vpc: data.vpc,
    database: data.database,
    domainName: 'branchbook.link',
    hostedZoneId: 'Z0000000PLACEHOLDER',
    demoGatePassword: password,
    ...overrides,
  });
  return { data: Template.fromStack(data), app: Template.fromStack(appStack) };
}

const { data, app } = synth();

describe('DataStack', () => {
  test('runs PostgreSQL 17 on db.t4g.micro, encrypted, private, Single-AZ, with 7-day backups', () => {
    data.hasResourceProperties('AWS::RDS::DBInstance', {
      Engine: 'postgres',
      EngineVersion: Match.stringLikeRegexp('^17\\.'),
      DBInstanceClass: 'db.t4g.micro',
      StorageEncrypted: true,
      PubliclyAccessible: false,
      MultiAZ: false,
      BackupRetentionPeriod: 7,
    });
  });

  test('refuses any database connection without TLS', () => {
    data.hasResourceProperties('AWS::RDS::DBParameterGroup', { Parameters: { 'rds.force_ssl': '1' } });
  });

  test('generates the database credentials into Secrets Manager, never into the template', () => {
    data.hasResourceProperties('AWS::SecretsManager::Secret', {
      Name: 'branchbook/database',
      GenerateSecretString: Match.objectLike({ GenerateStringKey: 'password' }),
    });
  });

  test('exports no PostgreSQL log, whose constraint errors can quote personal data', () => {
    data.hasResourceProperties('AWS::RDS::DBInstance', { EnableCloudwatchLogsExports: Match.absent() });
  });

  test('has no NAT gateway, and puts the database in subnets with no internet route', () => {
    data.resourceCountIs('AWS::EC2::NatGateway', 0);
    data.resourceCountIs('AWS::EC2::Subnet', 4);
  });
});

describe('AppStack', () => {
  test('runs one ARM64 Fargate task of 0.5 vCPU and 1 GB', () => {
    app.hasResourceProperties('AWS::ECS::TaskDefinition', {
      Cpu: '512',
      Memory: '1024',
      RuntimePlatform: { CpuArchitecture: 'ARM64', OperatingSystemFamily: 'LINUX' },
    });
    app.hasResourceProperties('AWS::ECS::Service', {
      DesiredCount: 1,
      DeploymentConfiguration: Match.objectLike({ DeploymentCircuitBreaker: { Enable: true, Rollback: true } }),
    });
  });

  test('gives the app the public address the SMS links to, over TLS to the database, with the inbox and the 48-hour deletion on', () => {
    app.hasResourceProperties('AWS::ECS::TaskDefinition', {
      ContainerDefinitions: [
        Match.objectLike({
          // arrayWith matches in order: the order the container declares them.
          Environment: Match.arrayWith([
            { Name: 'DB_URL', Value: Match.objectLike({ 'Fn::Join': Match.anyValue() }) },
            { Name: 'PUBLIC_BASE_URL', Value: 'https://branchbook.link' },
            { Name: 'BRANCHBOOK_INBOX_ENABLED', Value: 'true' },
            { Name: 'BRANCHBOOK_RETENTION_ENABLED', Value: 'true' },
            { Name: 'BRANCHBOOK_BOOKING_DEMO_NUMBERS_ONLY', Value: 'true' },
          ]),
          Secrets: Match.arrayWith([Match.objectLike({ Name: 'DB_PASSWORD' })]),
        }),
      ],
    });
  });

  test('keeps application logs for 7 days', () => {
    app.hasResourceProperties('AWS::Logs::LogGroup', { LogGroupName: '/branchbook/app', RetentionInDays: 7 });
  });

  test('serves HTTPS only, with a TLS 1.3 policy, so the gate never asks for the password over plain HTTP', () => {
    app.resourceCountIs('AWS::ElasticLoadBalancingV2::Listener', 1);
    app.hasResourceProperties('AWS::ElasticLoadBalancingV2::Listener', {
      Port: 443,
      Protocol: 'HTTPS',
      SslPolicy: Match.stringLikeRegexp('TLS13'),
    });
    app.hasResourceProperties('AWS::EC2::SecurityGroup', {
      SecurityGroupIngress: [Match.objectLike({ FromPort: 443, ToPort: 443, CidrIp: '0.0.0.0/0' })],
    });
    app.hasResourceProperties('AWS::ElasticLoadBalancingV2::TargetGroup', { HealthCheckPath: '/readyz' });
  });

  test('opens PostgreSQL only to the app task, and the task only to the load balancer', () => {
    app.hasResourceProperties('AWS::EC2::SecurityGroupIngress', {
      IpProtocol: 'tcp',
      FromPort: 5432,
      ToPort: 5432,
      SourceSecurityGroupId: Match.anyValue(),
    });
    app.hasResourceProperties('AWS::EC2::SecurityGroupIngress', {
      FromPort: 8080,
      SourceSecurityGroupId: Match.anyValue(),
    });
  });

  test('puts WAF in front: /actuator blocked, the demo gate, the booking rate limit and AWS managed rules', () => {
    const expected = `Basic ${Buffer.from(`${DEMO_GATE_USER}:${password}`).toString('base64')}`;
    app.hasResourceProperties('AWS::WAFv2::WebACL', {
      Scope: 'REGIONAL',
      Rules: Match.arrayWith([
        Match.objectLike({ Name: 'block-actuator', Action: { Block: {} } }),
        Match.objectLike({
          Name: 'demo-gate',
          Action: { Block: { CustomResponse: Match.objectLike({ ResponseCode: 401 }) } },
          Statement: {
            NotStatement: { Statement: { ByteMatchStatement: Match.objectLike({ SearchString: expected }) } },
          },
        }),
        Match.objectLike({
          Name: 'booking-rate-limit',
          Statement: { RateBasedStatement: Match.objectLike({ Limit: BOOKING_RATE_LIMIT, EvaluationWindowSec: 300 }) },
        }),
        Match.objectLike({ Name: 'AWSManagedRulesCommonRuleSet' }),
      ]),
    });
    app.resourceCountIs('AWS::WAFv2::WebACLAssociation', 1);
  });

  test('raises three alarms', () => {
    app.resourceCountIs('AWS::CloudWatch::Alarm', 3);
  });

  test('refuses a domain too long for the SMS link (BR-18)', () => {
    expect(() => synth({ domainName: 'a-much-too-long-name.link' })).toThrow(/at most 20 characters/);
  });

  test('refuses a short demo gate password', () => {
    expect(() => synth({ demoGatePassword: 'short' })).toThrow(/at least 12 characters/);
  });
});
