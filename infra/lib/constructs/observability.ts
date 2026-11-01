import * as fs from 'node:fs';
import * as path from 'node:path';
import { Duration, RemovalPolicy, Stack, Tokenization, Validations } from 'aws-cdk-lib/core';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as cloudwatchActions from 'aws-cdk-lib/aws-cloudwatch-actions';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as synthetics from 'aws-cdk-lib/aws-synthetics';
import * as wafv2 from 'aws-cdk-lib/aws-wafv2';
import { Construct } from 'constructs';
import { EnvironmentConfig } from '../config';
import { nagArnPrefix } from './nag-arn';

/**
 * The app's metrics as Micrometer's CloudWatch registry names them (api/.../shared/metrics): the meter's dotted name,
 * and `.value` after a gauge's. None carries a dimension except the circuit breaker's, which Resilience4j tags with
 * the breaker's `name` and one `state` per state, each 1 while the breaker is in it and 0 otherwise. The app adds no
 * common tag, so no other dimension appears.
 */
export const APP_METRICS = {
  /** Messages still PENDING after the dispatcher's last tick. */
  outboxPending: 'outbox.pending.value',
  /** Seconds the oldest PENDING message has waited; 0 when none waits. */
  outboxOldestPendingAge: 'outbox.oldest.pending.age.value',
  /** Messages FAILED, for any reason: a level, not a count of events. */
  outboxFailed: 'outbox.failed.value',
  /** Seconds since the dispatcher last finished a tick. */
  outboxLastTickAge: 'outbox.last.tick.age.value',
  /** 1 while the breaker is in the `state` dimension's state, 0 otherwise. */
  circuitBreakerState: 'resilience4j.circuitbreaker.state.value',
} as const;

/** The app's metrics the alarms and the dashboard read. */
interface AppMetricSet {
  readonly outboxPending: cloudwatch.Metric;
  readonly outboxOldestPendingAge: cloudwatch.Metric;
  readonly outboxFailed: cloudwatch.Metric;
  readonly outboxLastTickAge: cloudwatch.Metric;
  readonly smsCircuitOpen: cloudwatch.Metric;
}

/** Creates an alarm that publishes to the alarm topic, and, when asked, says when it clears. */
type AddAlarm = (id: string, props: cloudwatch.AlarmProps, sayWhenCleared: boolean) => cloudwatch.Alarm;

/** The dimensions of the SMS breaker's `open` series (DeliveryCircuit.NAME is `sms`). */
export const SMS_CIRCUIT_OPEN = { name: 'sms', state: 'open' } as const;

/**
 * The canary's runtime: `syn-nodejs-puppeteer-18.0`, the newest Node.js runtime AWS documents as supported. CDK
 * 2.274.0's newest constant is `SYNTHETICS_NODEJS_PUPPETEER_13_0`, so the name is written out; the canary makes plain
 * HTTPS calls (`executeHttpStep`) and never starts the browser the runtime ships.
 */
export const CANARY_RUNTIME = new synthetics.Runtime('syn-nodejs-puppeteer-18.0', synthetics.RuntimeFamily.NODEJS);

/** The time zone of every branch, in which the app counts its booking window; the canary asks for tomorrow there. */
export const BRANCH_TIME_ZONE = 'Africa/Johannesburg';

/** The canary's script (CommonJS, run as it is by the Synthetics runtime). */
const CANARY_SCRIPT = path.join(__dirname, '..', '..', 'lambda', 'canary', 'index.js');

// The artifacts bucket refuses connections older than TLS 1.2, the oldest version AWS still recommends.
const MINIMUM_TLS_VERSION = 1.2;

/**
 * The variables that switch the app's CloudWatch export on, into the environment's own namespace, at the step the
 * alarms are built for. The service's task gets them; the migration task does not, so it never exports.
 *
 * @param config - the environment's monitoring settings
 */
export function metricsExportEnvironment(config: EnvironmentConfig): Record<string, string> {
  return {
    BRANCHBOOK_METRICS_CLOUDWATCH_ENABLED: 'true',
    BRANCHBOOK_METRICS_CLOUDWATCH_NAMESPACE: config.monitoring.metricsNamespace,
    BRANCHBOOK_METRICS_CLOUDWATCH_STEP: `PT${String(config.monitoring.metricsStepSeconds)}S`,
  };
}

/**
 * Lets a role put metrics into one namespace and no other. PutMetricData names no resource, so the namespace
 * condition is the boundary: the task cannot write into another environment's metrics, or AWS's.
 *
 * @param grantee - the service's task role
 * @param namespace - the environment's namespace
 */
export function allowMetricsExport(grantee: iam.IGrantable, namespace: string): void {
  grantee.grantPrincipal.addToPrincipalPolicy(
    new iam.PolicyStatement({
      actions: ['cloudwatch:PutMetricData'],
      resources: ['*'],
      conditions: { StringEquals: { 'cloudwatch:namespace': namespace } },
    }),
  );
}

/**
 * Configuration for {@link Observability}.
 */
export interface ObservabilityProps {
  /** The environment: namespace, thresholds, periods and the canary's settings. */
  readonly config: EnvironmentConfig;
  /** The Fargate service. */
  readonly service: ecs.FargateService;
  /** The internet-facing load balancer. */
  readonly loadBalancer: elbv2.ApplicationLoadBalancer;
  /** The service's target group. */
  readonly targetGroup: elbv2.ApplicationTargetGroup;
  /** The database. */
  readonly database: rds.DatabaseInstance;
  /** The web ACL, whose rules' metric names the dashboard reads. */
  readonly webAcl: wafv2.CfnWebACL;
  /** The topic every alarm publishes to. */
  readonly alarmTopic: sns.ITopic;
  /** The environment's public address, which the canary calls. */
  readonly url: string;
  /** The alarms the app stack already raises, shown on the dashboard beside these. */
  readonly existingAlarms: readonly cloudwatch.IAlarm[];
}

/**
 * What an operator watches, and what wakes them, for one environment:
 *
 * - **Alarms** on the app's own metrics (the outbox's backlog age, new FAILED messages, a stalled dispatcher, the SMS
 *   circuit breaker open), on the load balancer's 99th-percentile response time, on the database's CPU, connections
 *   and memory, and on the service's CPU and memory. Each publishes to the alarm topic, and most say when they clear.
 * - **A dashboard** named after the resource prefix: the golden signals, the outbox, the circuit breaker, the
 *   database, the service and WAF.
 * - **A canary** (CloudWatch Synthetics) that calls `/readyz` and the availability API from outside, as a customer's
 *   browser would, every few minutes, and an alarm when it fails.
 *
 * Every threshold, period and schedule comes from the environment's configuration (`monitoring` and `canary`).
 */
export class Observability extends Construct {
  /** Every alarm this construct raises, by construct ID. */
  public readonly alarms: Readonly<Record<string, cloudwatch.Alarm>>;

  /** The dashboard. */
  public readonly dashboard: cloudwatch.Dashboard;

  /** The canary, where the environment has one. */
  public readonly canary?: synthetics.Canary;

  private readonly notify: cloudwatchActions.SnsAction;

  constructor(scope: Construct, id: string, props: ObservabilityProps) {
    super(scope, id);
    const { config } = props;
    this.notify = new cloudwatchActions.SnsAction(props.alarmTopic);
    const alarms: Record<string, cloudwatch.Alarm> = {};
    const add: AddAlarm = (alarmId, alarmProps, sayWhenCleared) => {
      const alarm = new cloudwatch.Alarm(this, alarmId, alarmProps);
      alarm.addAlarmAction(this.notify);
      if (sayWhenCleared) {
        alarm.addOkAction(this.notify);
      }
      alarms[alarmId] = alarm;
      return alarm;
    };

    const app = this.appMetrics(config);
    this.appAlarms(config, app, add);
    this.awsAlarms(props, add);
    if (config.canary.enabled) {
      this.canary = this.createCanary(props);
      const { canary } = config;
      add(
        'CanaryFailing',
        {
          alarmDescription: `The outside-in canary failed in ${String(canary.failingPeriods)} runs in a row: /readyz or the availability API did not answer 200 with a sane body.`,
          metric: this.canary.metricSuccessPercent({ period: Duration.minutes(canary.scheduleMinutes), statistic: 'Average' }),
          threshold: canary.successPercentThreshold,
          evaluationPeriods: canary.failingPeriods,
          comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
          // A canary that stops reporting has lost the outside view, which is itself worth waking someone for.
          treatMissingData: cloudwatch.TreatMissingData.BREACHING,
        },
        true,
      );
    }
    this.alarms = alarms;
    this.dashboard = this.createDashboard(props, app, [...props.existingAlarms, ...Object.values(alarms)]);
  }

  /**
   * The app's gauges. Two or more tasks each export the same gauge with no dimension to tell them apart, so CloudWatch
   * holds one datapoint per task per step; the alarms read the Maximum, which is the worst task's view: an age that
   * one stuck dispatcher sees is a problem even when another task's dispatcher is fine.
   *
   * @param config - the environment's namespace and export step
   */
  private appMetrics(config: EnvironmentConfig): AppMetricSet {
    const { monitoring } = config;
    // No label: an alarm on a labelled metric is written as a metric-math query, which reads worse in the console.
    const metric = (metricName: string, dimensionsMap?: Record<string, string>): cloudwatch.Metric =>
      new cloudwatch.Metric({
        namespace: monitoring.metricsNamespace,
        metricName,
        dimensionsMap,
        statistic: cloudwatch.Stats.MAXIMUM,
        period: Duration.seconds(monitoring.metricsStepSeconds),
      });
    return {
      outboxPending: metric(APP_METRICS.outboxPending),
      outboxOldestPendingAge: metric(APP_METRICS.outboxOldestPendingAge),
      outboxFailed: metric(APP_METRICS.outboxFailed),
      outboxLastTickAge: metric(APP_METRICS.outboxLastTickAge),
      smsCircuitOpen: metric(APP_METRICS.circuitBreakerState, { ...SMS_CIRCUIT_OPEN }),
    };
  }

  /**
   * The four alarms on the app's own metrics.
   *
   * @param config - the environment's thresholds
   * @param app - the app's metrics
   * @param add - creates an alarm that publishes to the topic
   */
  private appAlarms(
    config: EnvironmentConfig,
    app: AppMetricSet,
    add: AddAlarm,
  ): void {
    const { monitoring } = config;

    // A message backing off waits about 30 seconds at most, and a burst drains within a tick or two, so an oldest
    // message older than the threshold means delivery has stopped, for example while the circuit breaker is open.
    // No data is not a breach here: the dispatcher alarm below owns a silent app.
    add(
      'OutboxBacklogAge',
      {
        alarmDescription: `A message has waited more than ${String(monitoring.outboxOldestPendingSeconds)} seconds to be sent: SMS delivery is stuck.`,
        metric: app.outboxOldestPendingAge,
        threshold: monitoring.outboxOldestPendingSeconds,
        evaluationPeriods: 1,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
        treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
      },
      true,
    );

    // outbox.failed is a level that only falls when retention deletes old messages, so an alarm on the level would
    // stay raised for days after one failure and stay silent through the next. DIFF turns it into the number of
    // messages that turned FAILED in each period, so every new failure raises the alarm once. It clears by itself in
    // the next quiet period, which says nothing about the cause, so it sends no OK.
    const failedPeriod = Duration.minutes(monitoring.outboxFailedPeriodMinutes);
    add(
      'OutboxNewFailures',
      {
        alarmDescription: `${String(monitoring.outboxFailedIncrease)} or more messages turned FAILED within ${String(monitoring.outboxFailedPeriodMinutes)} minutes: they will not be sent without an operator.`,
        metric: new cloudwatch.MathExpression({
          expression: 'DIFF(failed)',
          usingMetrics: { failed: app.outboxFailed.with({ period: failedPeriod }) },
          period: failedPeriod,
          label: 'Messages newly FAILED',
        }),
        threshold: monitoring.outboxFailedIncrease,
        evaluationPeriods: 1,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
      },
      false,
    );

    // The dispatcher ticks every few seconds, so a tick age over the threshold means it has stopped or hangs. No
    // data is a breach too: a task that exports nothing has no working dispatcher anyone can see. Two periods ride
    // out one late export.
    add(
      'DispatcherStalled',
      {
        alarmDescription: `The outbox dispatcher has not finished a tick for more than ${String(monitoring.dispatcherStalledSeconds)} seconds, or the app has stopped exporting metrics.`,
        metric: app.outboxLastTickAge,
        threshold: monitoring.dispatcherStalledSeconds,
        evaluationPeriods: monitoring.dispatcherStalledPeriods,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
        treatMissingData: cloudwatch.TreatMissingData.BREACHING,
      },
      true,
    );

    add(
      'SmsCircuitOpen',
      {
        alarmDescription: 'The SMS circuit breaker is open: the provider is failing or slow, and the dispatcher is holding messages back.',
        metric: app.smsCircuitOpen,
        threshold: 1,
        evaluationPeriods: 1,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
      },
      true,
    );
  }

  /**
   * The alarms on AWS's metrics: the load balancer's tail latency, the database and the service. The 5xx,
   * no-healthy-task and free-storage alarms already exist in the app stack.
   *
   * @param props - the resources and thresholds
   * @param add - creates an alarm that publishes to the topic
   */
  private awsAlarms(
    props: ObservabilityProps,
    add: AddAlarm,
  ): void {
    const { monitoring, database } = props.config;
    const resourcePeriod = Duration.minutes(monitoring.resourcePeriodMinutes);
    const sustained = {
      evaluationPeriods: monitoring.resourcePeriods,
      treatMissingData: cloudwatch.TreatMissingData.MISSING,
    };

    // No requests, no datapoints: quiet hours are not slow ones.
    add(
      'ResponseTimeP99',
      {
        alarmDescription: `The slowest 1% of requests took over ${String(monitoring.responseTimeP99Milliseconds)} ms for ${String(monitoring.responseTimePeriods * monitoring.responseTimePeriodMinutes)} minutes.`,
        metric: props.loadBalancer.metrics.targetResponseTime({
          statistic: cloudwatch.Stats.p(99),
          period: Duration.minutes(monitoring.responseTimePeriodMinutes),
        }),
        threshold: monitoring.responseTimeP99Milliseconds / 1000,
        evaluationPeriods: monitoring.responseTimePeriods,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
        treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
      },
      true,
    );

    add(
      'DatabaseCpu',
      {
        alarmDescription: `The database's CPU averaged over ${String(monitoring.databaseCpuPercent)}% for ${String(monitoring.resourcePeriods * monitoring.resourcePeriodMinutes)} minutes.`,
        metric: props.database.metricCPUUtilization({ period: resourcePeriod, statistic: cloudwatch.Stats.AVERAGE }),
        threshold: monitoring.databaseCpuPercent,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
        ...sustained,
      },
      true,
    );

    // The budget already covers the worst case the service can open (config.ts, connectionPeak), so crossing this
    // share of it means a leak or a client that is not the app.
    const connectionLimit = Math.floor((database.connectionBudget * monitoring.databaseConnectionsPercent) / 100);
    add(
      'DatabaseConnections',
      {
        alarmDescription: `The database held more than ${String(connectionLimit)} connections (${String(monitoring.databaseConnectionsPercent)}% of its budget of ${String(database.connectionBudget)}).`,
        metric: props.database.metricDatabaseConnections({ period: resourcePeriod, statistic: cloudwatch.Stats.MAXIMUM }),
        threshold: connectionLimit,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
        ...sustained,
      },
      true,
    );

    add(
      'DatabaseMemory',
      {
        alarmDescription: `The database had less than ${String(monitoring.databaseFreeableMemoryMiB)} MiB of freeable memory.`,
        metric: props.database.metricFreeableMemory({ period: resourcePeriod, statistic: cloudwatch.Stats.MINIMUM }),
        threshold: monitoring.databaseFreeableMemoryMiB * 1024 ** 2,
        comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
        ...sustained,
      },
      true,
    );

    // CPU is shared work, which autoscaling spreads across tasks, so the average is what it cannot keep down. Memory
    // is not: one task near its limit is killed whatever the others hold, so the busiest task decides.
    add(
      'ServiceCpu',
      {
        alarmDescription: `The service's CPU averaged over ${String(monitoring.serviceCpuPercent)}% for ${String(monitoring.resourcePeriods * monitoring.resourcePeriodMinutes)} minutes, beyond what autoscaling absorbed.`,
        metric: props.service.metricCpuUtilization({ period: resourcePeriod, statistic: cloudwatch.Stats.AVERAGE }),
        threshold: monitoring.serviceCpuPercent,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
        ...sustained,
      },
      true,
    );
    add(
      'ServiceMemory',
      {
        alarmDescription: `A task used over ${String(monitoring.serviceMemoryPercent)}% of its memory for ${String(monitoring.resourcePeriods * monitoring.resourcePeriodMinutes)} minutes.`,
        metric: props.service.metricMemoryUtilization({ period: resourcePeriod, statistic: cloudwatch.Stats.MAXIMUM }),
        threshold: monitoring.serviceMemoryPercent,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
        ...sustained,
      },
      true,
    );
  }

  /**
   * The canary and its artifacts bucket. The bucket is encrypted, private, TLS-only, and expires what the canary
   * writes after the configured days; deleting the stack empties and deletes it only where the configuration allows.
   *
   * @param props - the address and the canary's settings
   */
  private createCanary(props: ObservabilityProps): synthetics.Canary {
    const { canary } = props.config;
    const retention = Duration.days(canary.artifactRetentionDays);
    const bucket = new s3.Bucket(this, 'CanaryArtifacts', {
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      objectOwnership: s3.ObjectOwnership.BUCKET_OWNER_ENFORCED,
      enforceSSL: true,
      minimumTLSVersion: MINIMUM_TLS_VERSION,
      lifecycleRules: [{ id: 'expire-canary-artifacts', expiration: retention }],
      removalPolicy: canary.autoDeleteArtifacts ? RemovalPolicy.DESTROY : RemovalPolicy.RETAIN,
      autoDeleteObjects: canary.autoDeleteArtifacts,
    });

    const result = new synthetics.Canary(this, 'Canary', {
      runtime: CANARY_RUNTIME,
      test: synthetics.Test.custom({
        code: synthetics.Code.fromInline(fs.readFileSync(CANARY_SCRIPT, 'utf8')),
        handler: 'index.handler',
      }),
      schedule: synthetics.Schedule.rate(Duration.minutes(canary.scheduleMinutes)),
      timeout: Duration.seconds(canary.timeoutSeconds),
      environmentVariables: {
        BRANCHBOOK_BASE_URL: props.url,
        BRANCHBOOK_BRANCH_CODE: canary.branchCode,
        BRANCHBOOK_SERVICE_CODE: canary.serviceCode,
        BRANCHBOOK_TIME_ZONE: BRANCH_TIME_ZONE,
      },
      artifactsBucketLocation: { bucket },
      successRetentionPeriod: retention,
      failureRetentionPeriod: retention,
      // Deleting the canary deletes the Lambda function Synthetics made for it.
      provisionedResourceCleanup: true,
      startAfterCreation: true,
    });

    Validations.of(bucket).acknowledge({
      id: 'AwsSolutions-S1',
      reason: 'Only the canary writes to this bucket, its own run reports, which expire with the lifecycle rule; access logs would need a second bucket to record that one writer.',
    });
    const stack = Stack.of(this);
    Validations.of(result).acknowledge(
      {
        id: 'AwsSolutions-IAM5[Resource::*]',
        reason: 'CDK\'s canary role: s3:ListAllMyBuckets accepts no resource, and cloudwatch:PutMetricData is limited to the CloudWatchSynthetics namespace by its condition.',
      },
      {
        id: `AwsSolutions-IAM5[Resource::<${stack.getLogicalId(bucket.node.defaultChild as s3.CfnBucket)}.Arn>/*]`,
        reason: 'The canary writes its run reports under keys it names per run, in its own bucket only.',
      },
      {
        id: `AwsSolutions-IAM5[Resource::${nagArnPrefix(stack, 'logs')}:log-group:/aws/lambda/cwsyn-*]`,
        reason: 'Synthetics names the canary\'s Lambda function, and so its log group, cwsyn-<name>-<random id> when it creates it; the prefix is the narrowest name known in advance.',
      },
    );
    return result;
  }

  /**
   * One dashboard for the environment, named after its resource prefix.
   *
   * @param props - the resources
   * @param app - the app's metrics
   * @param alarms - every alarm, for the status widget
   */
  private createDashboard(
    props: ObservabilityProps,
    app: AppMetricSet,
    alarms: cloudwatch.IAlarm[],
  ): cloudwatch.Dashboard {
    const { config, loadBalancer, targetGroup, database, service } = props;
    const { monitoring } = config;
    const resourcePeriod = Duration.minutes(monitoring.resourcePeriodMinutes);
    const heading = (markdown: string): cloudwatch.TextWidget => new cloudwatch.TextWidget({ markdown, width: 24, height: 1 });
    const graph = (title: string, left: cloudwatch.IMetric[], extra: Partial<cloudwatch.GraphWidgetProps> = {}): cloudwatch.GraphWidget =>
      new cloudwatch.GraphWidget({ title, left, width: 6, height: 6, ...extra });
    const line = (value: number, label: string): cloudwatch.HorizontalAnnotation => ({ value, label });

    const lb = loadBalancer.metrics;
    const latency = (percentile: number): cloudwatch.Metric =>
      lb.targetResponseTime({ statistic: cloudwatch.Stats.p(percentile), period: resourcePeriod, label: `p${String(percentile)}` });

    const dashboard = new cloudwatch.Dashboard(this, 'Dashboard', {
      dashboardName: config.resourcePrefix,
      defaultInterval: Duration.hours(3),
    });
    dashboard.addWidgets(new cloudwatch.AlarmStatusWidget({ title: 'Alarms', alarms, width: 24, height: 4 }));

    dashboard.addWidgets(heading('## Golden signals: traffic, errors, latency, saturation'));
    dashboard.addWidgets(
      graph('Requests', [lb.requestCount({ statistic: cloudwatch.Stats.SUM, period: resourcePeriod, label: 'Requests' })]),
      graph('5xx answers', [
        lb.httpCodeTarget(elbv2.HttpCodeTarget.TARGET_5XX_COUNT, { statistic: cloudwatch.Stats.SUM, period: resourcePeriod, label: 'From the app' }),
        lb.httpCodeElb(elbv2.HttpCodeElb.ELB_5XX_COUNT, { statistic: cloudwatch.Stats.SUM, period: resourcePeriod, label: 'From the load balancer' }),
      ]),
      graph('Response time (s)', [latency(50), latency(99)], {
        leftAnnotations: [line(monitoring.responseTimeP99Milliseconds / 1000, 'p99 alarm')],
      }),
      graph('Healthy tasks', [
        targetGroup.metrics.healthyHostCount({ statistic: cloudwatch.Stats.MINIMUM, period: Duration.minutes(1), label: 'Healthy' }),
        targetGroup.metrics.unhealthyHostCount({ statistic: cloudwatch.Stats.MAXIMUM, period: Duration.minutes(1), label: 'Unhealthy' }),
      ]),
    );

    dashboard.addWidgets(heading('## Outbox and SMS delivery'));
    dashboard.addWidgets(
      graph('Pending and FAILED messages', [app.outboxPending.with({ label: 'Pending' }), app.outboxFailed.with({ label: 'FAILED' })]),
      graph('Oldest pending message (s)', [app.outboxOldestPendingAge.with({ label: 'Oldest pending' })], {
        leftAnnotations: [line(monitoring.outboxOldestPendingSeconds, 'Alarm')],
      }),
      graph('Seconds since the dispatcher\'s last tick', [app.outboxLastTickAge.with({ label: 'Last tick age' })], {
        leftAnnotations: [line(monitoring.dispatcherStalledSeconds, 'Stalled')],
      }),
      graph('SMS circuit breaker open', [app.smsCircuitOpen.with({ label: 'Open (1)' })], { leftYAxis: { min: 0, max: 1 } }),
    );

    dashboard.addWidgets(heading('## Database'));
    const connectionLimit = Math.floor((config.database.connectionBudget * monitoring.databaseConnectionsPercent) / 100);
    dashboard.addWidgets(
      graph('CPU (%)', [database.metricCPUUtilization({ period: resourcePeriod, label: 'Average' })], {
        leftAnnotations: [line(monitoring.databaseCpuPercent, 'Alarm')],
      }),
      graph('Connections', [database.metricDatabaseConnections({ period: resourcePeriod, statistic: cloudwatch.Stats.MAXIMUM, label: 'Maximum' })], {
        leftAnnotations: [line(connectionLimit, 'Alarm'), line(config.database.connectionBudget, 'Budget')],
      }),
      graph('Freeable memory (bytes)', [database.metricFreeableMemory({ period: resourcePeriod, statistic: cloudwatch.Stats.MINIMUM, label: 'Minimum' })]),
      graph('Free storage (bytes)', [database.metricFreeStorageSpace({ period: resourcePeriod, statistic: cloudwatch.Stats.MINIMUM, label: 'Minimum' })]),
    );

    dashboard.addWidgets(heading('## Service'));
    const tasks: cloudwatch.IMetric[] = [
      targetGroup.metrics.healthyHostCount({ statistic: cloudwatch.Stats.MINIMUM, period: Duration.minutes(1), label: 'Healthy targets' }),
    ];
    if (config.service.containerInsights) {
      // Container Insights alone publishes the running task count; without it the healthy targets stand in.
      tasks.push(
        new cloudwatch.Metric({
          namespace: 'ECS/ContainerInsights',
          metricName: 'RunningTaskCount',
          dimensionsMap: { ClusterName: service.cluster.clusterName, ServiceName: service.serviceName },
          statistic: cloudwatch.Stats.MAXIMUM,
          period: Duration.minutes(1),
          label: 'Running tasks',
        }),
      );
    }
    dashboard.addWidgets(
      graph('CPU (%)', [
        service.metricCpuUtilization({ period: resourcePeriod, statistic: cloudwatch.Stats.AVERAGE, label: 'Average' }),
        service.metricCpuUtilization({ period: resourcePeriod, statistic: cloudwatch.Stats.MAXIMUM, label: 'Busiest task' }),
      ], { leftAnnotations: [line(monitoring.serviceCpuPercent, 'Alarm')] }),
      graph('Memory (%)', [
        service.metricMemoryUtilization({ period: resourcePeriod, statistic: cloudwatch.Stats.AVERAGE, label: 'Average' }),
        service.metricMemoryUtilization({ period: resourcePeriod, statistic: cloudwatch.Stats.MAXIMUM, label: 'Busiest task' }),
      ], { leftAnnotations: [line(monitoring.serviceMemoryPercent, 'Alarm')] }),
      graph('Tasks', tasks),
    );

    dashboard.addWidgets(heading('## WAF'));
    const waf = wafRuleMetricNames(props.webAcl);
    const wafMetric = (metricName: string, rule: string, label: string): cloudwatch.Metric =>
      new cloudwatch.Metric({
        namespace: 'AWS/WAFV2',
        metricName,
        dimensionsMap: { WebACL: waf.webAcl, Region: Stack.of(this).region, Rule: rule },
        statistic: cloudwatch.Stats.SUM,
        period: resourcePeriod,
        label,
      });
    dashboard.addWidgets(
      graph('Allowed and blocked requests', [
        wafMetric('AllowedRequests', WAF_ALL_RULES, 'Allowed'),
        wafMetric('BlockedRequests', WAF_ALL_RULES, 'Blocked'),
      ]),
      graph('Blocked, by rule', waf.blocking.map((rule) => wafMetric('BlockedRequests', rule, rule)), { width: 12 }),
      graph('CAPTCHA', waf.captcha.flatMap((rule) => [
        wafMetric('CaptchaRequests', rule, `${rule}: asked`),
        wafMetric('RequestsWithValidCaptchaToken', rule, `${rule}: solved`),
      ])),
    );

    if (this.canary !== undefined) {
      dashboard.addWidgets(heading('## Canary'));
      dashboard.addWidgets(
        graph('Successful runs (%)', [this.canary.metricSuccessPercent({ period: Duration.minutes(config.canary.scheduleMinutes) })], {
          leftYAxis: { min: 0, max: 100 },
        }),
        graph('Run duration (ms)', [this.canary.metricDuration({ period: Duration.minutes(config.canary.scheduleMinutes) })]),
      );
    }
    return dashboard;
  }
}

/** The `Rule` dimension of WAF's web-ACL-wide metrics. */
const WAF_ALL_RULES = 'ALL';

/** The metric names a web ACL publishes under, read from its own visibility settings. */
interface WafRuleMetricNames {
  /** The web ACL's own metric name (the `WebACL` dimension). */
  readonly webAcl: string;
  /** The rules that block, rate-based and managed rule groups alike. */
  readonly blocking: string[];
  /** The rules that ask for a CAPTCHA. */
  readonly captcha: string[];
}

/**
 * Reads the metric names from the web ACL itself, so the dashboard follows its rules rather than a second list.
 *
 * @param webAcl - the web ACL, built with literal (not tokenised) rules
 * @throws {Error} when the rules or their visibility settings are tokens, which synthesis cannot read
 */
export function wafRuleMetricNames(webAcl: wafv2.CfnWebACL): WafRuleMetricNames {
  const literal = <T>(value: T | object): T => {
    if (Tokenization.isResolvable(value)) {
      throw new Error('the web ACL\'s rules must be literal for the dashboard to read their metric names');
    }
    return value as T;
  };
  const visibility = literal<wafv2.CfnWebACL.VisibilityConfigProperty>(webAcl.visibilityConfig);
  const rules = literal<(wafv2.CfnWebACL.RuleProperty | object)[]>(webAcl.rules ?? []).map((rule) =>
    literal<wafv2.CfnWebACL.RuleProperty>(rule),
  );
  const blocking: string[] = [];
  const captcha: string[] = [];
  for (const rule of rules) {
    const name = literal<wafv2.CfnWebACL.VisibilityConfigProperty>(rule.visibilityConfig).metricName;
    const action = rule.action === undefined ? undefined : literal<wafv2.CfnWebACL.RuleActionProperty>(rule.action);
    (action?.captcha === undefined ? blocking : captcha).push(name);
  }
  return { webAcl: visibility.metricName, blocking, captcha };
}
