import { Duration, RemovalPolicy, Stack, StackProps, Validations } from 'aws-cdk-lib/core';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import { Construct } from 'constructs';
import { EnvironmentConfig, retentionDays } from './config';

/** The database the application connects to; the migration task creates its schema. */
export const DATABASE_NAME = 'branchbook';

/** PostgreSQL 17.11: the version `make up` and the tests run (compose.yaml), which Cape Town offers (ADR-0002). */
export const POSTGRES_VERSION = rds.PostgresEngineVersion.of('17.11', '17');

/** PostgreSQL's port, which the app and migration tasks connect to. */
export const POSTGRES_PORT = 5432;

/**
 * The master user. It owns the schema and is used only by the migration task; changing it would replace the
 * database, so it is the same in every environment.
 */
export const MASTER_USERNAME = 'branchbook';

/**
 * The subnet groups, in the order they were added. CDK hands out address ranges in this order, so a new group goes
 * at the end and every existing subnet keeps its range (and so is not replaced).
 */
export const SUBNET_GROUPS = {
  /** The load balancer and the NAT gateways. */
  public: 'public',
  /** The database: no route to or from the internet. */
  data: 'data',
  /** The Fargate tasks: no public address, out through the NAT gateways only. */
  app: 'app',
} as const;

/**
 * Configuration for {@link DataStack}.
 */
export interface DataStackProps extends StackProps {
  /** The environment: network, database sizes, retention and names. */
  readonly config: EnvironmentConfig;
}

/**
 * One environment's network and database, in Cape Town (`af-south-1`), kept apart from the app because they outlive
 * it: the app stack can be torn down and redeployed without touching the data (ADR-0006).
 *
 * - A VPC across two zones with a public, an isolated (`data`) and a private (`app`) subnet in each. The tasks run in
 *   the private subnets, with no public address, and reach AWS's APIs through the NAT gateways (one, or one per zone);
 *   the database sits in subnets with no route to or from the internet. Rejected traffic is logged.
 * - RDS for PostgreSQL 17, encrypted at rest, with TLS required and IAM database authentication on, so the runtime
 *   role signs in with a short-lived token and no password exists for it. Its master credentials are generated into
 *   Secrets Manager, never appear in the template, and only the migration task receives them. Multi-AZ, deletion
 *   protection, backups and the instance class come from the environment's configuration.
 * - The key the SMS daily cap fingerprints each number with, generated into Secrets Manager the same way.
 * - Where the environment allows it (staging), the token that lets the pipeline's synthetic traffic skip the CAPTCHA.
 */
export class DataStack extends Stack {
  /** The network the app stack runs in. */
  public readonly vpc: ec2.Vpc;

  /** The database instance. The app stack opens its port to the task's security group only. */
  public readonly database: rds.DatabaseInstance;

  /** The HMAC key of the SMS daily cap's ledger, which the app stack injects into the task. */
  public readonly smsCapKey: secretsmanager.Secret;

  /** The synthetic-traffic token, where the environment has one. */
  public readonly syntheticTrafficToken?: secretsmanager.Secret;

  constructor(scope: Construct, id: string, props: DataStackProps) {
    super(scope, id, props);
    const { config } = props;
    const prefix = config.resourcePrefix;

    // The construct IDs below are those of the first deployment; renaming one would replace the resource.
    this.vpc = new ec2.Vpc(this, 'Network', {
      ipAddresses: ec2.IpAddresses.cidr(config.network.cidr),
      availabilityZones: [...config.availabilityZones],
      natGateways: config.network.natGateways,
      natGatewaySubnets: { subnetGroupName: SUBNET_GROUPS.public },
      subnetConfiguration: [
        { name: SUBNET_GROUPS.public, subnetType: ec2.SubnetType.PUBLIC, cidrMask: config.network.subnetCidrMask },
        { name: SUBNET_GROUPS.data, subnetType: ec2.SubnetType.PRIVATE_ISOLATED, cidrMask: config.network.subnetCidrMask },
        { name: SUBNET_GROUPS.app, subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS, cidrMask: config.network.subnetCidrMask },
      ],
    });

    // Rejected traffic only: it is what shows a probe or a misconfigured rule, and it stays small under a flood of
    // accepted requests to the load balancer.
    const flowLogs = new logs.LogGroup(this, 'FlowLogs', {
      logGroupName: `/${prefix}/vpc-flow-logs`,
      retention: retentionDays(config.logs.flowLogRetentionDays),
      removalPolicy: RemovalPolicy.DESTROY,
    });
    this.vpc.addFlowLog('RejectedTraffic', {
      destination: ec2.FlowLogDestination.toCloudWatchLogs(flowLogs),
      trafficType: ec2.FlowLogTrafficType.REJECT,
    });

    // rds.force_ssl refuses any connection that is not TLS; PostgreSQL 17 on RDS defaults to it, and stating it
    // keeps the guarantee if the default ever changes.
    const parameters = new rds.ParameterGroup(this, 'Parameters', {
      engine: rds.DatabaseInstanceEngine.postgres({ version: POSTGRES_VERSION }),
      description: 'BranchBook: TLS required',
      parameters: { 'rds.force_ssl': '1' },
    });

    this.database = new rds.DatabaseInstance(this, 'Database', {
      engine: rds.DatabaseInstanceEngine.postgres({ version: POSTGRES_VERSION }),
      instanceType: new ec2.InstanceType(config.database.instanceClass),
      vpc: this.vpc,
      vpcSubnets: { subnetGroupName: SUBNET_GROUPS.data },
      // The port is left to the engine's default, POSTGRES_PORT: the first deployment did not state it, and adding
      // it to the template now would put the live instance at risk of replacement for no gain.
      multiAz: config.database.multiAz,
      publiclyAccessible: false,
      databaseName: DATABASE_NAME,
      credentials: rds.Credentials.fromGeneratedSecret(MASTER_USERNAME, {
        secretName: `${prefix}/database`,
      }),
      // The runtime role (migration V9) has no password: the tasks sign in with a 15-minute token signed by their
      // task role, so there is no database password to leak from the app.
      iamAuthentication: true,
      parameterGroup: parameters,
      allocatedStorage: config.database.allocatedStorageGiB,
      maxAllocatedStorage: config.database.maxAllocatedStorageGiB,
      storageType: rds.StorageType.GP3,
      storageEncrypted: true,
      backupRetention: Duration.days(config.database.backupRetentionDays),
      deletionProtection: config.database.deletionProtection,
      // A final snapshot is taken whenever the instance is deleted, in case the deletion was a mistake.
      removalPolicy: RemovalPolicy.SNAPSHOT,
      // PostgreSQL's own log is not exported: a constraint violation's DETAIL line quotes the values, a mobile
      // number among them, and no log may hold personal data (BR-19).
      autoMinorVersionUpgrade: true,
    });

    // The app counts the SMS sent to each number in a ledger in this database, under an HMAC-SHA256 fingerprint of
    // the number keyed with this secret, so the ledger holds no number. The key lives here, with the ledger it keys:
    // a new task, or a redeployed app stack, keeps counting against the same fingerprints. 48 letters and digits.
    this.smsCapKey = new secretsmanager.Secret(this, 'SmsCapKey', {
      secretName: `${prefix}/sms-cap-key`,
      description: 'BranchBook: the HMAC key that fingerprints each number in the SMS daily cap ledger',
      generateSecretString: { passwordLength: 48, excludePunctuation: true },
      removalPolicy: RemovalPolicy.DESTROY,
    });

    if (config.waf.syntheticTrafficBypass) {
      // The pipeline's end-to-end tests and smoke test send this as x-branchbook-synthetic, and the web ACL lets such
      // requests skip the CAPTCHA. It lives with the data so redeploying the app does not change it mid-run.
      this.syntheticTrafficToken = new secretsmanager.Secret(this, 'SyntheticTrafficToken', {
        secretName: `${prefix}/synthetic-traffic-token`,
        description: 'BranchBook: lets the pipeline\'s synthetic traffic skip the CAPTCHA (staging only)',
        generateSecretString: { passwordLength: 40, excludePunctuation: true },
        removalPolicy: RemovalPolicy.DESTROY,
      });
      Validations.of(this.syntheticTrafficToken).acknowledge({
        id: 'AwsSolutions-SMG4',
        reason: 'Rotating the synthetic-traffic token mid-run would fail the pipeline\'s end-to-end tests; it guards a CAPTCHA on staging, not data.',
      });
    }

    // The cdk-nag (AwsSolutions) findings this stack accepts, each with its reason; infra/test/nag.test.ts fails on
    // any other.
    Validations.of(this.smsCapKey).acknowledge({
      id: 'AwsSolutions-SMG4',
      reason: 'The SMS cap key fingerprints numbers in a 24-hour ledger; rotating it would silently reset every count, so it is replaced deliberately, never on a schedule.',
    });
    Validations.of(this.database).acknowledge(
      {
        id: 'AwsSolutions-SMG4',
        reason: 'The master secret is read only by the one-off migration task; the runtime role has no password (IAM authentication). Scheduled rotation needs the Serverless rotation application and is not set up yet.',
      },
      {
        id: 'AwsSolutions-RDS11',
        reason: 'A non-default port is obscurity, not control: the security group admits the app and migration tasks only, and the subnets have no internet route.',
      },
    );
    if (!config.database.multiAz) {
      Validations.of(this.database).acknowledge({
        id: 'AwsSolutions-RDS3',
        reason: `${config.name} runs Single-AZ to halve its cost; production runs Multi-AZ, which config.ts enforces.`,
      });
    }
    if (!config.database.deletionProtection) {
      Validations.of(this.database).acknowledge({
        id: 'AwsSolutions-RDS10',
        reason: `${config.name} holds synthetic data and is torn down freely; production turns deletion protection on, which config.ts enforces.`,
      });
    }
  }
}
