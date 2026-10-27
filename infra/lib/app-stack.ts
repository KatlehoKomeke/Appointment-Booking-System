import * as path from 'node:path';
import { CfnOutput, Duration, Stack, StackProps } from 'aws-cdk-lib/core';
import * as acm from 'aws-cdk-lib/aws-certificatemanager';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as cloudwatchActions from 'aws-cdk-lib/aws-cloudwatch-actions';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import { Platform } from 'aws-cdk-lib/aws-ecr-assets';
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
import { DATABASE_NAME } from './data-stack';
import { MMS_COUNTRIES, SMS_COUNTRIES, VOICE_COUNTRIES } from './sms-countries';

/**
 * Bookings and cancellations one address may make in {@link BOOKING_RATE_WINDOW_SECONDS} before WAF refuses more
 * (ADR-0006). Each sends a real SMS on the hosted demo, so the limit is WAF's lowest.
 */
export const BOOKING_RATE_LIMIT = 10;

/** The most SMS the app sends to one number in a day, whatever the bookings (SECURITY.md, known risks). */
export const SMS_RECIPIENT_DAILY_CAP = 5;

/** The only country the hosted demo texts: the app accepts South African mobile numbers alone (BR-19). */
export const SMS_COUNTRY = 'ZA';

/** The window of the booking rate limit: five minutes. */
export const BOOKING_RATE_WINDOW_SECONDS = 300;

/**
 * Configuration for {@link AppStack}.
 */
export interface AppStackProps extends StackProps {
  /** The network from {@link DataStack}. */
  readonly vpc: ec2.IVpc;

  /** The database from {@link DataStack}; its generated secret supplies the credentials. */
  readonly database: rds.DatabaseInstance;

  /** The SMS daily cap's HMAC key from {@link DataStack}, injected into the task and never into the template. */
  readonly smsCapKey: secretsmanager.ISecret;

  /**
   * The public domain, at most 20 characters so that `https://` and it fit the 28 the confirmation SMS allows
   * (BR-18). Its Route 53 hosted zone was created when the domain was registered.
   */
  readonly domainName: string;

  /** The ID of that hosted zone. Public, not a secret, and written out so that synthesis needs no lookup. */
  readonly hostedZoneId: string;

  /** Where the three alarms are e-mailed, if anywhere. Personal data, so supplied at deploy time only. */
  readonly alertEmail?: string;

  /**
   * When true, the app asks AWS to check every send without sending it (SendTextMessage's DryRun), which proves the
   * task's permission on a real deployment before anyone is texted. Off by default.
   */
  readonly smsDryRun?: boolean;
}

/**
 * The hosted demo's application, in Cape Town (`af-south-1`), on the network and database of {@link DataStack}
 * (ADR-0006). A reviewer's request takes this path:
 *
 * 1. Route 53 answers the domain with the load balancer's address.
 * 2. AWS WAF, attached to the load balancer, blocks `/actuator` (403), allows 10 bookings or cancellations per
 *    address in five minutes, and applies AWS's managed rules for known bad inputs and addresses. There is no
 *    sign-in: anyone with the address can try the demo. It texts the confirmation for real, to South African numbers
 *    only, at most five a day to one number, and deletes each booking once its appointment is over.
 * 3. The load balancer ends HTTPS with an ACM certificate, redirects plain HTTP to it, and health-checks `/readyz`.
 * 4. One ARM64 Fargate task (0.5 vCPU, 1 GB) serves the request on port 8080. It accepts traffic only from the load
 *    balancer, and runs in a public subnet so it reaches ECR, Secrets Manager and AWS End User Messaging without a
 *    NAT gateway. It may send SMS through the stack's protect configuration and nothing else.
 * 5. The task reaches RDS on port 5432 over TLS, directly, with no RDS Proxy; the database accepts only the task.
 *
 * The image is built from the repository's Dockerfile for ARM64 when the stack is deployed. Application logs are
 * kept for seven days, and three alarms e-mail through an SNS topic.
 */
export class AppStack extends Stack {
  /** The address reviewers open. */
  public readonly url: string;

  constructor(scope: Construct, id: string, props: AppStackProps) {
    super(scope, id, props);

    if (props.domainName.length > 20) {
      throw new Error(`domainName must be at most 20 characters for the SMS link (BR-18): ${props.domainName}`);
    }
    this.url = `https://${props.domainName}`;

    const zone = route53.HostedZone.fromHostedZoneAttributes(this, 'Zone', {
      hostedZoneId: props.hostedZoneId,
      zoneName: props.domainName,
    });
    const certificate = new acm.Certificate(this, 'Certificate', {
      domainName: props.domainName,
      validation: acm.CertificateValidation.fromDns(zone),
    });

    const cluster = new ecs.Cluster(this, 'Cluster', { vpc: props.vpc });

    const logGroup = new logs.LogGroup(this, 'Logs', {
      logGroupName: '/branchbook/app',
      retention: logs.RetentionDays.ONE_WEEK,
    });

    const task = new ecs.FargateTaskDefinition(this, 'Task', {
      cpu: 512,
      memoryLimitMiB: 1024,
      runtimePlatform: {
        cpuArchitecture: ecs.CpuArchitecture.ARM64,
        operatingSystemFamily: ecs.OperatingSystemFamily.LINUX,
      },
    });

    const secret = props.database.secret;
    if (secret === undefined) {
      throw new Error('the database must have generated credentials');
    }
    const endpoint = props.database.dbInstanceEndpointAddress;

    // Real SMS go through AWS End User Messaging. A protect configuration allows every country by default, so it
    // blocks each one AWS lists except South Africa, and every voice and MMS destination; the app names it on every
    // send, so it holds whatever the account's default is.
    const smsProtection = new smsvoice.CfnProtectConfiguration(this, 'SmsProtection', {
      countryRuleSet: {
        sms: SMS_COUNTRIES.map((countryCode) => ({
          countryCode,
          protectStatus: countryCode === SMS_COUNTRY ? 'ALLOW' : 'BLOCK',
        })),
        voice: VOICE_COUNTRIES.map((countryCode) => ({ countryCode, protectStatus: 'BLOCK' })),
        mms: MMS_COUNTRIES.map((countryCode) => ({ countryCode, protectStatus: 'BLOCK' })),
      },
    });
    // SendTextMessage is authorised against the resources a send names; with no origination identity (AWS's shared
    // route) that is the protect configuration alone, so the task may send through this one and nothing else.
    task.addToTaskRolePolicy(
      new iam.PolicyStatement({
        actions: ['sms-voice:SendTextMessage'],
        resources: [smsProtection.attrArn],
      }),
    );

    task.addContainer('App', {
      image: ecs.ContainerImage.fromAsset(path.join(__dirname, '..', '..'), {
        file: 'Dockerfile',
        platform: Platform.LINUX_ARM64,
      }),
      portMappings: [{ containerPort: 8080 }],
      logging: ecs.LogDrivers.awsLogs({ logGroup, streamPrefix: 'app' }),
      environment: {
        // sslmode=require encrypts the connection; the server also refuses anything else (rds.force_ssl).
        DB_URL: `jdbc:postgresql://${endpoint}:5432/${DATABASE_NAME}?sslmode=require`,
        // The confirmation SMS's /manage link (BR-18).
        PUBLIC_BASE_URL: this.url,
        // The simulated inbox is off: with no sign-in, it would show every visitor every booking's reference, and a
        // demo number's last four digits are public, so anyone could open anyone's booking. Each booker still sees
        // their own SMS and its status on the confirmation page.
        BRANCHBOOK_INBOX_ENABLED: 'false',
        // Should anyone switch the inbox back on, it shows every reference as APT-****-****.
        BRANCHBOOK_INBOX_REDACT_REFERENCES: 'true',
        // Each booking, with its name and number, is deleted once its appointment is over, as the privacy notice
        // says of the hosted demo.
        BRANCHBOOK_RETENTION_ENABLED: 'true',
        // Real SMS through AWS End User Messaging, in Cape Town, to South African numbers only (the protect
        // configuration above). The published demo numbers, 082 555 0000 to 082 555 0199, stay on the simulated
        // gateway, so a reader trying the README's examples never texts a stranger.
        BRANCHBOOK_SMS_GATEWAY: 'aws',
        BRANCHBOOK_SMS_AWS_REGION: this.region,
        BRANCHBOOK_SMS_AWS_PROTECT_CONFIGURATION_ID: smsProtection.attrProtectConfigurationId,
        BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP: String(SMS_RECIPIENT_DAILY_CAP),
        BRANCHBOOK_SMS_AWS_DRY_RUN: String(props.smsDryRun === true),
        // Only health and info are served, so the metrics stay private even if a request slipped past WAF's block.
        MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: 'health,info',
        // The load balancer ends HTTPS; trusting its X-Forwarded-* headers makes the app see the request as secure,
        // so links and HSTS are right. Tomcat trusts them only from private addresses, which the load balancer has.
        SERVER_FORWARD_HEADERS_STRATEGY: 'native',
      },
      secrets: {
        DB_USER: ecs.Secret.fromSecretsManager(secret, 'username'),
        DB_PASSWORD: ecs.Secret.fromSecretsManager(secret, 'password'),
        // The daily cap's ledger keys each number's fingerprint with it. Without it the app would make a key at
        // start-up, and every new task would start counting from zero.
        BRANCHBOOK_SMS_RECIPIENT_CAP_KEY: ecs.Secret.fromSecretsManager(props.smsCapKey),
      },
      stopTimeout: Duration.seconds(30),
    });

    const taskSecurityGroup = new ec2.SecurityGroup(this, 'TaskSecurityGroup', {
      vpc: props.vpc,
      description: 'BranchBook app task: in from the load balancer only',
      allowAllOutbound: true,
    });
    // The database's rule is written here, on an imported handle of its group, so the data stack never refers to
    // this one: the app can be torn down and redeployed while the data stack stays as it is.
    const databaseSecurityGroups = props.database.connections.securityGroups;
    if (databaseSecurityGroups.length !== 1) {
      throw new Error('the database must have exactly one security group');
    }
    const databaseSecurityGroupId = databaseSecurityGroups[0].securityGroupId;
    ec2.SecurityGroup.fromSecurityGroupId(this, 'DatabaseSecurityGroup', databaseSecurityGroupId).addIngressRule(
      taskSecurityGroup,
      ec2.Port.tcp(5432),
      'PostgreSQL from the BranchBook app task only',
    );

    const service = new ecs.FargateService(this, 'Service', {
      cluster,
      taskDefinition: task,
      desiredCount: 1,
      assignPublicIp: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      securityGroups: [taskSecurityGroup],
      // A new version starts and passes its health check before the old one stops, and a version that never
      // becomes healthy is rolled back.
      minHealthyPercent: 100,
      maxHealthyPercent: 200,
      circuitBreaker: { enable: true, rollback: true },
      healthCheckGracePeriod: Duration.minutes(3),
    });

    const loadBalancer = new elbv2.ApplicationLoadBalancer(this, 'LoadBalancer', {
      vpc: props.vpc,
      internetFacing: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      dropInvalidHeaderFields: true,
    });
    loadBalancer.addRedirect({ sourcePort: 80, targetPort: 443 });
    const https = loadBalancer.addListener('Https', {
      port: 443,
      certificates: [certificate],
      sslPolicy: elbv2.SslPolicy.RECOMMENDED_TLS,
    });
    const targetGroup = https.addTargets('App', {
      port: 8080,
      protocol: elbv2.ApplicationProtocol.HTTP,
      targets: [service],
      healthCheck: {
        path: '/readyz',
        interval: Duration.seconds(15),
        healthyThresholdCount: 2,
        unhealthyThresholdCount: 3,
      },
      deregistrationDelay: Duration.seconds(30),
    });

    new route53.ARecord(this, 'Address', {
      zone,
      target: route53.RecordTarget.fromAlias(new targets.LoadBalancerTarget(loadBalancer)),
    });

    const webAcl = this.webAcl();
    new wafv2.CfnWebACLAssociation(this, 'WafOnLoadBalancer', {
      resourceArn: loadBalancer.loadBalancerArn,
      webAclArn: webAcl.attrArn,
    });

    this.alarms(props, loadBalancer, targetGroup);

    new CfnOutput(this, 'Url', { value: this.url, description: 'The hosted demo' });
    new CfnOutput(this, 'LogGroup', { value: logGroup.logGroupName, description: 'Application logs, 7 days' });
  }

  /**
   * The web ACL in front of the load balancer. It asks for no sign-in: a prompt in front of a demo turned away the
   * people it is for (ADR-0006 Notes). Texting South Africa only, five a day to a number, and deleting each booking
   * once its appointment is over keep what an open demo can do, and hold, small.
   */
  private webAcl(): wafv2.CfnWebACL {
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
    const managed = (name: string, priority: number): wafv2.CfnWebACL.RuleProperty => ({
      name,
      priority,
      overrideAction: { none: {} },
      statement: { managedRuleGroupStatement: { vendorName: 'AWS', name } },
      visibilityConfig: visibility(name),
    });

    return new wafv2.CfnWebACL(this, 'Waf', {
      scope: 'REGIONAL',
      defaultAction: { allow: {} },
      visibilityConfig: visibility('branchbook-demo'),
      rules: [
        {
          name: 'block-actuator',
          priority: 0,
          action: { block: {} },
          statement: startsWith('/actuator'),
          visibilityConfig: visibility('block-actuator'),
        },
        {
          name: 'booking-rate-limit',
          priority: 1,
          action: { block: {} },
          statement: {
            rateBasedStatement: {
              limit: BOOKING_RATE_LIMIT,
              evaluationWindowSec: BOOKING_RATE_WINDOW_SECONDS,
              aggregateKeyType: 'IP',
              scopeDownStatement: {
                andStatement: {
                  statements: [
                    {
                      byteMatchStatement: {
                        fieldToMatch: { method: {} },
                        positionalConstraint: 'EXACTLY',
                        searchString: 'POST',
                        textTransformations: [{ priority: 0, type: 'NONE' }],
                      },
                    },
                    startsWith('/api/v1/appointments'),
                  ],
                },
              },
            },
          },
          visibilityConfig: visibility('booking-rate-limit'),
        },
        managed('AWSManagedRulesAmazonIpReputationList', 2),
        managed('AWSManagedRulesKnownBadInputsRuleSet', 3),
        managed('AWSManagedRulesCommonRuleSet', 4),
      ],
    });
  }

  /**
   * Three alarms: the app failing requests, no healthy task, and the database running out of space.
   *
   * @param props - the stack's configuration: the database and the optional alert address
   * @param loadBalancer - the load balancer whose 5xx answers are counted
   * @param targetGroup - the target group whose healthy tasks are counted
   */
  private alarms(props: AppStackProps, loadBalancer: elbv2.ApplicationLoadBalancer, targetGroup: elbv2.ApplicationTargetGroup) {
    const topic = new sns.Topic(this, 'Alarms', { displayName: 'BranchBook demo alarms' });
    if (props.alertEmail !== undefined) {
      topic.addSubscription(new subscriptions.EmailSubscription(props.alertEmail));
    }
    const notify = new cloudwatchActions.SnsAction(topic);

    new cloudwatch.Alarm(this, 'ServerErrors', {
      alarmDescription: 'The app answered 5xx to 5 or more requests in 5 minutes.',
      metric: loadBalancer.metrics.httpCodeTarget(elbv2.HttpCodeTarget.TARGET_5XX_COUNT, {
        period: Duration.minutes(5),
        statistic: 'Sum',
      }),
      threshold: 5,
      evaluationPeriods: 1,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    }).addAlarmAction(notify);

    new cloudwatch.Alarm(this, 'NoHealthyTask', {
      alarmDescription: 'No task has passed the /readyz health check for 3 minutes.',
      metric: targetGroup.metrics.healthyHostCount({ period: Duration.minutes(1), statistic: 'Minimum' }),
      threshold: 1,
      evaluationPeriods: 3,
      comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
      treatMissingData: cloudwatch.TreatMissingData.BREACHING,
    }).addAlarmAction(notify);

    new cloudwatch.Alarm(this, 'DatabaseStorage', {
      alarmDescription: 'The database has less than 2 GB of storage left.',
      metric: props.database.metricFreeStorageSpace({ period: Duration.minutes(5), statistic: 'Minimum' }),
      threshold: 2 * 1024 ** 3,
      evaluationPeriods: 1,
      comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
    }).addAlarmAction(notify);
  }
}
