/**
 * The configuration of each deployed environment, read from `infra/config/<name>.json` and checked before any stack
 * is built. Every size, count, limit, threshold, retention period, region, zone, address range and name the stacks
 * use comes from here, so the stacks hold only protocol facts, and a mistake fails synthesis with the file and the
 * field it is in, never a deployment.
 *
 * The validator is hand-written rather than a schema library: the shapes are small, and it adds no dependency.
 *
 * @packageDocumentation
 */
import * as fs from 'node:fs';
import * as path from 'node:path';
import * as logs from 'aws-cdk-lib/aws-logs';

/** The environments the CDK app can build, selected with `BRANCHBOOK_ENV`. */
export const ENVIRONMENT_NAMES = ['staging', 'production'] as const;

/** One of {@link ENVIRONMENT_NAMES}. */
export type EnvironmentName = (typeof ENVIRONMENT_NAMES)[number];

/** Where the JSON files live: `infra/config`. */
export const CONFIG_DIRECTORY = path.join(__dirname, '..', 'config');

/** The SMS gateways the app knows (`BRANCHBOOK_SMS_GATEWAY`). */
export const SMS_GATEWAYS = ['aws', 'simulated'] as const;

/**
 * The variables the stacks compute themselves, so `appEnvironment` may not set them: a second source for the same
 * setting would let the two disagree.
 */
export const COMPUTED_VARIABLES: readonly string[] = [
  'DB_URL',
  'DB_USER',
  'DB_PASSWORD',
  'SPRING_DATASOURCE_DRIVER_CLASS_NAME',
  'SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE',
  'PUBLIC_BASE_URL',
  'BRANCHBOOK_SMS_GATEWAY',
  'BRANCHBOOK_SMS_AWS_REGION',
  'BRANCHBOOK_SMS_AWS_PROTECT_CONFIGURATION_ID',
  'BRANCHBOOK_SMS_AWS_DRY_RUN',
  'BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP',
  'BRANCHBOOK_SMS_RECIPIENT_CAP_KEY',
  'BRANCHBOOK_CAPTCHA_SCRIPT_URL',
  'BRANCHBOOK_CAPTCHA_API_KEY',
  'BRANCHBOOK_MIGRATE_ONLY',
  'BRANCHBOOK_DB_APP_ROLE',
  'SPRING_MAIN_WEB_APPLICATION_TYPE',
  'BRANCHBOOK_METRICS_CLOUDWATCH_ENABLED',
  'BRANCHBOOK_METRICS_CLOUDWATCH_NAMESPACE',
  'BRANCHBOOK_METRICS_CLOUDWATCH_STEP',
];

/** The retention periods CloudWatch Logs accepts, in days. */
export const LOG_RETENTION_DAYS: readonly number[] = [
  1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653,
];

/** CDK's names for the retention periods, by number of days. */
const RETENTION_DAYS = new Map<number, logs.RetentionDays>(
  Object.values(logs.RetentionDays)
    .filter((value): value is logs.RetentionDays => typeof value === 'number')
    .map((value): [number, logs.RetentionDays] => [value, value]),
);

/**
 * CDK's retention period for a number of days the configuration has already checked.
 *
 * @param days - one of {@link LOG_RETENTION_DAYS}
 * @throws {Error} when CloudWatch Logs offers no such period
 */
export function retentionDays(days: number): logs.RetentionDays {
  const retention = RETENTION_DAYS.get(days);
  if (retention === undefined) {
    throw new Error(`CloudWatch Logs cannot keep logs for ${String(days)} days`);
  }
  return retention;
}

/** The evaluation windows a WAF rate-based rule accepts, in seconds. */
export const WAF_RATE_WINDOWS: readonly number[] = [60, 120, 300, 600];

/** The lowest limit a WAF rate-based rule accepts. */
export const WAF_MIN_RATE_LIMIT = 10;

/**
 * The longest public domain the confirmation SMS allows: `https://` and the domain must fit in 28 characters (BR-18).
 */
export const MAX_DOMAIN_LENGTH = 20;

/** The Route 53 hosted zone a domain lives in. Both environments share the zone of `branchbook.click`. */
export interface DomainConfig {
  /** The public domain, for example `branchbook.click` or `stg.branchbook.click`. */
  readonly name: string;
  /** The hosted zone's name; the domain is it or a name under it. */
  readonly hostedZoneName: string;
  /** The hosted zone's ID. Public, not a secret, and written out so that synthesis needs no lookup. */
  readonly hostedZoneId: string;
}

/** The VPC. */
export interface NetworkConfig {
  /** The VPC's address range, for example `10.40.0.0/16`. */
  readonly cidr: string;
  /** The prefix length of every subnet, for example 24. */
  readonly subnetCidrMask: number;
  /** How many NAT gateways give the tasks' private subnets their way out: one, or one per availability zone. */
  readonly natGateways: number;
}

/** The RDS for PostgreSQL instance. */
export interface DatabaseConfig {
  /** The instance class without the `db.` prefix, for example `t4g.micro`. */
  readonly instanceClass: string;
  /** Whether RDS keeps a synchronous standby in the second zone and fails over to it. */
  readonly multiAz: boolean;
  /** The storage allocated at creation, in GiB. */
  readonly allocatedStorageGiB: number;
  /** The most storage autoscaling may grow to, in GiB. */
  readonly maxAllocatedStorageGiB: number;
  /** How many days of automated backups RDS keeps (0 to 35). */
  readonly backupRetentionDays: number;
  /** Whether RDS refuses to delete the instance. */
  readonly deletionProtection: boolean;
  /**
   * How many connections the app and the migration may hold: the instance class's `max_connections`, which RDS sets
   * to `LEAST(DBInstanceClassMemory/9531392, 5000)`, less the slots it reserves for superusers. Checked against the
   * worst case at synthesis (see {@link connectionPeak}).
   */
  readonly connectionBudget: number;
  /** Connections kept free for an operator's session and RDS's own monitoring. */
  readonly connectionHeadroom: number;
  /** The runtime role the tasks sign in as with IAM authentication; migration V9 creates it. */
  readonly appUser: string;
}

/** The Fargate service. */
export interface ServiceConfig {
  /** CPU units per task (1024 is one vCPU). */
  readonly cpu: number;
  /** Memory per task, in MiB; it must be a combination Fargate offers for {@link cpu}. */
  readonly memoryMiB: number;
  /** The fewest tasks autoscaling keeps. */
  readonly minTasks: number;
  /** The most tasks autoscaling starts. */
  readonly maxTasks: number;
  /** During a deployment, the share of the desired tasks that must stay healthy. */
  readonly minHealthyPercent: number;
  /** During a deployment, how far above the desired tasks ECS may go while new ones start. */
  readonly maxHealthyPercent: number;
  /** The most database connections each task's pool opens (`spring.datasource.hikari.maximum-pool-size`). */
  readonly dbPoolSize: number;
  /** Autoscaling keeps the service's average CPU near this percentage. */
  readonly cpuTargetPercent: number;
  /** Autoscaling keeps each task's share of the load balancer's requests near this many a minute. */
  readonly requestsPerTargetPerMinute: number;
  /** How long autoscaling waits after adding tasks before it adds more, in seconds. */
  readonly scaleOutCooldownSeconds: number;
  /** How long autoscaling waits after removing tasks before it removes more, in seconds. */
  readonly scaleInCooldownSeconds: number;
  /** Whether the cluster records Container Insights metrics, which CloudWatch charges for. */
  readonly containerInsights: boolean;
  /** How long a stopping task has to finish its requests before it is killed, in seconds. */
  readonly stopTimeoutSeconds: number;
  /** How long the load balancer drains a task before it is removed, in seconds. */
  readonly deregistrationDelaySeconds: number;
  /** How long ECS ignores failing health checks after a task starts, in seconds. */
  readonly healthCheckGracePeriodSeconds: number;
}

/** The load balancer's and the container's health checks. */
export interface HealthCheckConfig {
  /** How often the load balancer calls `/readyz`, in seconds. */
  readonly intervalSeconds: number;
  /** Consecutive passes before the load balancer sends a task traffic. */
  readonly healthyThreshold: number;
  /** Consecutive failures before the load balancer stops sending a task traffic. */
  readonly unhealthyThreshold: number;
  /** How often ECS calls `/livez` inside the container, in seconds. */
  readonly containerIntervalSeconds: number;
  /** How long one `/livez` call may take, in seconds. */
  readonly containerTimeoutSeconds: number;
  /** Consecutive `/livez` failures before ECS replaces the task. */
  readonly containerRetries: number;
  /** How long ECS ignores `/livez` failures while the JVM starts, in seconds. */
  readonly containerStartPeriodSeconds: number;
}

/** The one-off task that migrates the schema before the service is updated. */
export interface MigrationConfig {
  /** CPU units of the migration task. */
  readonly cpu: number;
  /** Memory of the migration task, in MiB. */
  readonly memoryMiB: number;
  /** The most database connections the migration task opens. */
  readonly dbPoolSize: number;
  /** How long the deployment waits for the migration before failing, in minutes. */
  readonly timeoutMinutes: number;
}

/** How long each kind of log is kept, in days; each must be a period CloudWatch Logs accepts. */
export interface LogsConfig {
  /** The application's log group. */
  readonly appRetentionDays: number;
  /** WAF's request log. */
  readonly wafRetentionDays: number;
  /** The VPC's rejected-traffic flow log. */
  readonly flowLogRetentionDays: number;
}

/** The web ACL's limits. Every limit counts requests from one address in its window. */
export interface WafConfig {
  /** Booking and cancellation `POST`s allowed per address in {@link bookingWindowSeconds}. */
  readonly bookingLimit: number;
  /** The window of {@link bookingLimit}, in seconds. */
  readonly bookingWindowSeconds: number;
  /** Appointment lookups (`GET /api/v1/appointments/...`) allowed per address in {@link lookupWindowSeconds}. */
  readonly lookupLimit: number;
  /** The window of {@link lookupLimit}, in seconds. */
  readonly lookupWindowSeconds: number;
  /** Requests of any kind allowed per address in {@link requestWindowSeconds}. */
  readonly requestLimit: number;
  /** The window of {@link requestLimit}, in seconds. */
  readonly requestWindowSeconds: number;
  /** How long a solved CAPTCHA is accepted before WAF asks again, in seconds (60 to 259200). */
  readonly captchaImmunitySeconds: number;
  /**
   * Whether requests carrying the synthetic-traffic token skip the CAPTCHA, so the pipeline's end-to-end tests can
   * book. Staging only: production must never have a bypass.
   */
  readonly syntheticTrafficBypass: boolean;
}

/** How the app sends SMS. */
export interface SmsConfig {
  /** `aws` sends real SMS through AWS End User Messaging; `simulated` sends none. */
  readonly gateway: (typeof SMS_GATEWAYS)[number];
  /** The most SMS one number outside the demo block is sent in a day; 0 turns the cap off. */
  readonly recipientDailyCap: number;
  /** The one country the protect configuration allows (ISO 3166-1 alpha-2). */
  readonly allowedCountry: string;
}

/** The alarms' thresholds. */
export interface AlarmsConfig {
  /** 5xx answers from the app, within {@link serverErrorPeriodMinutes}, that raise the alarm. */
  readonly serverErrorThreshold: number;
  /** The window of {@link serverErrorThreshold}, in minutes. */
  readonly serverErrorPeriodMinutes: number;
  /** Minutes with no healthy task before the alarm is raised. */
  readonly noHealthyTaskMinutes: number;
  /** Free database storage, in GiB, below which the alarm is raised. */
  readonly databaseFreeStorageGiB: number;
}

/**
 * The app's own metrics in CloudWatch, and the alarms on them and on AWS's metrics of the load balancer, the database
 * and the service (infra/lib/constructs/observability.ts).
 */
export interface MonitoringConfig {
  /** The CloudWatch namespace the app exports its meters to, for example `BranchBook/production`. */
  readonly metricsNamespace: string;
  /** How often the app exports its meters, in seconds; also the period of the alarms on them. */
  readonly metricsStepSeconds: number;
  /** The age, in seconds, of the oldest message still waiting to be sent above which the alarm is raised. */
  readonly outboxOldestPendingSeconds: number;
  /** How many messages may turn FAILED within {@link outboxFailedPeriodMinutes} before the alarm is raised. */
  readonly outboxFailedIncrease: number;
  /** The window of {@link outboxFailedIncrease}, in minutes. */
  readonly outboxFailedPeriodMinutes: number;
  /** Seconds since the dispatcher's last tick above which it counts as stalled. */
  readonly dispatcherStalledSeconds: number;
  /** Consecutive export periods the dispatcher must look stalled, or be silent, before the alarm is raised. */
  readonly dispatcherStalledPeriods: number;
  /** The load balancer's 99th-percentile response time, in milliseconds, above which the alarm is raised. */
  readonly responseTimeP99Milliseconds: number;
  /** The period of the response-time alarm, in minutes. */
  readonly responseTimePeriodMinutes: number;
  /** Consecutive periods over {@link responseTimeP99Milliseconds} before the alarm is raised. */
  readonly responseTimePeriods: number;
  /** The database's average CPU, in percent, above which the alarm is raised. */
  readonly databaseCpuPercent: number;
  /** The share of the database's connection budget, in percent, above which the alarm is raised. */
  readonly databaseConnectionsPercent: number;
  /** The database's freeable memory, in MiB, below which the alarm is raised. */
  readonly databaseFreeableMemoryMiB: number;
  /** The service's average CPU, in percent, above which the alarm is raised. */
  readonly serviceCpuPercent: number;
  /** The busiest task's memory, in percent of its reservation, above which the alarm is raised. */
  readonly serviceMemoryPercent: number;
  /** The period of the database and service alarms, in minutes. */
  readonly resourcePeriodMinutes: number;
  /** Consecutive periods over a database or service threshold before its alarm is raised. */
  readonly resourcePeriods: number;
}

/** The CloudWatch Synthetics canary that calls the environment from outside, as a customer would. */
export interface CanaryConfig {
  /** Whether the canary exists. */
  readonly enabled: boolean;
  /** How often it runs, in minutes (1 to 60). */
  readonly scheduleMinutes: number;
  /** How long one run may take, in seconds; at most the schedule. */
  readonly timeoutSeconds: number;
  /** The share of successful runs, in percent, below which a period counts as failing. */
  readonly successPercentThreshold: number;
  /** Consecutive failing periods before the alarm is raised. */
  readonly failingPeriods: number;
  /** How long run results and the artifacts in S3 are kept, in days. */
  readonly artifactRetentionDays: number;
  /** Whether deleting the stack empties and deletes the artifacts bucket; otherwise the bucket is kept. */
  readonly autoDeleteArtifacts: boolean;
  /** The branch whose availability the canary reads. */
  readonly branchCode: string;
  /** The service whose availability the canary reads; the branch must offer it. */
  readonly serviceCode: string;
}

/** Everything one environment needs, as read from `infra/config/<name>.json`. */
export interface EnvironmentConfig {
  /** The environment's name; it must match the file's name. */
  readonly name: EnvironmentName;
  /** The prefix of every stack ID: `Branchbook` keeps production's existing stacks, `BranchbookStaging` for staging. */
  readonly stackIdPrefix: string;
  /** The prefix of every physical name: secrets, log groups and SSM parameters (`branchbook`, `branchbook-staging`). */
  readonly resourcePrefix: string;
  /** The AWS Region, for example `af-south-1`. */
  readonly region: string;
  /** The two availability zones, written out so that synthesis needs no lookup. */
  readonly availabilityZones: readonly string[];
  /** The public domain and its hosted zone. */
  readonly domain: DomainConfig;
  /** The VPC. */
  readonly network: NetworkConfig;
  /** The database. */
  readonly database: DatabaseConfig;
  /** The Fargate service. */
  readonly service: ServiceConfig;
  /** The health checks. */
  readonly healthCheck: HealthCheckConfig;
  /** The migration task. */
  readonly migration: MigrationConfig;
  /** Log retention. */
  readonly logs: LogsConfig;
  /** The web ACL. */
  readonly waf: WafConfig;
  /** SMS. */
  readonly sms: SmsConfig;
  /** Alarm thresholds. */
  readonly alarms: AlarmsConfig;
  /** The app's metrics and the alarms on them and on AWS's metrics. */
  readonly monitoring: MonitoringConfig;
  /** The outside-in canary. */
  readonly canary: CanaryConfig;
  /**
   * The app's own settings, passed to every task as environment variables. Secrets never go here: they come from
   * Secrets Manager. Neither do the variables in {@link COMPUTED_VARIABLES}.
   */
  readonly appEnvironment: Readonly<Record<string, string>>;
}

/** The container registry, which both environments pull the same image from. */
export interface RegistryConfig {
  /** The Region of the ECR repository; every environment must run there. */
  readonly region: string;
  /** The ECR repository's name. */
  readonly repositoryName: string;
  /** How many of the newest images the lifecycle rule keeps. */
  readonly imagesToKeep: number;
}

const REGION = /^[a-z]{2}(-[a-z]+)+-\d$/;
const DOMAIN = /^(?=.{1,253}$)([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$/;
const PREFIX = /^[a-z][a-z0-9-]{1,30}$/;
const STACK_PREFIX = /^[A-Z][A-Za-z0-9]{1,40}$/;
const VARIABLE = /^[A-Z][A-Z0-9_]*$/;
const SECRET_LIKE = /(PASSWORD|SECRET|TOKEN|CREDENTIALS?|_KEY)$/;
const CIDR = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\/(\d{1,2})$/;
// CloudWatch reserves namespaces that start with AWS/ for AWS's own metrics.
const METRICS_NAMESPACE = /^(?!AWS\/)[A-Za-z0-9][A-Za-z0-9/_.#:-]{0,254}$/;

/**
 * The export steps the alarms accept, in seconds: CloudWatch's standard resolution is a minute, and an alarm's period
 * must be a multiple of it.
 */
export const METRICS_STEPS: readonly number[] = [60, 120, 300];

/**
 * One JSON object being read. It records which keys were read, so {@link end} can refuse a key nobody reads: a
 * misspelt field would otherwise be ignored in silence, and its default used.
 */
class Section {
  private readonly seen = new Set<string>();

  constructor(
    private readonly source: string,
    private readonly path: string,
    private readonly value: Record<string, unknown>,
  ) {}

  static root(raw: unknown, source: string): Section {
    if (!isObject(raw)) {
      throw new Error(`${source}: the file must hold a JSON object`);
    }
    return new Section(source, '', raw);
  }

  fail(key: string, message: string): never {
    throw new Error(`${this.source}: ${this.path}${key} ${message}`);
  }

  private get(key: string): unknown {
    this.seen.add(key);
    if (!(key in this.value)) {
      this.fail(key, 'is missing');
    }
    return this.value[key];
  }

  string(key: string, pattern: RegExp, description: string): string {
    const value = this.get(key);
    if (typeof value !== 'string' || !pattern.test(value)) {
      this.fail(key, `must be ${description}, got ${JSON.stringify(value)}`);
    }
    return value;
  }

  integer(key: string, min: number, max: number): number {
    const value = this.get(key);
    if (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > max) {
      this.fail(key, `must be a whole number from ${String(min)} to ${String(max)}, got ${JSON.stringify(value)}`);
    }
    return value;
  }

  oneOfNumbers(key: string, allowed: readonly number[]): number {
    const value = this.get(key);
    if (typeof value !== 'number' || !allowed.includes(value)) {
      this.fail(key, `must be one of ${allowed.join(', ')}, got ${JSON.stringify(value)}`);
    }
    return value;
  }

  boolean(key: string): boolean {
    const value = this.get(key);
    if (typeof value !== 'boolean') {
      this.fail(key, `must be true or false, got ${JSON.stringify(value)}`);
    }
    return value;
  }

  oneOf<T extends string>(key: string, allowed: readonly T[]): T {
    const value = this.get(key);
    if (typeof value !== 'string' || !(allowed as readonly string[]).includes(value)) {
      this.fail(key, `must be one of ${allowed.join(', ')}, got ${JSON.stringify(value)}`);
    }
    return value as T;
  }

  strings(key: string, count: number, pattern: RegExp, description: string): string[] {
    const value = this.get(key);
    if (!Array.isArray(value) || value.length !== count || !value.every((v) => typeof v === 'string' && pattern.test(v))) {
      this.fail(key, `must be a list of exactly ${String(count)} ${description}, got ${JSON.stringify(value)}`);
    }
    return value as string[];
  }

  stringMap(key: string): Record<string, string> {
    const value = this.get(key);
    if (!isObject(value)) {
      this.fail(key, 'must be an object of names to string values');
    }
    for (const [name, setting] of Object.entries(value)) {
      if (!VARIABLE.test(name)) {
        this.fail(`${key}.${name}`, 'is not an environment variable name (A-Z, 0-9 and _)');
      }
      if (typeof setting !== 'string') {
        this.fail(`${key}.${name}`, `must be a string, got ${JSON.stringify(setting)}`);
      }
    }
    return value as Record<string, string>;
  }

  section<T>(key: string, read: (section: Section) => T): T {
    const value = this.get(key);
    if (!isObject(value)) {
      this.fail(key, 'must be an object');
    }
    const section = new Section(this.source, `${this.path}${key}.`, value);
    const result = read(section);
    section.end();
    return result;
  }

  end(): void {
    const unknown = Object.keys(this.value).filter((key) => !this.seen.has(key));
    if (unknown.length > 0) {
      this.fail(unknown[0], 'is not a known setting; check its spelling');
    }
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Reads and checks one environment's configuration.
 *
 * @param raw - the parsed JSON
 * @param source - where it came from, named in every error message
 * @throws {Error} naming the file and the field, on the first missing, mistyped, out-of-range or unknown value, or on
 *   a combination {@link checkEnvironment} refuses
 */
export function parseEnvironmentConfig(raw: unknown, source: string): EnvironmentConfig {
  const root = Section.root(raw, source);
  const config: EnvironmentConfig = {
    name: root.oneOf('name', ENVIRONMENT_NAMES),
    stackIdPrefix: root.string('stackIdPrefix', STACK_PREFIX, 'a PascalCase stack ID prefix such as Branchbook'),
    resourcePrefix: root.string('resourcePrefix', PREFIX, 'a lower-case name prefix such as branchbook'),
    region: root.string('region', REGION, 'an AWS Region name such as af-south-1'),
    availabilityZones: root.strings('availabilityZones', 2, /^[a-z]{2}(-[a-z]+)+-\d[a-z]$/, 'availability zone names'),
    domain: root.section('domain', (s) => ({
      name: s.string('name', DOMAIN, 'a lower-case domain name'),
      hostedZoneName: s.string('hostedZoneName', DOMAIN, 'a lower-case domain name'),
      hostedZoneId: s.string('hostedZoneId', /^Z[A-Z0-9]{5,32}$/, 'a Route 53 hosted zone ID'),
    })),
    network: root.section('network', (s) => ({
      cidr: s.string('cidr', CIDR, 'an IPv4 CIDR block such as 10.40.0.0/16'),
      subnetCidrMask: s.integer('subnetCidrMask', 16, 28),
      natGateways: s.integer('natGateways', 1, 2),
    })),
    database: root.section('database', (s) => ({
      instanceClass: s.string('instanceClass', /^[a-z][a-z0-9-]*\.[a-z0-9]+$/, 'an instance class such as t4g.micro'),
      multiAz: s.boolean('multiAz'),
      allocatedStorageGiB: s.integer('allocatedStorageGiB', 20, 65536),
      maxAllocatedStorageGiB: s.integer('maxAllocatedStorageGiB', 20, 65536),
      backupRetentionDays: s.integer('backupRetentionDays', 1, 35),
      deletionProtection: s.boolean('deletionProtection'),
      connectionBudget: s.integer('connectionBudget', 10, 5000),
      connectionHeadroom: s.integer('connectionHeadroom', 0, 1000),
      appUser: s.string('appUser', /^[a-z_][a-z0-9_]{0,62}$/, 'a PostgreSQL role name'),
    })),
    service: root.section('service', (s) => ({
      cpu: s.oneOfNumbers('cpu', Object.keys(FARGATE_MEMORY).map(Number)),
      memoryMiB: s.integer('memoryMiB', 512, 30720),
      minTasks: s.integer('minTasks', 1, 50),
      maxTasks: s.integer('maxTasks', 1, 50),
      minHealthyPercent: s.integer('minHealthyPercent', 0, 100),
      maxHealthyPercent: s.integer('maxHealthyPercent', 100, 200),
      dbPoolSize: s.integer('dbPoolSize', 1, 100),
      cpuTargetPercent: s.integer('cpuTargetPercent', 10, 90),
      requestsPerTargetPerMinute: s.integer('requestsPerTargetPerMinute', 1, 1_000_000),
      scaleOutCooldownSeconds: s.integer('scaleOutCooldownSeconds', 0, 3600),
      scaleInCooldownSeconds: s.integer('scaleInCooldownSeconds', 0, 3600),
      containerInsights: s.boolean('containerInsights'),
      stopTimeoutSeconds: s.integer('stopTimeoutSeconds', 2, 120),
      deregistrationDelaySeconds: s.integer('deregistrationDelaySeconds', 0, 3600),
      healthCheckGracePeriodSeconds: s.integer('healthCheckGracePeriodSeconds', 0, 2_147_483_647),
    })),
    healthCheck: root.section('healthCheck', (s) => ({
      intervalSeconds: s.integer('intervalSeconds', 5, 300),
      healthyThreshold: s.integer('healthyThreshold', 2, 10),
      unhealthyThreshold: s.integer('unhealthyThreshold', 2, 10),
      containerIntervalSeconds: s.integer('containerIntervalSeconds', 5, 300),
      containerTimeoutSeconds: s.integer('containerTimeoutSeconds', 2, 120),
      containerRetries: s.integer('containerRetries', 1, 10),
      containerStartPeriodSeconds: s.integer('containerStartPeriodSeconds', 0, 300),
    })),
    migration: root.section('migration', (s) => ({
      cpu: s.oneOfNumbers('cpu', Object.keys(FARGATE_MEMORY).map(Number)),
      memoryMiB: s.integer('memoryMiB', 512, 30720),
      dbPoolSize: s.integer('dbPoolSize', 1, 10),
      timeoutMinutes: s.integer('timeoutMinutes', 1, 60),
    })),
    logs: root.section('logs', (s) => ({
      appRetentionDays: s.oneOfNumbers('appRetentionDays', LOG_RETENTION_DAYS),
      wafRetentionDays: s.oneOfNumbers('wafRetentionDays', LOG_RETENTION_DAYS),
      flowLogRetentionDays: s.oneOfNumbers('flowLogRetentionDays', LOG_RETENTION_DAYS),
    })),
    waf: root.section('waf', (s) => ({
      bookingLimit: s.integer('bookingLimit', WAF_MIN_RATE_LIMIT, 2_000_000_000),
      bookingWindowSeconds: s.oneOfNumbers('bookingWindowSeconds', WAF_RATE_WINDOWS),
      lookupLimit: s.integer('lookupLimit', WAF_MIN_RATE_LIMIT, 2_000_000_000),
      lookupWindowSeconds: s.oneOfNumbers('lookupWindowSeconds', WAF_RATE_WINDOWS),
      requestLimit: s.integer('requestLimit', WAF_MIN_RATE_LIMIT, 2_000_000_000),
      requestWindowSeconds: s.oneOfNumbers('requestWindowSeconds', WAF_RATE_WINDOWS),
      captchaImmunitySeconds: s.integer('captchaImmunitySeconds', 60, 259_200),
      syntheticTrafficBypass: s.boolean('syntheticTrafficBypass'),
    })),
    sms: root.section('sms', (s) => ({
      gateway: s.oneOf('gateway', SMS_GATEWAYS),
      recipientDailyCap: s.integer('recipientDailyCap', 0, 1000),
      allowedCountry: s.string('allowedCountry', /^[A-Z]{2}$/, 'an ISO 3166-1 alpha-2 country code'),
    })),
    alarms: root.section('alarms', (s) => ({
      serverErrorThreshold: s.integer('serverErrorThreshold', 1, 1_000_000),
      serverErrorPeriodMinutes: s.integer('serverErrorPeriodMinutes', 1, 60),
      noHealthyTaskMinutes: s.integer('noHealthyTaskMinutes', 1, 60),
      databaseFreeStorageGiB: s.integer('databaseFreeStorageGiB', 1, 65536),
    })),
    monitoring: root.section('monitoring', (s) => ({
      metricsNamespace: s.string('metricsNamespace', METRICS_NAMESPACE, 'a CloudWatch namespace such as BranchBook/production'),
      metricsStepSeconds: s.oneOfNumbers('metricsStepSeconds', METRICS_STEPS),
      outboxOldestPendingSeconds: s.integer('outboxOldestPendingSeconds', 60, 86_400),
      outboxFailedIncrease: s.integer('outboxFailedIncrease', 1, 10_000),
      outboxFailedPeriodMinutes: s.integer('outboxFailedPeriodMinutes', 1, 60),
      dispatcherStalledSeconds: s.integer('dispatcherStalledSeconds', 30, 3600),
      dispatcherStalledPeriods: s.integer('dispatcherStalledPeriods', 1, 60),
      responseTimeP99Milliseconds: s.integer('responseTimeP99Milliseconds', 50, 60_000),
      responseTimePeriodMinutes: s.integer('responseTimePeriodMinutes', 1, 60),
      responseTimePeriods: s.integer('responseTimePeriods', 1, 60),
      databaseCpuPercent: s.integer('databaseCpuPercent', 10, 100),
      databaseConnectionsPercent: s.integer('databaseConnectionsPercent', 10, 100),
      databaseFreeableMemoryMiB: s.integer('databaseFreeableMemoryMiB', 16, 1_048_576),
      serviceCpuPercent: s.integer('serviceCpuPercent', 10, 100),
      serviceMemoryPercent: s.integer('serviceMemoryPercent', 10, 100),
      resourcePeriodMinutes: s.integer('resourcePeriodMinutes', 1, 60),
      resourcePeriods: s.integer('resourcePeriods', 1, 60),
    })),
    canary: root.section('canary', (s) => ({
      enabled: s.boolean('enabled'),
      scheduleMinutes: s.integer('scheduleMinutes', 1, 60),
      timeoutSeconds: s.integer('timeoutSeconds', 15, 840),
      successPercentThreshold: s.integer('successPercentThreshold', 1, 100),
      failingPeriods: s.integer('failingPeriods', 1, 60),
      artifactRetentionDays: s.integer('artifactRetentionDays', 1, 455),
      autoDeleteArtifacts: s.boolean('autoDeleteArtifacts'),
      branchCode: s.string('branchCode', /^[A-Z]{2,8}$/, 'a branch code of 2 to 8 capital letters such as SAN'),
      serviceCode: s.string('serviceCode', /^[A-Z]{2,12}$/, 'a service code of 2 to 12 capital letters such as FICA'),
    })),
    appEnvironment: root.stringMap('appEnvironment'),
  };
  root.end();

  const problems = checkEnvironment(config);
  if (problems.length > 0) {
    throw new Error(`${source}: ${problems.join('; ')}`);
  }
  return config;
}

/**
 * The memory Fargate offers for each CPU size, in MiB: the smallest, the largest, and the step between them.
 * Source: the ECS developer guide, "Task CPU and memory".
 */
const FARGATE_MEMORY: Readonly<Record<number, readonly [number, number, number]>> = {
  256: [512, 2048, 512],
  512: [1024, 4096, 1024],
  1024: [2048, 8192, 1024],
  2048: [4096, 16384, 1024],
  4096: [8192, 30720, 1024],
};

/**
 * The most database connections an environment can hold at once: every task autoscaling may run, multiplied by the
 * extra tasks a deployment starts beside them, each with a full pool, plus the migration task and the headroom.
 *
 * @param config - the environment
 */
export function connectionPeak(config: EnvironmentConfig): number {
  const peakTasks = Math.ceil((config.service.maxTasks * config.service.maxHealthyPercent) / 100);
  return peakTasks * config.service.dbPoolSize + config.migration.dbPoolSize + config.database.connectionHeadroom;
}

/**
 * The combinations no single field can check: each returned string is one problem. Production must survive the
 * loss of a zone and of a careless delete, and must never let synthetic traffic past the CAPTCHA.
 *
 * @param config - the environment, already checked field by field
 */
export function checkEnvironment(config: EnvironmentConfig): string[] {
  const problems: string[] = [];
  const { service, database, network, domain, migration } = config;

  for (const [label, cpu, memory] of [
    ['service', service.cpu, service.memoryMiB],
    ['migration', migration.cpu, migration.memoryMiB],
  ] as const) {
    const [min, max, step] = FARGATE_MEMORY[cpu];
    if (memory < min || memory > max || (memory - min) % step !== 0) {
      problems.push(`${label}.memoryMiB ${String(memory)} is not a size Fargate offers with ${String(cpu)} CPU units`);
    }
  }
  if (service.minTasks > service.maxTasks) {
    problems.push(`service.minTasks (${String(service.minTasks)}) is above service.maxTasks (${String(service.maxTasks)})`);
  }
  const peak = connectionPeak(config);
  if (peak > database.connectionBudget) {
    problems.push(
      `the database's connection budget (${String(database.connectionBudget)}) is below the peak the service can open ` +
        `(${String(peak)}: maxTasks x maxHealthyPercent x dbPoolSize, plus the migration's pool and the headroom)`,
    );
  }
  if (database.maxAllocatedStorageGiB < database.allocatedStorageGiB) {
    problems.push('database.maxAllocatedStorageGiB is below database.allocatedStorageGiB');
  }
  if (network.natGateways > config.availabilityZones.length) {
    problems.push('network.natGateways is more than one per availability zone');
  }
  if (!config.availabilityZones.every((zone) => zone.startsWith(config.region))) {
    problems.push(`every availability zone must be in ${config.region}`);
  }
  if (domain.name.length > MAX_DOMAIN_LENGTH) {
    problems.push(`domain.name must be at most ${String(MAX_DOMAIN_LENGTH)} characters for the SMS link (BR-18): ${domain.name}`);
  }
  if (domain.name !== domain.hostedZoneName && !domain.name.endsWith(`.${domain.hostedZoneName}`)) {
    problems.push(`domain.name ${domain.name} is not in the hosted zone ${domain.hostedZoneName}`);
  }
  const { canary } = config;
  if (canary.timeoutSeconds > canary.scheduleMinutes * 60) {
    problems.push('canary.timeoutSeconds is longer than canary.scheduleMinutes: a run would overlap the next');
  }

  for (const name of Object.keys(config.appEnvironment)) {
    if (COMPUTED_VARIABLES.includes(name)) {
      problems.push(`appEnvironment.${name} is set by the stack itself and may not be configured`);
    } else if (SECRET_LIKE.test(name)) {
      problems.push(`appEnvironment.${name} looks like a secret; secrets come from Secrets Manager, never from config`);
    }
  }
  const app = config.appEnvironment;
  if (app.SPRING_PROFILES_ACTIVE !== 'prod') {
    problems.push('appEnvironment.SPRING_PROFILES_ACTIVE must be "prod" in every deployed environment');
  }
  if (app.BRANCHBOOK_MIGRATE_ON_START !== 'false') {
    problems.push('appEnvironment.BRANCHBOOK_MIGRATE_ON_START must be "false": the migration task migrates, not the service');
  }
  if (app.BRANCHBOOK_INBOX_ENABLED === 'true' && app.BRANCHBOOK_INBOX_REDACT_REFERENCES !== 'true') {
    problems.push('appEnvironment turns the inbox on without BRANCHBOOK_INBOX_REDACT_REFERENCES=true');
  }

  if (config.name === 'production') {
    if (!database.multiAz) {
      problems.push('production must run the database Multi-AZ');
    }
    if (!database.deletionProtection) {
      problems.push('production must turn on the database deletion protection');
    }
    if (config.waf.syntheticTrafficBypass) {
      problems.push('production must not let synthetic traffic skip the CAPTCHA');
    }
    if (service.minTasks < 2) {
      problems.push('production must keep at least two tasks, so a zone can fail');
    }
    if (network.natGateways !== config.availabilityZones.length) {
      problems.push('production must have one NAT gateway per availability zone');
    }
    if (canary.autoDeleteArtifacts) {
      problems.push('production must keep the canary\'s artifacts when its stack is deleted');
    }
  }
  return problems;
}

/**
 * Reads `infra/config/<name>.json`.
 *
 * @param name - the environment, normally `BRANCHBOOK_ENV`
 * @param directory - where the files are; tests point it elsewhere
 * @throws {Error} when the name is not an environment, the file is missing or not JSON, or its content is refused
 */
export function loadEnvironmentConfig(name: string, directory: string = CONFIG_DIRECTORY): EnvironmentConfig {
  if (!(ENVIRONMENT_NAMES as readonly string[]).includes(name)) {
    throw new Error(`BRANCHBOOK_ENV must be one of ${ENVIRONMENT_NAMES.join(', ')}, got "${name}"`);
  }
  const source = path.join(directory, `${name}.json`);
  const config = parseEnvironmentConfig(readJson(source), path.relative(process.cwd(), source));
  if (config.name !== name) {
    throw new Error(`${source}: name is "${config.name}", but the file is for "${name}"`);
  }
  return config;
}

/**
 * Reads and checks `infra/config/registry.json`.
 *
 * @param directory - where the file is; tests point it elsewhere
 */
export function loadRegistryConfig(directory: string = CONFIG_DIRECTORY): RegistryConfig {
  const source = path.join(directory, 'registry.json');
  const root = Section.root(readJson(source), path.relative(process.cwd(), source));
  const config: RegistryConfig = {
    region: root.string('region', REGION, 'an AWS Region name such as af-south-1'),
    repositoryName: root.string('repositoryName', /^[a-z0-9]+([._/-][a-z0-9]+)*$/, 'an ECR repository name'),
    imagesToKeep: root.integer('imagesToKeep', 1, 10000),
  };
  root.end();
  return config;
}

function readJson(source: string): unknown {
  let text: string;
  try {
    text = fs.readFileSync(source, 'utf8');
  } catch {
    throw new Error(`${source}: cannot be read; is the environment name right?`);
  }
  try {
    return JSON.parse(text) as unknown;
  } catch (error) {
    throw new Error(`${source}: is not valid JSON (${(error as Error).message})`, { cause: error });
  }
}

/** The digest every environment's tasks run, as `sha256:` and 64 hex digits. */
export const IMAGE_DIGEST = /^sha256:[0-9a-f]{64}$/;

/**
 * Checks the image digest the pipeline passes in `BRANCHBOOK_IMAGE_DIGEST`.
 *
 * @param value - the variable's value, or undefined when it is not set
 * @throws {Error} when it is missing or not a sha256 digest
 */
export function requireImageDigest(value: string | undefined): string {
  if (value === undefined || !IMAGE_DIGEST.test(value)) {
    throw new Error(
      `BRANCHBOOK_IMAGE_DIGEST must be the image's digest, sha256: and 64 hex digits, whenever BRANCHBOOK_ENV is set; got ${JSON.stringify(value ?? null)}`,
    );
  }
  return value;
}
