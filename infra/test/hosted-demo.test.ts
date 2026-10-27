/**
 * Unit tests for the hosted demo, {@link DataStack} and {@link AppStack} (ADR-0006): they prove the properties a
 * reviewer relies on, on the synthesised CloudFormation, before anything reaches AWS.
 */
import { App } from 'aws-cdk-lib/core';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { AppStack, AppStackProps, BOOKING_RATE_LIMIT, SMS_RECIPIENT_DAILY_CAP } from '../lib/app-stack';
import { MMS_COUNTRIES, SMS_COUNTRIES } from '../lib/sms-countries';
import { DataStack } from '../lib/data-stack';

const env = { account: '111111111111', region: 'af-south-1' };

function synth(overrides: Partial<AppStackProps> = {}): { data: Template; app: Template } {
  const app = new App();
  const data = new DataStack(app, 'Data', { env, availabilityZones: ['af-south-1a', 'af-south-1b'] });
  const appStack = new AppStack(app, 'App', {
    env,
    vpc: data.vpc,
    database: data.database,
    domainName: 'branchbook.link',
    hostedZoneId: 'Z0000000PLACEHOLDER',
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

  test('gives the app the public address the SMS links to, over TLS to the database, with the inbox off and ended bookings deleted', () => {
    app.hasResourceProperties('AWS::ECS::TaskDefinition', {
      ContainerDefinitions: [
        Match.objectLike({
          // arrayWith matches in order: the order the container declares them.
          Environment: Match.arrayWith([
            { Name: 'DB_URL', Value: Match.objectLike({ 'Fn::Join': Match.anyValue() }) },
            { Name: 'PUBLIC_BASE_URL', Value: 'https://branchbook.link' },
            { Name: 'BRANCHBOOK_INBOX_ENABLED', Value: 'false' },
            { Name: 'BRANCHBOOK_RETENTION_ENABLED', Value: 'true' },
            { Name: 'BRANCHBOOK_SMS_GATEWAY', Value: 'aws' },
            { Name: 'BRANCHBOOK_SMS_AWS_REGION', Value: 'af-south-1' },
            { Name: 'BRANCHBOOK_SMS_AWS_PROTECT_CONFIGURATION_ID', Value: Match.objectLike({ 'Fn::GetAtt': Match.anyValue() }) },
            { Name: 'BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP', Value: String(SMS_RECIPIENT_DAILY_CAP) },
            { Name: 'BRANCHBOOK_SMS_AWS_DRY_RUN', Value: 'false' },
            { Name: 'MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE', Value: 'health,info' },
          ]),
          Secrets: Match.arrayWith([Match.objectLike({ Name: 'DB_PASSWORD' })]),
        }),
      ],
    });
  });

  test('texts South Africa only: every other SMS country, and every voice and MMS destination, is blocked', () => {
    const protections = Object.values(app.findResources('AWS::SMSVOICE::ProtectConfiguration')) as {
      Properties: { CountryRuleSet: Record<'SMS' | 'VOICE' | 'MMS', { CountryCode: string; ProtectStatus: string }[]> };
    }[];
    expect(protections).toHaveLength(1);
    const rules = protections[0].Properties.CountryRuleSet;
    expect(rules.SMS).toHaveLength(SMS_COUNTRIES.length);
    expect(rules.SMS.filter((r) => r.ProtectStatus === 'ALLOW').map((r) => r.CountryCode)).toEqual(['ZA']);
    expect(rules.SMS.filter((r) => r.ProtectStatus === 'BLOCK')).toHaveLength(SMS_COUNTRIES.length - 1);
    expect(rules.VOICE.every((r) => r.ProtectStatus === 'BLOCK')).toBe(true);
    expect(rules.MMS.map((r) => r.CountryCode).sort()).toEqual([...MMS_COUNTRIES].sort());
    expect(rules.MMS.every((r) => r.ProtectStatus === 'BLOCK')).toBe(true);
  });

  test('lets the task send SMS through its protect configuration and nothing else', () => {
    app.hasResourceProperties('AWS::IAM::Policy', {
      PolicyDocument: {
        Statement: Match.arrayWith([
          Match.objectLike({
            Action: 'sms-voice:SendTextMessage',
            Effect: 'Allow',
            Resource: { 'Fn::GetAtt': [Match.stringLikeRegexp('^SmsProtection'), 'Arn'] },
          }),
        ]),
      },
    });
  });

  test('can deploy a task that only asks AWS to check each send', () => {
    const dryRun = synth({ smsDryRun: true }).app;
    dryRun.hasResourceProperties('AWS::ECS::TaskDefinition', {
      ContainerDefinitions: [
        Match.objectLike({ Environment: Match.arrayWith([{ Name: 'BRANCHBOOK_SMS_AWS_DRY_RUN', Value: 'true' }]) }),
      ],
    });
  });

  test('keeps application logs for 7 days', () => {
    app.hasResourceProperties('AWS::Logs::LogGroup', { LogGroupName: '/branchbook/app', RetentionInDays: 7 });
  });

  test('serves HTTPS with a TLS 1.3 policy and redirects plain HTTP to it', () => {
    app.hasResourceProperties('AWS::ElasticLoadBalancingV2::Listener', {
      Port: 443,
      Protocol: 'HTTPS',
      SslPolicy: Match.stringLikeRegexp('TLS13'),
    });
    app.hasResourceProperties('AWS::ElasticLoadBalancingV2::Listener', {
      Port: 80,
      DefaultActions: [Match.objectLike({ Type: 'redirect', RedirectConfig: Match.objectLike({ Protocol: 'HTTPS' }) })],
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

  test('puts WAF in front, with no sign-in: /actuator blocked, the booking rate limit and AWS managed rules', () => {
    // Decoded and lowercased first, so /%61ctuator and /ACTUATOR are blocked too.
    const decoded = [
      { Priority: 0, Type: 'URL_DECODE' },
      { Priority: 1, Type: 'LOWERCASE' },
    ];
    app.hasResourceProperties('AWS::WAFv2::WebACL', {
      Scope: 'REGIONAL',
      Rules: [
        Match.objectLike({
          Name: 'block-actuator',
          Action: { Block: {} },
          Statement: { ByteMatchStatement: Match.objectLike({ TextTransformations: decoded }) },
        }),
        Match.objectLike({
          Name: 'booking-rate-limit',
          Statement: { RateBasedStatement: Match.objectLike({ Limit: BOOKING_RATE_LIMIT, EvaluationWindowSec: 300 }) },
        }),
        Match.objectLike({ Name: 'AWSManagedRulesAmazonIpReputationList' }),
        Match.objectLike({ Name: 'AWSManagedRulesKnownBadInputsRuleSet' }),
        Match.objectLike({ Name: 'AWSManagedRulesCommonRuleSet' }),
      ],
    });
    app.resourceCountIs('AWS::WAFv2::WebACLAssociation', 1);
  });

  test('raises three alarms', () => {
    app.resourceCountIs('AWS::CloudWatch::Alarm', 3);
  });

  test('refuses a domain too long for the SMS link (BR-18)', () => {
    expect(() => synth({ domainName: 'a-much-too-long-name.link' })).toThrow(/at most 20 characters/);
  });
});
