import { ArnFormat, CfnOutput, Duration, RemovalPolicy, SecretValue, Stack, StackProps, Validations } from 'aws-cdk-lib/core';
import * as acm from 'aws-cdk-lib/aws-certificatemanager';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as cloudwatchActions from 'aws-cdk-lib/aws-cloudwatch-actions';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as route53 from 'aws-cdk-lib/aws-route53';
import * as targets from 'aws-cdk-lib/aws-route53-targets';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as smsvoice from 'aws-cdk-lib/aws-smsvoice';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as subscriptions from 'aws-cdk-lib/aws-sns-subscriptions';
import * as wafv2 from 'aws-cdk-lib/aws-wafv2';
import { Construct } from 'constructs';
import { CaptchaApiKey } from './captcha-api-key';
import { EnvironmentConfig, retentionDays } from './config';
import { Observability, allowMetricsExport, metricsExportEnvironment } from './constructs/observability';
import { DATABASE_NAME, POSTGRES_PORT, SUBNET_GROUPS } from './data-stack';
import { SchemaMigration } from './schema-migration';
import { ANY_RESOURCE_FINDING, LAMBDA_BASIC_EXECUTION_FINDING } from './nag';
import { MMS_COUNTRIES, SMS_COUNTRIES, VOICE_COUNTRIES } from './sms-countries';

/** The port the app serves its pages, API and probes on; the only port the load balancer reaches. */
export const APP_PORT = 8080;

/**
 * The management (actuator) port of the `prod` profile. Nothing routes to it: it is in no target group and no
 * security group rule, so only the task itself can call it.
 */
export const MANAGEMENT_PORT = 8081;

/** HTTPS, the one port the task may call out on, besides PostgreSQL. */
const HTTPS_PORT = 443;

/** Where the image keeps the RDS certificate bundle that `sslmode=verify-full` checks the server against. */
export const RDS_CA_BUNDLE = '/app/certs/rds-global-bundle.pem';

/** The JDBC driver of the AWS Advanced JDBC Wrapper, which signs the IAM authentication token. */
export const AWS_JDBC_DRIVER = 'software.amazon.jdbc.Driver';

/** The API path that bookings, lookups and cancellations share. */
export const APPOINTMENTS_PATH = '/api/v1/appointments';

/** The header the pipeline's synthetic traffic carries its token in (staging only). */
export const SYNTHETIC_TRAFFIC_HEADER = 'x-branchbook-synthetic';

/** The header a lookup or cancellation carries the number's last four digits in. */
export const MOBILE_LAST4_HEADER = 'x-mobile-last4';

/** The names of the app's container and of the migration's. */
export const CONTAINER_NAMES = { app: 'App', migrate: 'Migrate' } as const;

/** The scratch volume mounted at /tmp, the one writable path in the container's read-only file system. */
const TMP_VOLUME = 'tmp';

/** The alert topic and the three alarms the app stack raises itself. */
interface BaseAlarms {
  readonly topic: sns.Topic;
  readonly serverErrors: cloudwatch.Alarm;
  readonly noHealthyTask: cloudwatch.Alarm;
  readonly databaseStorage: cloudwatch.Alarm;
}

/**
 * Configuration for {@link AppStack}.
 */
export interface AppStackProps extends StackProps {
  /** The environment: sizes, limits, thresholds, retention, names and the app's settings. */
  readonly config: EnvironmentConfig;

  /** The network from {@link DataStack}. */
  readonly vpc: ec2.IVpc;

  /** The database from {@link DataStack}. */
  readonly database: rds.DatabaseInstance;

  /** The SMS daily cap's HMAC key from {@link DataStack}, injected into the task and never into the template. */
  readonly smsCapKey: secretsmanager.ISecret;

  /** The ECR repository the image is pulled from, `branchbook` (see {@link RegistryStack}). */
  readonly repositoryName: string;

  /** The digest of the image every task runs, `sha256:...`, from `BRANCHBOOK_IMAGE_DIGEST`. */
  readonly imageDigest: string;

  /** Where the alarms are e-mailed, if anywhere. Personal data, so supplied at deploy time only. */
  readonly alertEmail?: string;

  /**
   * When true, the app asks AWS to check every send without sending it (SendTextMessage's DryRun), which proves the
   * task's permission on a real deployment before anyone is texted. Off by default.
   */
  readonly smsDryRun?: boolean;
}

/**
 * One environment's application, in Cape Town (`af-south-1`), on the network and database of {@link DataStack}
 * (ADR-0006). A request takes this path:
 *
 * 1. Route 53 answers the domain with the load balancer's address.
 * 2. AWS WAF, attached to the load balancer, blocks `/actuator`, blocks an address that makes too many bookings or
 *    cancellations, lookups or requests of any kind, and asks for a CAPTCHA on every booking and cancellation, before
 *    AWS's managed rules.
 * 3. The load balancer ends HTTPS with an ACM certificate, redirects plain HTTP to it, and health-checks `/readyz` on
 *    port 8080. The management port, 8081, is never routed.
 * 4. One of the Fargate tasks serves the request. The tasks run in private subnets with no public address, accept
 *    traffic only from the load balancer, and may call out only on 443 (AWS's APIs, through the NAT gateways) and to
 *    the database on 5432. Their root file system is read-only and they hold no Linux capabilities. Autoscaling keeps
 *    their number between the configured bounds.
 * 5. The task signs in to RDS as `branchbook_app` with an IAM token over TLS (`verify-full`); it has no database
 *    password. Only the migration task, run once per image before the service is updated, has the master secret.
 *
 * Every number, name and limit comes from the environment's configuration (infra/config).
 */
export class AppStack extends Stack {
  /** The address the environment serves. */
  public readonly url: string;

  /** The Fargate service. */
  public readonly service: ecs.FargateService;

  /** The service's target group behind the HTTPS listener. */
  public readonly targetGroup: elbv2.ApplicationTargetGroup;

  /** The internet-facing load balancer. */
  public readonly loadBalancer: elbv2.ApplicationLoadBalancer;

  /** The database the service uses. */
  public readonly database: rds.DatabaseInstance;

  /** The topic every alarm publishes to. */
  public readonly alarmTopic: sns.Topic;

  /** Raised when the app answers 5xx to too many requests; it also rolls a deployment back. */
  public readonly serverErrorsAlarm: cloudwatch.Alarm;

  /** Raised when no task has passed the load balancer's health check for a while. */
  public readonly noHealthyTaskAlarm: cloudwatch.Alarm;

  /** The app's metrics, the alarms on them and on AWS's metrics, the dashboard and the canary. */
  public readonly observability: Observability;

  /** The web ACL in front of the load balancer. */
  public readonly webAcl: wafv2.CfnWebACL;

  /** The migration that runs before the service is updated. */
  public readonly migration: SchemaMigration;

  /** The one-off migration task definition. */
  public readonly migrationTask: ecs.FargateTaskDefinition;

  constructor(scope: Construct, id: string, props: AppStackProps) {
    super(scope, id, props);
    const { config } = props;
    const prefix = config.resourcePrefix;
    this.database = props.database;
    this.url = `https://${config.domain.name}`;

    // The construct IDs below are those of the first deployment; renaming one would replace the resource.
    const zone = route53.HostedZone.fromHostedZoneAttributes(this, 'Zone', {
      hostedZoneId: config.domain.hostedZoneId,
      zoneName: config.domain.hostedZoneName,
    });
    const certificate = new acm.Certificate(this, 'Certificate', {
      domainName: config.domain.name,
      validation: acm.CertificateValidation.fromDns(zone),
    });

    const cluster = new ecs.Cluster(this, 'Cluster', {
      vpc: props.vpc,
      containerInsightsV2: config.service.containerInsights ? ecs.ContainerInsights.ENABLED : ecs.ContainerInsights.DISABLED,
    });

    const logGroup = new logs.LogGroup(this, 'Logs', {
      logGroupName: `/${prefix}/app`,
      retention: retentionDays(config.logs.appRetentionDays),
    });

    const secret = props.database.secret;
    if (secret === undefined) {
      throw new Error('the database must have generated credentials');
    }
    const endpoint = props.database.dbInstanceEndpointAddress;
    const image = ecs.ContainerImage.fromEcrRepository(
      ecr.Repository.fromRepositoryName(this, 'Repository', props.repositoryName),
      props.imageDigest,
    );
    const appSubnets = props.vpc.selectSubnets({ subnetGroupName: SUBNET_GROUPS.app });

    const taskSecurityGroup = new ec2.SecurityGroup(this, 'TaskSecurityGroup', {
      vpc: props.vpc,
      description: 'BranchBook app task: in from the load balancer only',
      allowAllOutbound: false,
    });
    // Out on 443 only: ECR, Secrets Manager, CloudWatch Logs, STS and End User Messaging, through the NAT gateways.
    // DNS and the task metadata endpoint are link-local, which security groups never filter.
    taskSecurityGroup.addEgressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(HTTPS_PORT), 'AWS APIs over HTTPS');
    // The database's rule is written here, on an imported handle of its group, so the data stack never refers to
    // this one: the app can be torn down and redeployed while the data stack stays as it is.
    const databaseSecurityGroups = props.database.connections.securityGroups;
    if (databaseSecurityGroups.length !== 1) {
      throw new Error('the database must have exactly one security group');
    }
    const databaseSecurityGroup = ec2.SecurityGroup.fromSecurityGroupId(
      this,
      'DatabaseSecurityGroup',
      databaseSecurityGroups[0].securityGroupId,
    );
    databaseSecurityGroup.addIngressRule(
      taskSecurityGroup,
      ec2.Port.tcp(POSTGRES_PORT),
      'PostgreSQL from the BranchBook app task only',
    );
    taskSecurityGroup.addEgressRule(databaseSecurityGroup, ec2.Port.tcp(POSTGRES_PORT), 'PostgreSQL to the database only');

    const task = this.taskDefinition('Task', config.service.cpu, config.service.memoryMiB);

    // The runtime role signs in with IAM authentication: the task role may connect as branchbook_app and as no
    // other database user.
    props.database.grantConnect(task.taskRole, config.database.appUser);
    // The app exports its outbox and circuit breaker meters to CloudWatch for the alarms, into this environment's
    // namespace alone.
    allowMetricsExport(task.taskRole, config.monitoring.metricsNamespace);

    const environment: Record<string, string> = {
      ...config.appEnvironment,
      // IAM authentication through the AWS Advanced JDBC Wrapper, which signs a short-lived token with the task role;
      // verify-full checks the server's certificate and name against the RDS bundle baked into the image.
      DB_URL: `jdbc:aws-wrapper:postgresql://${endpoint}:${String(POSTGRES_PORT)}/${DATABASE_NAME}?wrapperPlugins=iam&sslmode=verify-full&sslrootcert=${RDS_CA_BUNDLE}`,
      DB_USER: config.database.appUser,
      DB_PASSWORD: '',
      SPRING_DATASOURCE_DRIVER_CLASS_NAME: AWS_JDBC_DRIVER,
      // Sized so the worst case, every task autoscaling allows plus a deployment's new ones, fits the database's
      // connection budget (config.ts, connectionPeak).
      SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE: String(config.service.dbPoolSize),
      // The confirmation SMS's /manage link (BR-18).
      PUBLIC_BASE_URL: this.url,
      BRANCHBOOK_SMS_GATEWAY: config.sms.gateway,
      BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP: String(config.sms.recipientDailyCap),
      ...metricsExportEnvironment(config),
    };
    if (config.sms.gateway === 'aws') {
      Object.assign(environment, this.smsSettings(config, task, props.smsDryRun === true));
    }

    const captcha = new CaptchaApiKey(this, 'Captcha', {
      domainName: config.domain.name,
      logRetention: retentionDays(config.logs.appRetentionDays),
    });
    environment.BRANCHBOOK_CAPTCHA_SCRIPT_URL = captcha.scriptUrl;
    environment.BRANCHBOOK_CAPTCHA_API_KEY = captcha.apiKey;

    const container = task.addContainer(CONTAINER_NAMES.app, {
      ...this.hardenedContainer(image, logGroup, 'app'),
      portMappings: [{ containerPort: APP_PORT }],
      environment,
      secrets: {
        // The daily cap's ledger keys each number's fingerprint with it. Without it the app would make a key at
        // start-up, and every new task would start counting from zero.
        BRANCHBOOK_SMS_RECIPIENT_CAP_KEY: ecs.Secret.fromSecretsManager(props.smsCapKey),
      },
      // Liveness only: a task whose JVM stops answering is replaced. Readiness, which includes the database, is
      // the load balancer's check, so a database outage takes tasks out of rotation without killing them all.
      healthCheck: {
        command: ['CMD-SHELL', `wget -q -O /dev/null http://127.0.0.1:${String(APP_PORT)}/livez || exit 1`],
        interval: Duration.seconds(config.healthCheck.containerIntervalSeconds),
        timeout: Duration.seconds(config.healthCheck.containerTimeoutSeconds),
        retries: config.healthCheck.containerRetries,
        startPeriod: Duration.seconds(config.healthCheck.containerStartPeriodSeconds),
      },
      stopTimeout: Duration.seconds(config.service.stopTimeoutSeconds),
    });
    container.addMountPoints({ sourceVolume: TMP_VOLUME, containerPath: '/tmp', readOnly: false });

    // The migration task: the same image, run once per digest before the service is updated. It alone receives the
    // master secret, with a plain JDBC URL, and it serves nothing.
    const migrationTask = this.taskDefinition('MigrationTask', config.migration.cpu, config.migration.memoryMiB);
    this.migrationTask = migrationTask;
    const migrationContainer = migrationTask.addContainer(CONTAINER_NAMES.migrate, {
      ...this.hardenedContainer(image, logGroup, 'migrate'),
      environment: {
        // The same settings as the service, so the prod profile's start-up guard sees the same safe combination.
        ...config.appEnvironment,
        DB_URL: `jdbc:postgresql://${endpoint}:${String(POSTGRES_PORT)}/${DATABASE_NAME}?sslmode=verify-full&sslrootcert=${RDS_CA_BUNDLE}`,
        SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE: String(config.migration.dbPoolSize),
        PUBLIC_BASE_URL: this.url,
        BRANCHBOOK_MIGRATE_ONLY: 'true',
        BRANCHBOOK_MIGRATE_ON_START: 'true',
        BRANCHBOOK_DB_APP_ROLE: config.database.appUser,
        SPRING_MAIN_WEB_APPLICATION_TYPE: 'none',
      },
      secrets: {
        DB_USER: ecs.Secret.fromSecretsManager(secret, 'username'),
        DB_PASSWORD: ecs.Secret.fromSecretsManager(secret, 'password'),
      },
    });
    migrationContainer.addMountPoints({ sourceVolume: TMP_VOLUME, containerPath: '/tmp', readOnly: false });

    this.migration = new SchemaMigration(this, 'Migration', {
      cluster,
      taskDefinition: migrationTask,
      containerName: CONTAINER_NAMES.migrate,
      subnets: appSubnets.subnets,
      securityGroup: taskSecurityGroup,
      imageDigest: props.imageDigest,
      timeout: Duration.minutes(config.migration.timeoutMinutes),
      logRetention: retentionDays(config.logs.appRetentionDays),
    });

    this.service = new ecs.FargateService(this, 'Service', {
      cluster,
      taskDefinition: task,
      // No desiredCount: autoscaling owns the number of tasks, and a deployment must not reset it. A new service
      // starts with one and autoscaling raises it to minTasks.
      assignPublicIp: false,
      vpcSubnets: { subnetGroupName: SUBNET_GROUPS.app },
      securityGroups: [taskSecurityGroup],
      // A new version starts and passes its health check before the old one stops, and a version that never
      // becomes healthy, or raises the deployment alarm, is rolled back.
      minHealthyPercent: config.service.minHealthyPercent,
      maxHealthyPercent: config.service.maxHealthyPercent,
      circuitBreaker: { enable: true, rollback: true },
      healthCheckGracePeriod: Duration.seconds(config.service.healthCheckGracePeriodSeconds),
    });
    // The migration runs only once its task can reach the database: its roles' policies, the task's egress rules
    // and the database's ingress rule must exist first, or a first deployment could race them.
    this.migration.resource.node.addDependency(migrationTask, taskSecurityGroup, databaseSecurityGroup);
    // The service is updated only after the new image's migration has exited 0.
    this.service.node.addDependency(this.migration.resource);

    this.loadBalancer = new elbv2.ApplicationLoadBalancer(this, 'LoadBalancer', {
      vpc: props.vpc,
      internetFacing: true,
      vpcSubnets: { subnetGroupName: SUBNET_GROUPS.public },
      dropInvalidHeaderFields: true,
    });
    this.loadBalancer.addRedirect({ sourcePort: 80, targetPort: HTTPS_PORT });
    const https = this.loadBalancer.addListener('Https', {
      port: HTTPS_PORT,
      certificates: [certificate],
      sslPolicy: elbv2.SslPolicy.RECOMMENDED_TLS,
    });
    // Port 8080 only; the management port is never a target.
    this.targetGroup = https.addTargets('App', {
      port: APP_PORT,
      protocol: elbv2.ApplicationProtocol.HTTP,
      targets: [this.service],
      healthCheck: {
        path: '/readyz',
        interval: Duration.seconds(config.healthCheck.intervalSeconds),
        healthyThresholdCount: config.healthCheck.healthyThreshold,
        unhealthyThresholdCount: config.healthCheck.unhealthyThreshold,
      },
      deregistrationDelay: Duration.seconds(config.service.deregistrationDelaySeconds),
    });

    const scaling = this.service.autoScaleTaskCount({
      minCapacity: config.service.minTasks,
      maxCapacity: config.service.maxTasks,
    });
    scaling.scaleOnCpuUtilization('Cpu', {
      targetUtilizationPercent: config.service.cpuTargetPercent,
      scaleOutCooldown: Duration.seconds(config.service.scaleOutCooldownSeconds),
      scaleInCooldown: Duration.seconds(config.service.scaleInCooldownSeconds),
    });
    scaling.scaleOnRequestCount('Requests', {
      requestsPerTarget: config.service.requestsPerTargetPerMinute,
      targetGroup: this.targetGroup,
      scaleOutCooldown: Duration.seconds(config.service.scaleOutCooldownSeconds),
      scaleInCooldown: Duration.seconds(config.service.scaleInCooldownSeconds),
    });

    new route53.ARecord(this, 'Address', {
      zone,
      recordName: config.domain.name,
      target: route53.RecordTarget.fromAlias(new targets.LoadBalancerTarget(this.loadBalancer)),
    });

    this.webAcl = this.createWebAcl(config);
    new wafv2.CfnWebACLAssociation(this, 'WafOnLoadBalancer', {
      resourceArn: this.loadBalancer.loadBalancerArn,
      webAclArn: this.webAcl.attrArn,
    });
    this.wafLogging(config, this.webAcl);

    const alarms = this.alarms(props, config);
    this.alarmTopic = alarms.topic;
    this.serverErrorsAlarm = alarms.serverErrors;
    this.noHealthyTaskAlarm = alarms.noHealthyTask;
    this.observability = new Observability(this, 'Observability', {
      config,
      service: this.service,
      loadBalancer: this.loadBalancer,
      targetGroup: this.targetGroup,
      database: this.database,
      webAcl: this.webAcl,
      alarmTopic: this.alarmTopic,
      url: this.url,
      existingAlarms: [alarms.serverErrors, alarms.noHealthyTask, alarms.databaseStorage],
    });

    new CfnOutput(this, 'Url', { value: this.url, description: `The ${config.name} environment` });
    new CfnOutput(this, 'LogGroup', {
      value: logGroup.logGroupName,
      description: `Application logs, ${String(config.logs.appRetentionDays)} days`,
    });

    this.suppressNagFindings(config);
  }

  /**
   * An ARM64 Fargate task definition with the scratch volume both containers mount at /tmp.
   *
   * @param id - the construct ID
   * @param cpu - CPU units
   * @param memoryMiB - memory in MiB
   */
  private taskDefinition(id: string, cpu: number, memoryMiB: number): ecs.FargateTaskDefinition {
    const definition = new ecs.FargateTaskDefinition(this, id, {
      cpu,
      memoryLimitMiB: memoryMiB,
      // The pipeline builds the image for ARM64 (Graviton), which Fargate runs for less than x86.
      runtimePlatform: {
        cpuArchitecture: ecs.CpuArchitecture.ARM64,
        operatingSystemFamily: ecs.OperatingSystemFamily.LINUX,
      },
    });
    definition.addVolume({ name: TMP_VOLUME });
    return definition;
  }

  /**
   * The settings both containers share: the image by digest, logs, a read-only root file system and no Linux
   * capabilities. The JVM and Tomcat write only to /tmp, the task's scratch volume.
   *
   * @param image - the image, by digest
   * @param logGroup - the application's log group
   * @param streamPrefix - the log stream prefix, `app` or `migrate`
   */
  private hardenedContainer(image: ecs.ContainerImage, logGroup: logs.ILogGroup, streamPrefix: string): ecs.ContainerDefinitionOptions {
    const linuxParameters = new ecs.LinuxParameters(this, `${streamPrefix}LinuxParameters`);
    linuxParameters.dropCapabilities(ecs.Capability.ALL);
    return {
      image,
      logging: ecs.LogDrivers.awsLogs({ logGroup, streamPrefix }),
      readonlyRootFilesystem: true,
      linuxParameters,
    };
  }

  /**
   * Real SMS through AWS End User Messaging. A protect configuration allows every country by default, so it blocks
   * each one AWS lists except the allowed one, and every voice and MMS destination; the app names it on every send,
   * so it holds whatever the account's default is. SendTextMessage is authorised against the resources a send names;
   * with no origination identity (AWS's shared route) that is the protect configuration alone, so the task may send
   * through this one and nothing else.
   *
   * @param config - the environment
   * @param task - the service's task definition, whose role may send
   * @param dryRun - whether AWS only checks each send
   */
  private smsSettings(config: EnvironmentConfig, task: ecs.TaskDefinition, dryRun: boolean): Record<string, string> {
    const smsProtection = new smsvoice.CfnProtectConfiguration(this, 'SmsProtection', {
      countryRuleSet: {
        sms: SMS_COUNTRIES.map((countryCode) => ({
          countryCode,
          protectStatus: countryCode === config.sms.allowedCountry ? 'ALLOW' : 'BLOCK',
        })),
        voice: VOICE_COUNTRIES.map((countryCode) => ({ countryCode, protectStatus: 'BLOCK' })),
        mms: MMS_COUNTRIES.map((countryCode) => ({ countryCode, protectStatus: 'BLOCK' })),
      },
    });
    task.addToTaskRolePolicy(
      new iam.PolicyStatement({
        actions: ['sms-voice:SendTextMessage'],
        resources: [smsProtection.attrArn],
      }),
    );
    return {
      BRANCHBOOK_SMS_AWS_REGION: this.region,
      BRANCHBOOK_SMS_AWS_PROTECT_CONFIGURATION_ID: smsProtection.attrProtectConfigurationId,
      BRANCHBOOK_SMS_AWS_DRY_RUN: String(dryRun),
    };
  }

  /**
   * The web ACL in front of the load balancer, its rules in order:
   *
   * 0. Block `/actuator`.
   * 1. to 3. Block an address over the booking-and-cancellation, lookup or overall request limit.
   * 4. A CAPTCHA on every booking and cancellation `POST`. WAF answers a request without a valid token with 405 and
   *    `x-amzn-waf-action: captcha`, and the SPA shows the puzzle and resends. On staging, requests that carry the
   *    synthetic-traffic token skip it; production has no bypass.
   * 5. to 7. AWS's managed rule groups: IP reputation, known bad inputs and the common rule set.
   *
   * @param config - the environment's limits and windows
   */
  private createWebAcl(config: EnvironmentConfig): wafv2.CfnWebACL {
    const { waf } = config;
    const visibility = (metricName: string): wafv2.CfnWebACL.VisibilityConfigProperty => ({
      cloudWatchMetricsEnabled: true,
      metricName,
      sampledRequestsEnabled: true,
    });
    // The path is decoded and lowercased before it is compared, so /%61ctuator or /ACTUATOR cannot slip past a rule
    // on its way to an application that decodes it.
    const startsWith = (search: string): wafv2.CfnWebACL.StatementProperty => ({
      byteMatchStatement: {
        fieldToMatch: { uriPath: {} },
        positionalConstraint: 'STARTS_WITH',
        searchString: search,
        textTransformations: [
          { priority: 0, type: 'URL_DECODE' },
          { priority: 1, type: 'LOWERCASE' },
        ],
      },
    });
    const method = (name: 'GET' | 'POST'): wafv2.CfnWebACL.StatementProperty => ({
      byteMatchStatement: {
        fieldToMatch: { method: {} },
        positionalConstraint: 'EXACTLY',
        searchString: name,
        textTransformations: [{ priority: 0, type: 'NONE' }],
      },
    });
    const bookingOrCancel = (): wafv2.CfnWebACL.StatementProperty[] => [method('POST'), startsWith(APPOINTMENTS_PATH)];
    const rateLimit = (
      name: string,
      priority: number,
      limit: number,
      window: number,
      scopeDown?: wafv2.CfnWebACL.StatementProperty[],
    ): wafv2.CfnWebACL.RuleProperty => ({
      name,
      priority,
      action: { block: {} },
      statement: {
        rateBasedStatement: {
          limit,
          evaluationWindowSec: window,
          aggregateKeyType: 'IP',
          scopeDownStatement: scopeDown === undefined ? undefined : { andStatement: { statements: scopeDown } },
        },
      },
      visibilityConfig: visibility(name),
    });
    const managed = (name: string, priority: number): wafv2.CfnWebACL.RuleProperty => ({
      name,
      priority,
      overrideAction: { none: {} },
      statement: { managedRuleGroupStatement: { vendorName: 'AWS', name } },
      visibilityConfig: visibility(name),
    });

    const captchaScope = bookingOrCancel();
    if (waf.syntheticTrafficBypass) {
      // A CloudFormation dynamic reference: the token is resolved at deploy time and is never in the template.
      const token = SecretValue.secretsManager(`${config.resourcePrefix}/synthetic-traffic-token`).unsafeUnwrap();
      captchaScope.push({
        notStatement: {
          statement: {
            byteMatchStatement: {
              fieldToMatch: { singleHeader: { Name: SYNTHETIC_TRAFFIC_HEADER } },
              positionalConstraint: 'EXACTLY',
              searchString: token,
              textTransformations: [{ priority: 0, type: 'NONE' }],
            },
          },
        },
      });
    }

    return new wafv2.CfnWebACL(this, 'Waf', {
      scope: 'REGIONAL',
      defaultAction: { allow: {} },
      visibilityConfig: visibility(`${config.resourcePrefix}-web-acl`),
      rules: [
        {
          name: 'block-actuator',
          priority: 0,
          action: { block: {} },
          statement: startsWith('/actuator'),
          visibilityConfig: visibility('block-actuator'),
        },
        rateLimit('booking-rate-limit', 1, waf.bookingLimit, waf.bookingWindowSeconds, bookingOrCancel()),
        rateLimit('lookup-rate-limit', 2, waf.lookupLimit, waf.lookupWindowSeconds, [
          method('GET'),
          startsWith(`${APPOINTMENTS_PATH}/`),
        ]),
        rateLimit('request-rate-limit', 3, waf.requestLimit, waf.requestWindowSeconds),
        {
          name: 'booking-captcha',
          priority: 4,
          action: { captcha: {} },
          captchaConfig: { immunityTimeProperty: { immunityTime: waf.captchaImmunitySeconds } },
          statement: { andStatement: { statements: captchaScope } },
          visibilityConfig: visibility('booking-captcha'),
        },
        managed('AWSManagedRulesAmazonIpReputationList', 5),
        managed('AWSManagedRulesKnownBadInputsRuleSet', 6),
        managed('AWSManagedRulesCommonRuleSet', 7),
      ],
    });
  }

  /**
   * WAF's request log, in CloudWatch Logs. Each entry records the request's headers and path, so the fields that
   * carry personal data or credentials are redacted (BR-19): the number's last four digits, the synthetic-traffic
   * token, the query string, the path and the Referer (both hold appointment references), and the cookies (the
   * CAPTCHA token).
   *
   * @param config - the environment's prefix and retention
   * @param webAcl - the web ACL to log
   */
  private wafLogging(config: EnvironmentConfig, webAcl: wafv2.CfnWebACL): void {
    // WAF accepts only log groups whose names start with aws-waf-logs-.
    const name = `aws-waf-logs-${config.resourcePrefix}`;
    const logGroup = new logs.LogGroup(this, 'WafLogs', {
      logGroupName: name,
      retention: retentionDays(config.logs.wafRetentionDays),
      removalPolicy: RemovalPolicy.DESTROY,
    });
    const header = (headerName: string): wafv2.CfnLoggingConfiguration.FieldToMatchProperty => ({
      singleHeader: { Name: headerName },
    });
    const logging = new wafv2.CfnLoggingConfiguration(this, 'WafLogging', {
      resourceArn: webAcl.attrArn,
      // WAF wants the log group's ARN without the :* that CloudFormation's Arn attribute ends in.
      logDestinationConfigs: [
        this.formatArn({ service: 'logs', resource: 'log-group', resourceName: name, arnFormat: ArnFormat.COLON_RESOURCE_NAME }),
      ],
      redactedFields: [
        header(MOBILE_LAST4_HEADER),
        header(SYNTHETIC_TRAFFIC_HEADER),
        header('referer'),
        header('cookie'),
        { queryString: {} },
        { uriPath: {} },
      ],
    });
    logging.node.addDependency(logGroup);
  }

  /**
   * Three alarms: the app failing requests, no healthy task, and the database running out of space. The first also
   * rolls back an ECS deployment that raises it. The rest are in {@link Observability}.
   *
   * @param props - the stack's configuration: the optional alert address
   * @param config - the environment's thresholds
   */
  private alarms(props: AppStackProps, config: EnvironmentConfig): BaseAlarms {
    const { alarms } = config;
    const topic = new sns.Topic(this, 'Alarms', {
      displayName: `BranchBook ${config.name} alarms`,
      enforceSSL: true,
    });
    if (props.alertEmail !== undefined) {
      topic.addSubscription(new subscriptions.EmailSubscription(props.alertEmail));
    }
    const notify = new cloudwatchActions.SnsAction(topic);

    const serverErrors = new cloudwatch.Alarm(this, 'ServerErrors', {
      alarmDescription: `The app answered 5xx to ${String(alarms.serverErrorThreshold)} or more requests in ${String(alarms.serverErrorPeriodMinutes)} minutes.`,
      metric: this.loadBalancer.metrics.httpCodeTarget(elbv2.HttpCodeTarget.TARGET_5XX_COUNT, {
        period: Duration.minutes(alarms.serverErrorPeriodMinutes),
        statistic: 'Sum',
      }),
      threshold: alarms.serverErrorThreshold,
      evaluationPeriods: 1,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });
    serverErrors.addAlarmAction(notify);
    // Beside the circuit breaker, which catches tasks that never become healthy, a deployment whose healthy tasks
    // answer 5xx is rolled back too.
    this.service.enableDeploymentAlarms([serverErrors.alarmName], { behavior: ecs.AlarmBehavior.ROLLBACK_ON_ALARM });

    const noHealthyTask = new cloudwatch.Alarm(this, 'NoHealthyTask', {
      alarmDescription: `No task has passed the /readyz health check for ${String(alarms.noHealthyTaskMinutes)} minutes.`,
      metric: this.targetGroup.metrics.healthyHostCount({ period: Duration.minutes(1), statistic: 'Minimum' }),
      threshold: 1,
      evaluationPeriods: alarms.noHealthyTaskMinutes,
      comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
      treatMissingData: cloudwatch.TreatMissingData.BREACHING,
    });
    noHealthyTask.addAlarmAction(notify);

    const databaseStorage = new cloudwatch.Alarm(this, 'DatabaseStorage', {
      alarmDescription: `The database has less than ${String(alarms.databaseFreeStorageGiB)} GB of storage left.`,
      metric: this.database.metricFreeStorageSpace({ period: Duration.minutes(5), statistic: 'Minimum' }),
      threshold: alarms.databaseFreeStorageGiB * 1024 ** 3,
      evaluationPeriods: 1,
      comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
    });
    databaseStorage.addAlarmAction(notify);

    return { topic, serverErrors, noHealthyTask, databaseStorage };
  }

  /**
   * The cdk-nag (AwsSolutions) findings this stack accepts, each with its reason. infra/test/nag.test.ts fails on
   * any finding not listed here.
   *
   * @param config - the environment
   */
  private suppressNagFindings(config: EnvironmentConfig): void {
    Validations.of(this).acknowledge(
      {
        id: 'AwsSolutions-ECS2',
        reason: 'The task\'s environment holds settings only; every secret (the SMS cap key, the master credentials) comes from Secrets Manager, and the CAPTCHA key is public by design.',
      },
      {
        id: LAMBDA_BASIC_EXECUTION_FINDING,
        reason: 'CDK\'s own custom resource handlers (the AWS SDK call, and where the canary\'s artifacts are deleted with the stack, the bucket emptier) use AWSLambdaBasicExecutionRole, which grants only writing their own logs.',
      },
    );
    Validations.of(this.loadBalancer).acknowledge(
      {
        id: 'AwsSolutions-ELB2',
        reason: 'Access logs would record every request path, and lookup and cancel paths carry appointment references, which no log may hold (BR-19). WAF logs, with those fields redacted, record each request instead.',
      },
      {
        id: 'AwsSolutions-EC23',
        reason: 'The load balancer serves the public site: it must accept 80 (redirected) and 443 from anywhere; WAF filters what reaches the tasks.',
      },
    );
    Validations.of(this.service.taskDefinition).acknowledge({
      id: ANY_RESOURCE_FINDING,
      reason: 'ecr:GetAuthorizationToken, which the execution role needs to pull the image, accepts no resource ARN; nor does cloudwatch:PutMetricData, which the task role may call only for its environment\'s namespace (a condition).',
    });
    Validations.of(this.migrationTask).acknowledge({
      id: ANY_RESOURCE_FINDING,
      reason: 'ecr:GetAuthorizationToken, which the execution role needs to pull the image, accepts no resource ARN.',
    });
    if (!config.service.containerInsights) {
      Validations.of(this.service.cluster).acknowledge({
        id: 'AwsSolutions-ECS4',
        reason: `${config.name} saves Container Insights' cost; production turns it on, and the service's CPU and memory metrics remain.`,
      });
    }
  }
}
