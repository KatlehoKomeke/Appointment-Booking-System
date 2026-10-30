/**
 * Unit tests for both deployed environments, built from infra/config exactly as `cdk synth` builds them: they prove
 * the properties production relies on, and staging's differences, on the synthesised CloudFormation before anything
 * reaches AWS.
 */
import { Match, Template } from 'aws-cdk-lib/assertions';
import { EnvironmentConfig, EnvironmentName, loadEnvironmentConfig, loadRegistryConfig } from '../lib/config';
import { addEnvironment } from '../lib/environment';
import { MMS_COUNTRIES, SMS_COUNTRIES } from '../lib/sms-countries';
import { newApp } from './cdk-app';

const DIGEST = `sha256:${'5e'.repeat(32)}`;

interface Built {
  readonly config: EnvironmentConfig;
  readonly data: Template;
  readonly app: Template;
}

function build(name: EnvironmentName): Built {
  const app = newApp();
  const config = loadEnvironmentConfig(name);
  const stacks = addEnvironment(app, config, loadRegistryConfig(), {
    account: '111111111111',
    imageDigest: DIGEST,
    version: 'local',
  });
  return { config, data: Template.fromStack(stacks.data), app: Template.fromStack(stacks.app) };
}

const production = build('production');
const staging = build('staging');

/** The parts of a synthesised container definition the assertions read. */
interface Container {
  Name: string;
  Environment: { Name: string; Value: unknown }[];
  Secrets?: { Name: string; ValueFrom: unknown }[];
  PortMappings?: { ContainerPort: number }[];
}

/**
 * The named container, from whichever task definition holds it.
 *
 * @param template - the app stack
 * @param name - the container's name
 */
function container(template: Template, name: 'App' | 'Migrate'): Container {
  const definitions = Object.values(template.findResources('AWS::ECS::TaskDefinition')) as {
    Properties: { ContainerDefinitions: Container[] };
  }[];
  const found = definitions.flatMap((d) => d.Properties.ContainerDefinitions).find((c) => c.Name === name);
  if (found === undefined) {
    throw new Error(`no container ${name}`);
  }
  return found;
}

function environment(c: Container): Record<string, unknown> {
  return Object.fromEntries(c.Environment.map((e) => [e.Name, e.Value]));
}

/**
 * Every statement of every IAM policy in the template.
 *
 * @param template - the app stack
 */
function statements(template: Template): { Action: unknown; Resource: unknown }[] {
  const policies = Object.values(template.findResources('AWS::IAM::Policy')) as {
    Properties: { PolicyDocument: { Statement: { Action: unknown; Resource: unknown }[] } };
  }[];
  return policies.flatMap((p) => p.Properties.PolicyDocument.Statement);
}

function logicalIdOf(template: Template, type: string): string {
  const ids = Object.keys(template.findResources(type));
  expect(ids).toHaveLength(1);
  return ids[0];
}

describe.each([
  ['production', production],
  ['staging', staging],
] as const)('%s', (_name, built) => {
  const { app, config } = built;

  test('migrates the schema with a one-off task before the service is updated', () => {
    const migration = logicalIdOf(app, 'Custom::SchemaMigration');
    app.hasResource('AWS::ECS::Service', { DependsOn: Match.arrayWith([migration]) });
    // A new digest changes the task definition and the digest itself, so every new image migrates again.
    app.hasResourceProperties('Custom::SchemaMigration', {
      ImageDigest: DIGEST,
      ContainerName: 'Migrate',
      TaskDefinition: { Ref: Match.stringLikeRegexp('^MigrationTask') },
    });
  });

  test('gives only the migration task the master secret, with a plain verify-full URL, and no web server', () => {
    const migrate = container(app, 'Migrate');
    expect(migrate.Secrets?.map((s) => s.Name).sort()).toEqual(['DB_PASSWORD', 'DB_USER']);
    const env = environment(migrate);
    expect(env).toMatchObject({
      BRANCHBOOK_MIGRATE_ONLY: 'true',
      SPRING_MAIN_WEB_APPLICATION_TYPE: 'none',
      BRANCHBOOK_DB_APP_ROLE: 'branchbook_app',
      SPRING_PROFILES_ACTIVE: 'prod',
    });
    expect(JSON.stringify(env.DB_URL)).toMatch(/jdbc:postgresql:\/\/.*sslmode=verify-full&sslrootcert=\/app\/certs\/rds-global-bundle\.pem/);
    expect(migrate.PortMappings ?? []).toHaveLength(0);
  });

  test('signs the service in to the database as branchbook_app with an IAM token, and gives it no password', () => {
    const svc = container(app, 'App');
    expect(svc.Secrets?.map((s) => s.Name)).toEqual(['BRANCHBOOK_SMS_RECIPIENT_CAP_KEY']);
    const env = environment(svc);
    expect(env).toMatchObject({
      DB_USER: 'branchbook_app',
      DB_PASSWORD: '',
      SPRING_DATASOURCE_DRIVER_CLASS_NAME: 'software.amazon.jdbc.Driver',
      SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE: String(config.service.dbPoolSize),
    });
    expect(JSON.stringify(env.DB_URL)).toMatch(
      /jdbc:aws-wrapper:postgresql:\/\/.*:5432\/branchbook\?wrapperPlugins=iam&sslmode=verify-full&sslrootcert=\/app\/certs\/rds-global-bundle\.pem/,
    );
    const connect = statements(app).filter((s) => s.Action === 'rds-db:connect');
    expect(connect).toHaveLength(1);
    expect(JSON.stringify(connect[0].Resource)).toMatch(/:dbuser:.*\/branchbook_app"/);
  });

  test('runs the prod profile, migrates nothing at start-up, and passes the CAPTCHA key and script to the SPA', () => {
    const env = environment(container(app, 'App'));
    expect(env).toMatchObject({
      SPRING_PROFILES_ACTIVE: 'prod',
      BRANCHBOOK_MIGRATE_ON_START: 'false',
      PUBLIC_BASE_URL: `https://${config.domain.name}`,
      BRANCHBOOK_INBOX_ENABLED: 'false',
      BRANCHBOOK_INBOX_REDACT_REFERENCES: 'true',
      BRANCHBOOK_RETENTION_ENABLED: 'true',
    });
    expect(JSON.stringify(env.BRANCHBOOK_CAPTCHA_SCRIPT_URL)).toMatch(/ApplicationIntegrationURL.*jsapi\.js/);
    expect(JSON.stringify(env.BRANCHBOOK_CAPTCHA_API_KEY)).toMatch(/"CaptchaKey[0-9A-F]+","APIKey"/);
  });

  test('creates the CAPTCHA API key for the environment\'s domain, and deletes it safely', () => {
    const keys = Object.values(app.findResources('Custom::WafCaptchaApiKey')) as { Properties: Record<string, string> }[];
    expect(keys).toHaveLength(1);
    const create = JSON.parse(keys[0].Properties.Create) as { action: string; parameters: unknown };
    const remove = JSON.parse(keys[0].Properties.Delete) as { action: string; ignoreErrorCodesMatching: string };
    expect(create).toMatchObject({ action: 'CreateAPIKey', parameters: { Scope: 'REGIONAL', TokenDomains: [config.domain.name] } });
    expect(keys[0].Properties.Update).toBe(keys[0].Properties.Create);
    expect(remove).toMatchObject({ action: 'DeleteAPIKey', ignoreErrorCodesMatching: 'WAFNonexistentItemException' });
  });

  test('runs a read-only root file system with no Linux capabilities, a /tmp volume and a /livez health check', () => {
    const definitions = Object.values(app.findResources('AWS::ECS::TaskDefinition')) as {
      Properties: { Volumes: { Name: string }[]; ContainerDefinitions: Record<string, unknown>[] };
    }[];
    expect(definitions).toHaveLength(2);
    for (const definition of definitions) {
      expect(definition.Properties.Volumes).toEqual([{ Name: 'tmp' }]);
      for (const c of definition.Properties.ContainerDefinitions) {
        expect(c).toMatchObject({
          ReadonlyRootFilesystem: true,
          LinuxParameters: { Capabilities: { Drop: ['ALL'] } },
          MountPoints: [{ ContainerPath: '/tmp', SourceVolume: 'tmp', ReadOnly: false }],
        });
      }
    }
    app.hasResourceProperties('AWS::ECS::TaskDefinition', {
      ContainerDefinitions: [
        Match.objectLike({
          Name: 'App',
          HealthCheck: Match.objectLike({ Command: ['CMD-SHELL', Match.stringLikeRegexp('/livez')] }),
        }),
      ],
    });
  });

  test('pulls the image from the branchbook repository by digest, never building it at deploy time', () => {
    expect(JSON.stringify(container(app, 'App'))).toContain(`/branchbook@${DIGEST}`);
    expect(JSON.stringify(container(app, 'Migrate'))).toContain(`/branchbook@${DIGEST}`);
    expect(JSON.stringify(app.toJSON())).not.toContain('cdk-hnb659fds-container-assets');
  });

  test('runs the tasks in private subnets with no public address', () => {
    const services = Object.values(app.findResources('AWS::ECS::Service')) as {
      Properties: { NetworkConfiguration: { AwsvpcConfiguration: { AssignPublicIp: string; Subnets: unknown[] } } };
    }[];
    const network = services[0].Properties.NetworkConfiguration.AwsvpcConfiguration;
    expect(network.AssignPublicIp).toBe('DISABLED');
    expect(network.Subnets).toHaveLength(2);
    for (const subnet of network.Subnets) {
      expect(JSON.stringify(subnet)).toMatch(/Networkapp/);
    }
  });

  test('lets the tasks call out on 443 and to the database alone, and the database admit the tasks alone', () => {
    app.hasResourceProperties('AWS::EC2::SecurityGroup', {
      GroupDescription: 'BranchBook app task: in from the load balancer only',
      SecurityGroupEgress: [{ CidrIp: '0.0.0.0/0', IpProtocol: 'tcp', FromPort: 443, ToPort: 443, Description: Match.anyValue() }],
    });
    app.hasResourceProperties('AWS::EC2::SecurityGroupEgress', { FromPort: 5432, ToPort: 5432, DestinationSecurityGroupId: Match.anyValue() });
    app.hasResourceProperties('AWS::EC2::SecurityGroupIngress', { FromPort: 5432, ToPort: 5432, SourceSecurityGroupId: Match.anyValue() });
    app.hasResourceProperties('AWS::EC2::SecurityGroupIngress', { FromPort: 8080, SourceSecurityGroupId: Match.anyValue() });
  });

  test('never exposes the management port: no target group, rule or port mapping names 8081', () => {
    for (const type of ['AWS::EC2::SecurityGroupIngress', 'AWS::EC2::SecurityGroupEgress', 'AWS::ElasticLoadBalancingV2::TargetGroup']) {
      expect(JSON.stringify(app.findResources(type))).not.toMatch(/8081/);
    }
    expect(container(app, 'App').PortMappings?.map((p) => p.ContainerPort)).toEqual([8080]);
    app.hasResourceProperties('AWS::ElasticLoadBalancingV2::TargetGroup', { Port: 8080, HealthCheckPath: '/readyz' });
  });

  test('scales between the configured bounds on CPU and on requests per task', () => {
    app.hasResourceProperties('AWS::ApplicationAutoScaling::ScalableTarget', {
      MinCapacity: config.service.minTasks,
      MaxCapacity: config.service.maxTasks,
    });
    app.hasResourceProperties('AWS::ApplicationAutoScaling::ScalingPolicy', {
      TargetTrackingScalingPolicyConfiguration: Match.objectLike({
        PredefinedMetricSpecification: { PredefinedMetricType: 'ECSServiceAverageCPUUtilization' },
        TargetValue: config.service.cpuTargetPercent,
      }),
    });
    app.hasResourceProperties('AWS::ApplicationAutoScaling::ScalingPolicy', {
      TargetTrackingScalingPolicyConfiguration: Match.objectLike({
        PredefinedMetricSpecification: Match.objectLike({ PredefinedMetricType: 'ALBRequestCountPerTarget' }),
        TargetValue: config.service.requestsPerTargetPerMinute,
      }),
    });
    // Autoscaling owns the count, so a deployment never resets it.
    app.hasResourceProperties('AWS::ECS::Service', { DesiredCount: Match.absent() });
  });

  test('rolls back a deployment that never becomes healthy or that raises the 5xx alarm', () => {
    app.hasResourceProperties('AWS::ECS::Service', {
      DeploymentConfiguration: Match.objectLike({
        DeploymentCircuitBreaker: { Enable: true, Rollback: true },
        Alarms: { Enable: true, Rollback: true, AlarmNames: [{ Ref: Match.stringLikeRegexp('^ServerErrors') }] },
        MinimumHealthyPercent: config.service.minHealthyPercent,
        MaximumPercent: config.service.maxHealthyPercent,
      }),
    });
  });

  test('raises three alarms with the configured thresholds', () => {
    // The first three alarms keep their logical IDs; the rest are tested in observability.test.ts.
    expect(Object.keys(app.findResources('AWS::CloudWatch::Alarm')).filter((id) => /^(ServerErrors|NoHealthyTask|DatabaseStorage)[0-9A-F]{8}$/.test(id))).toHaveLength(3);
    app.hasResourceProperties('AWS::CloudWatch::Alarm', {
      Threshold: config.alarms.serverErrorThreshold,
      Period: config.alarms.serverErrorPeriodMinutes * 60,
    });
    app.hasResourceProperties('AWS::CloudWatch::Alarm', { Threshold: config.alarms.databaseFreeStorageGiB * 1024 ** 3 });
  });

  test('puts WAF in front: /actuator blocked, three per-address limits, a CAPTCHA on bookings, then AWS managed rules', () => {
    const decoded = [
      { Priority: 0, Type: 'URL_DECODE' },
      { Priority: 1, Type: 'LOWERCASE' },
    ];
    const rate = (limit: number, window: number) => ({
      RateBasedStatement: Match.objectLike({ Limit: limit, EvaluationWindowSec: window, AggregateKeyType: 'IP' }),
    });
    const { waf } = config;
    app.hasResourceProperties('AWS::WAFv2::WebACL', {
      Scope: 'REGIONAL',
      Rules: [
        Match.objectLike({ Name: 'block-actuator', Action: { Block: {} }, Statement: { ByteMatchStatement: Match.objectLike({ TextTransformations: decoded }) } }),
        Match.objectLike({ Name: 'booking-rate-limit', Action: { Block: {} }, Statement: rate(waf.bookingLimit, waf.bookingWindowSeconds) }),
        Match.objectLike({ Name: 'lookup-rate-limit', Action: { Block: {} }, Statement: rate(waf.lookupLimit, waf.lookupWindowSeconds) }),
        Match.objectLike({ Name: 'request-rate-limit', Action: { Block: {} }, Statement: rate(waf.requestLimit, waf.requestWindowSeconds) }),
        Match.objectLike({
          Name: 'booking-captcha',
          Action: { Captcha: {} },
          CaptchaConfig: { ImmunityTimeProperty: { ImmunityTime: waf.captchaImmunitySeconds } },
        }),
        Match.objectLike({ Name: 'AWSManagedRulesAmazonIpReputationList' }),
        Match.objectLike({ Name: 'AWSManagedRulesKnownBadInputsRuleSet' }),
        Match.objectLike({ Name: 'AWSManagedRulesCommonRuleSet' }),
      ],
    });
    app.resourceCountIs('AWS::WAFv2::WebACLAssociation', 1);
  });

  test('logs WAF\'s requests with the number, the synthetic token, the path, the query and the referrer redacted (BR-19)', () => {
    app.hasResourceProperties('AWS::WAFv2::LoggingConfiguration', {
      LogDestinationConfigs: [Match.stringLikeRegexp(`:log-group:aws-waf-logs-${config.resourcePrefix}$`)],
      RedactedFields: Match.arrayWith([
        { SingleHeader: { Name: 'x-mobile-last4' } },
        { SingleHeader: { Name: 'x-branchbook-synthetic' } },
        { SingleHeader: { Name: 'referer' } },
        { QueryString: {} },
        { UriPath: {} },
      ]),
    });
    app.hasResourceProperties('AWS::Logs::LogGroup', {
      LogGroupName: `aws-waf-logs-${config.resourcePrefix}`,
      RetentionInDays: config.logs.wafRetentionDays,
    });
  });

  test('keeps application logs for the configured period', () => {
    app.hasResourceProperties('AWS::Logs::LogGroup', {
      LogGroupName: `/${config.resourcePrefix}/app`,
      RetentionInDays: config.logs.appRetentionDays,
    });
  });

  test('serves HTTPS with a TLS 1.3 policy and redirects plain HTTP to it', () => {
    app.hasResourceProperties('AWS::ElasticLoadBalancingV2::Listener', { Port: 443, Protocol: 'HTTPS', SslPolicy: Match.stringLikeRegexp('TLS13') });
    app.hasResourceProperties('AWS::ElasticLoadBalancingV2::Listener', {
      Port: 80,
      DefaultActions: [Match.objectLike({ Type: 'redirect', RedirectConfig: Match.objectLike({ Protocol: 'HTTPS' }) })],
    });
    app.hasResourceProperties('AWS::Route53::RecordSet', { Name: `${config.domain.name}.`, Type: 'A' });
  });

  test('keeps the database encrypted, private, TLS-only, IAM-authenticated, and its log unexported', () => {
    built.data.hasResourceProperties('AWS::RDS::DBInstance', {
      Engine: 'postgres',
      EngineVersion: Match.stringLikeRegexp('^17\\.'),
      DBInstanceClass: `db.${config.database.instanceClass}`,
      StorageEncrypted: true,
      PubliclyAccessible: false,
      EnableIAMDatabaseAuthentication: true,
      MultiAZ: config.database.multiAz,
      DeletionProtection: config.database.deletionProtection,
      BackupRetentionPeriod: config.database.backupRetentionDays,
      EnableCloudwatchLogsExports: Match.absent(),
    });
    built.data.hasResourceProperties('AWS::RDS::DBParameterGroup', { Parameters: { 'rds.force_ssl': '1' } });
  });

  test('generates the database credentials and the SMS cap key into Secrets Manager, never into the template', () => {
    built.data.hasResourceProperties('AWS::SecretsManager::Secret', {
      Name: `${config.resourcePrefix}/database`,
      GenerateSecretString: Match.objectLike({ GenerateStringKey: 'password' }),
    });
    built.data.hasResource('AWS::SecretsManager::Secret', {
      DeletionPolicy: 'Delete',
      Properties: Match.objectLike({
        Name: `${config.resourcePrefix}/sms-cap-key`,
        GenerateSecretString: { ExcludePunctuation: true, PasswordLength: 48 },
      }),
    });
  });

  test('gives the tasks private subnets with a way out through the configured NAT gateways, and logs rejected traffic', () => {
    built.data.resourceCountIs('AWS::EC2::NatGateway', config.network.natGateways);
    built.data.resourceCountIs('AWS::EC2::Subnet', 6);
    built.data.hasResourceProperties('AWS::EC2::FlowLog', { TrafficType: 'REJECT' });
  });
});

describe('production', () => {
  const { app, data } = production;

  test('runs the database Multi-AZ with deletion protection and 14-day backups', () => {
    data.hasResourceProperties('AWS::RDS::DBInstance', { MultiAZ: true, DeletionProtection: true, BackupRetentionPeriod: 14 });
  });

  test('keeps two to four tasks and a NAT gateway in each zone', () => {
    app.hasResourceProperties('AWS::ApplicationAutoScaling::ScalableTarget', { MinCapacity: 2, MaxCapacity: 4 });
    data.resourceCountIs('AWS::EC2::NatGateway', 2);
  });

  test('adds the app subnets after the existing ones, so those keep their address ranges', () => {
    const ranges = (Object.values(data.findResources('AWS::EC2::Subnet')) as { Properties: { CidrBlock: string } }[])
      .map((s) => s.Properties.CidrBlock)
      .sort();
    expect(ranges).toEqual(['10.40.0.0/24', '10.40.1.0/24', '10.40.2.0/24', '10.40.3.0/24', '10.40.4.0/24', '10.40.5.0/24']);
    data.hasResourceProperties('AWS::EC2::Subnet', { CidrBlock: '10.40.0.0/24', MapPublicIpOnLaunch: true });
    data.hasResourceProperties('AWS::EC2::Subnet', { CidrBlock: '10.40.4.0/24', MapPublicIpOnLaunch: false });
  });

  test('lets no request skip the CAPTCHA: there is no synthetic-traffic bypass or token', () => {
    const acl = JSON.stringify(app.findResources('AWS::WAFv2::WebACL'));
    expect(acl).not.toContain('x-branchbook-synthetic');
    expect(acl).not.toContain('NotStatement');
    expect(acl).not.toContain('resolve:secretsmanager');
    expect(JSON.stringify(data.findResources('AWS::SecretsManager::Secret'))).not.toContain('synthetic-traffic-token');
  });

  test('keeps today\'s app settings: real SMS through AWS, five a day to a number, demo numbers not required', () => {
    expect(environment(container(app, 'App'))).toMatchObject({
      BRANCHBOOK_SMS_GATEWAY: 'aws',
      BRANCHBOOK_SMS_AWS_REGION: 'af-south-1',
      BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP: '5',
      BRANCHBOOK_SMS_AWS_DRY_RUN: 'false',
      BRANCHBOOK_BOOKING_DEMO_NUMBERS_ONLY: 'false',
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
            Resource: { 'Fn::GetAtt': [Match.stringLikeRegexp('^SmsProtection'), 'Arn'] },
          }),
        ]),
      },
    });
  });

  test('can deploy a task that only asks AWS to check each send', () => {
    const cdkApp = newApp();
    const stacks = addEnvironment(cdkApp, production.config, loadRegistryConfig(), {
      imageDigest: DIGEST,
      version: 'local',
      smsDryRun: true,
    });
    expect(environment(container(Template.fromStack(stacks.app), 'App')).BRANCHBOOK_SMS_AWS_DRY_RUN).toBe('true');
  });
});

describe('staging', () => {
  const { app, data } = staging;

  test('lets requests with the synthetic-traffic token skip the CAPTCHA, through a dynamic reference to its secret', () => {
    app.hasResourceProperties('AWS::WAFv2::WebACL', {
      Rules: Match.arrayWith([
        Match.objectLike({
          Name: 'booking-captcha',
          Statement: {
            AndStatement: {
              Statements: Match.arrayWith([
                {
                  NotStatement: {
                    Statement: {
                      ByteMatchStatement: Match.objectLike({
                        FieldToMatch: { SingleHeader: { Name: 'x-branchbook-synthetic' } },
                        PositionalConstraint: 'EXACTLY',
                        SearchString: '{{resolve:secretsmanager:branchbook-staging/synthetic-traffic-token:SecretString:::}}',
                      }),
                    },
                  },
                },
              ]),
            },
          },
        }),
      ]),
    });
    data.hasResourceProperties('AWS::SecretsManager::Secret', {
      Name: 'branchbook-staging/synthetic-traffic-token',
      GenerateSecretString: Match.objectLike({ ExcludePunctuation: true }),
    });
  });

  test('runs one to two tasks, a Single-AZ database without deletion protection, and one NAT gateway', () => {
    app.hasResourceProperties('AWS::ApplicationAutoScaling::ScalableTarget', { MinCapacity: 1, MaxCapacity: 2 });
    data.hasResourceProperties('AWS::RDS::DBInstance', { MultiAZ: false, DeletionProtection: false, BackupRetentionPeriod: 3 });
    data.resourceCountIs('AWS::EC2::NatGateway', 1);
  });

  test('sends no real SMS and books demo numbers only, so no protect configuration exists', () => {
    expect(environment(container(app, 'App'))).toMatchObject({
      BRANCHBOOK_SMS_GATEWAY: 'simulated',
      BRANCHBOOK_BOOKING_DEMO_NUMBERS_ONLY: 'true',
      PUBLIC_BASE_URL: 'https://stg.branchbook.click',
    });
    app.resourceCountIs('AWS::SMSVOICE::ProtectConfiguration', 0);
  });

  test('uses its own names, so it never collides with production', () => {
    data.hasResourceProperties('AWS::SecretsManager::Secret', { Name: 'branchbook-staging/database' });
    app.hasResourceProperties('AWS::Logs::LogGroup', { LogGroupName: '/branchbook-staging/app', RetentionInDays: 7 });
  });
});
