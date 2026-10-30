/**
 * Unit tests for what an operator watches and rehearses in each environment: the app's metrics export, the alarms,
 * the dashboard and the canary (lib/constructs/observability.ts), built from infra/config exactly as `cdk synth`
 * builds them.
 */
import { Match, Template } from 'aws-cdk-lib/assertions';
import { EnvironmentConfig, EnvironmentName, loadEnvironmentConfig, loadRegistryConfig } from '../lib/config';
import { addEnvironment } from '../lib/environment';
import { newApp } from './cdk-app';

const DIGEST = `sha256:${'0'.repeat(64)}`;

/**
 * Builds one environment's app stack.
 *
 * @param config - the environment, possibly changed by the test
 */
function build(config: EnvironmentConfig): Template {
  const stacks = addEnvironment(newApp(), config, loadRegistryConfig(), { account: '111111111111', imageDigest: DIGEST, version: 'local' });
  return Template.fromStack(stacks.app);
}

const built = Object.fromEntries(
  (['production', 'staging'] as const).map((name) => {
    const config = loadEnvironmentConfig(name);
    return [name, { config, app: build(config) }];
  }),
) as Record<EnvironmentName, { config: EnvironmentConfig; app: Template }>;

/** The parts of a synthesised alarm the assertions read. */
interface AlarmProperties {
  MetricName?: string;
  Namespace?: string;
  Statistic?: string;
  ExtendedStatistic?: string;
  Dimensions?: { Name: string; Value: unknown }[];
  Period?: number;
  Threshold: number;
  EvaluationPeriods: number;
  ComparisonOperator: string;
  TreatMissingData?: string;
  AlarmActions?: unknown[];
  OKActions?: unknown[];
  Metrics?: { Id: string; Expression?: string; MetricStat?: { Metric: { MetricName: string; Namespace: string }; Stat: string; Period: number } }[];
}

/**
 * The one alarm whose logical ID starts with the construct path, such as `ObservabilityDispatcherStalled`.
 *
 * @param template - the app stack
 * @param id - the start of the logical ID
 */
function alarm(template: Template, id: string): AlarmProperties {
  const found = Object.entries(template.findResources('AWS::CloudWatch::Alarm')).filter(([logicalId]) =>
    new RegExp(`^${id}[0-9A-F]{8}$`).test(logicalId),
  );
  expect(found).toHaveLength(1);
  return (found[0][1] as { Properties: AlarmProperties }).Properties;
}

const TOPIC = { Ref: expect.stringMatching(/^Alarms[0-9A-F]{8}$/) as unknown };

/**
 * The container's environment, by name.
 *
 * @param template - the app stack
 * @param name - the container's name
 */
function containerEnvironment(template: Template, name: 'App' | 'Migrate'): Record<string, unknown> {
  const definitions = Object.values(template.findResources('AWS::ECS::TaskDefinition')) as {
    Properties: { ContainerDefinitions: { Name: string; Environment: { Name: string; Value: unknown }[] }[] };
  }[];
  const found = definitions.flatMap((d) => d.Properties.ContainerDefinitions).find((c) => c.Name === name);
  if (found === undefined) {
    throw new Error(`no container ${name}`);
  }
  return Object.fromEntries(found.Environment.map((e) => [e.Name, e.Value]));
}

describe.each(['production', 'staging'] as const)('%s', (name) => {
  const { app, config } = built[name];
  const { monitoring, canary } = config;
  const namespace = `BranchBook/${name}`;

  test('exports the app\'s meters to its own namespace, from the service only, never the migration', () => {
    expect(monitoring.metricsNamespace).toBe(namespace);
    expect(containerEnvironment(app, 'App')).toMatchObject({
      BRANCHBOOK_METRICS_CLOUDWATCH_ENABLED: 'true',
      BRANCHBOOK_METRICS_CLOUDWATCH_NAMESPACE: namespace,
      BRANCHBOOK_METRICS_CLOUDWATCH_STEP: 'PT60S',
    });
    expect(Object.keys(containerEnvironment(app, 'Migrate')).filter((key) => key.startsWith('BRANCHBOOK_METRICS_'))).toEqual([]);
  });

  test('lets the task role put metrics into its environment\'s namespace and no other', () => {
    app.hasResourceProperties('AWS::IAM::Policy', {
      Roles: [{ Ref: Match.stringLikeRegexp('^TaskTaskRole') }],
      PolicyDocument: {
        Statement: Match.arrayWith([
          {
            Action: 'cloudwatch:PutMetricData',
            Effect: 'Allow',
            Resource: '*',
            Condition: { StringEquals: { 'cloudwatch:namespace': namespace } },
          },
        ]),
      },
    });
    const migrationPolicies = JSON.stringify(
      Object.values(app.findResources('AWS::IAM::Policy')).filter((p) => JSON.stringify(p).includes('MigrationTaskTaskRole')),
    );
    expect(migrationPolicies).not.toContain('cloudwatch:PutMetricData');
  });

  test('raises an alarm when the oldest pending message has waited too long, on the worst task\'s view', () => {
    expect(alarm(app, 'ObservabilityOutboxBacklogAge')).toMatchObject({
      Namespace: namespace,
      MetricName: 'outbox.oldest.pending.age.value',
      Statistic: 'Maximum',
      Period: monitoring.metricsStepSeconds,
      Threshold: monitoring.outboxOldestPendingSeconds,
      ComparisonOperator: 'GreaterThanThreshold',
      TreatMissingData: 'notBreaching',
      AlarmActions: [TOPIC],
      OKActions: [TOPIC],
    });
  });

  test('raises an alarm once for every new FAILED message: the change in the level, not the level', () => {
    const failures = alarm(app, 'ObservabilityOutboxNewFailures');
    expect(failures).toMatchObject({
      Threshold: monitoring.outboxFailedIncrease,
      ComparisonOperator: 'GreaterThanOrEqualToThreshold',
      TreatMissingData: 'notBreaching',
      AlarmActions: [TOPIC],
    });
    expect(failures.OKActions).toBeUndefined();
    const metrics = failures.Metrics ?? [];
    const failed = metrics.find((m) => m.MetricStat !== undefined);
    expect(metrics.find((m) => m.Expression !== undefined)?.Expression).toBe(`DIFF(${failed?.Id ?? ''})`);
    expect(failed?.MetricStat).toMatchObject({
      Metric: { Namespace: namespace, MetricName: 'outbox.failed.value' },
      Stat: 'Maximum',
      Period: monitoring.outboxFailedPeriodMinutes * 60,
    });
  });

  test('raises an alarm when the dispatcher stalls or the app stops exporting: missing data breaches', () => {
    expect(alarm(app, 'ObservabilityDispatcherStalled')).toMatchObject({
      Namespace: namespace,
      MetricName: 'outbox.last.tick.age.value',
      Statistic: 'Maximum',
      Threshold: monitoring.dispatcherStalledSeconds,
      EvaluationPeriods: monitoring.dispatcherStalledPeriods,
      ComparisonOperator: 'GreaterThanThreshold',
      TreatMissingData: 'breaching',
      AlarmActions: [TOPIC],
      OKActions: [TOPIC],
    });
    expect(monitoring.dispatcherStalledSeconds).toBe(60);
  });

  test('raises an alarm while the SMS circuit breaker is open', () => {
    const open = alarm(app, 'ObservabilitySmsCircuitOpen');
    expect(open).toMatchObject({
      Namespace: namespace,
      MetricName: 'resilience4j.circuitbreaker.state.value',
      Statistic: 'Maximum',
      Threshold: 1,
      ComparisonOperator: 'GreaterThanOrEqualToThreshold',
      TreatMissingData: 'notBreaching',
      AlarmActions: [TOPIC],
      OKActions: [TOPIC],
    });
    expect(open.Dimensions).toEqual(
      expect.arrayContaining([
        { Name: 'name', Value: 'sms' },
        { Name: 'state', Value: 'open' },
      ]),
    );
    expect(open.Dimensions).toHaveLength(2);
  });

  test('raises an alarm on the load balancer\'s 99th-percentile response time', () => {
    expect(alarm(app, 'ObservabilityResponseTimeP99')).toMatchObject({
      Namespace: 'AWS/ApplicationELB',
      MetricName: 'TargetResponseTime',
      ExtendedStatistic: 'p99',
      Period: monitoring.responseTimePeriodMinutes * 60,
      Threshold: monitoring.responseTimeP99Milliseconds / 1000,
      EvaluationPeriods: monitoring.responseTimePeriods,
      ComparisonOperator: 'GreaterThanThreshold',
      TreatMissingData: 'notBreaching',
    });
  });

  test('raises alarms on the database\'s CPU, connections against its budget, and memory', () => {
    const sustained = { Period: monitoring.resourcePeriodMinutes * 60, EvaluationPeriods: monitoring.resourcePeriods, AlarmActions: [TOPIC], OKActions: [TOPIC] };
    expect(alarm(app, 'ObservabilityDatabaseCpu')).toMatchObject({
      Namespace: 'AWS/RDS',
      MetricName: 'CPUUtilization',
      Statistic: 'Average',
      Threshold: monitoring.databaseCpuPercent,
      ComparisonOperator: 'GreaterThanThreshold',
      ...sustained,
    });
    expect(alarm(app, 'ObservabilityDatabaseConnections')).toMatchObject({
      MetricName: 'DatabaseConnections',
      Statistic: 'Maximum',
      Threshold: Math.floor((config.database.connectionBudget * monitoring.databaseConnectionsPercent) / 100),
      ComparisonOperator: 'GreaterThanThreshold',
      ...sustained,
    });
    expect(alarm(app, 'ObservabilityDatabaseMemory')).toMatchObject({
      MetricName: 'FreeableMemory',
      Statistic: 'Minimum',
      Threshold: monitoring.databaseFreeableMemoryMiB * 1024 ** 2,
      ComparisonOperator: 'LessThanThreshold',
      ...sustained,
    });
  });

  test('raises alarms on the service\'s average CPU and its busiest task\'s memory', () => {
    expect(alarm(app, 'ObservabilityServiceCpu')).toMatchObject({
      Namespace: 'AWS/ECS',
      MetricName: 'CPUUtilization',
      Statistic: 'Average',
      Threshold: monitoring.serviceCpuPercent,
      EvaluationPeriods: monitoring.resourcePeriods,
    });
    expect(alarm(app, 'ObservabilityServiceMemory')).toMatchObject({
      Namespace: 'AWS/ECS',
      MetricName: 'MemoryUtilization',
      Statistic: 'Maximum',
      Threshold: monitoring.serviceMemoryPercent,
    });
  });

  test('keeps the first three alarms as they were, and adds eleven', () => {
    app.resourceCountIs('AWS::CloudWatch::Alarm', 14);
    alarm(app, 'ServerErrors');
    alarm(app, 'NoHealthyTask');
    alarm(app, 'DatabaseStorage');
  });

  test('draws one dashboard named after the resource prefix, covering every signal', () => {
    app.resourceCountIs('AWS::CloudWatch::Dashboard', 1);
    app.hasResourceProperties('AWS::CloudWatch::Dashboard', { DashboardName: config.resourcePrefix });
    const body = JSON.stringify(app.findResources('AWS::CloudWatch::Dashboard'));
    for (const metric of [
      'RequestCount',
      'HTTPCode_Target_5XX_Count',
      'TargetResponseTime',
      'HealthyHostCount',
      'outbox.pending.value',
      'outbox.oldest.pending.age.value',
      'outbox.failed.value',
      'outbox.last.tick.age.value',
      'resilience4j.circuitbreaker.state.value',
      'DatabaseConnections',
      'FreeableMemory',
      'FreeStorageSpace',
      'MemoryUtilization',
      'AllowedRequests',
      'BlockedRequests',
      'CaptchaRequests',
      'booking-rate-limit',
      'booking-captcha',
      'AWSManagedRulesCommonRuleSet',
      `${config.resourcePrefix}-web-acl`,
      'SuccessPercent',
    ]) {
      expect(body).toContain(metric);
    }
    expect(body.includes('RunningTaskCount')).toBe(config.service.containerInsights);
  });

  test('runs the canary on the newest supported runtime, on the configured schedule, against this environment', () => {
    app.hasResourceProperties('AWS::Synthetics::Canary', {
      RuntimeVersion: 'syn-nodejs-puppeteer-18.0',
      Schedule: { Expression: `rate(${String(canary.scheduleMinutes)} minutes)` },
      Code: { Handler: 'index.handler', Script: Match.stringLikeRegexp('executeHttpStep') },
      RunConfig: {
        TimeoutInSeconds: canary.timeoutSeconds,
        EnvironmentVariables: {
          BRANCHBOOK_BASE_URL: `https://${config.domain.name}`,
          BRANCHBOOK_BRANCH_CODE: canary.branchCode,
          BRANCHBOOK_SERVICE_CODE: canary.serviceCode,
          BRANCHBOOK_TIME_ZONE: 'Africa/Johannesburg',
        },
      },
      SuccessRetentionPeriod: canary.artifactRetentionDays,
      FailureRetentionPeriod: canary.artifactRetentionDays,
      ProvisionedResourceCleanup: 'AUTOMATIC',
      StartCanaryAfterCreation: true,
    });
    expect(alarm(app, 'ObservabilityCanaryFailing')).toMatchObject({
      Namespace: 'CloudWatchSynthetics',
      MetricName: 'SuccessPercent',
      Statistic: 'Average',
      Period: canary.scheduleMinutes * 60,
      Threshold: canary.successPercentThreshold,
      EvaluationPeriods: canary.failingPeriods,
      ComparisonOperator: 'LessThanThreshold',
      TreatMissingData: 'breaching',
      AlarmActions: [TOPIC],
    });
  });

  test('keeps the canary\'s artifacts encrypted, private, TLS-only and expiring', () => {
    app.hasResourceProperties('AWS::S3::Bucket', {
      BucketEncryption: { ServerSideEncryptionConfiguration: [{ ServerSideEncryptionByDefault: { SSEAlgorithm: 'AES256' } }] },
      PublicAccessBlockConfiguration: { BlockPublicAcls: true, BlockPublicPolicy: true, IgnorePublicAcls: true, RestrictPublicBuckets: true },
      LifecycleConfiguration: { Rules: [Match.objectLike({ ExpirationInDays: canary.artifactRetentionDays, Status: 'Enabled' })] },
    });
    app.hasResourceProperties('AWS::S3::BucketPolicy', {
      PolicyDocument: {
        Statement: Match.arrayWith([
          Match.objectLike({ Effect: 'Deny', Condition: { Bool: { 'aws:SecureTransport': 'false' } } }),
          Match.objectLike({ Effect: 'Deny', Condition: { NumericLessThan: { 's3:TlsVersion': 1.2 } } }),
        ]),
      },
    });
  });

});

describe('production', () => {
  const { app } = built.production;

  test('keeps the canary\'s artifacts when the stack is deleted', () => {
    app.hasResource('AWS::S3::Bucket', { DeletionPolicy: 'Retain' });
    app.resourceCountIs('Custom::S3AutoDeleteObjects', 0);
  });
});

describe('staging', () => {
  const { app } = built.staging;

  test('empties and deletes the canary\'s artifacts with the stack', () => {
    app.resourceCountIs('Custom::S3AutoDeleteObjects', 1);
  });
});

describe('switched off', () => {
  test('creates no canary when the configuration turns it off', () => {
    const production = built.production.config;
    const app = build({ ...production, canary: { ...production.canary, enabled: false } });
    app.resourceCountIs('AWS::Synthetics::Canary', 0);
    // The dashboard and the other alarms stay.
    app.resourceCountIs('AWS::CloudWatch::Dashboard', 1);
    app.resourceCountIs('AWS::CloudWatch::Alarm', 13);
  });
});
