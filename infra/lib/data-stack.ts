import { Duration, RemovalPolicy, Stack, StackProps } from 'aws-cdk-lib/core';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import { Construct } from 'constructs';

/** The database the application connects to; Flyway creates its schema on first start. */
export const DATABASE_NAME = 'branchbook';

/** PostgreSQL 17.11: the version `make up` and the tests run (compose.yaml), which Cape Town offers (ADR-0002). */
export const POSTGRES_VERSION = rds.PostgresEngineVersion.of('17.11', '17');

/**
 * Configuration for {@link DataStack}.
 */
export interface DataStackProps extends StackProps {
  /**
   * The two availability zones, written out so that synthesis needs no lookup (infra/README.md, "Open points"). An
   * RDS subnet group needs two zones even for a Single-AZ instance.
   */
  readonly availabilityZones: [string, string];
}

/**
 * The hosted demo's network and database, in Cape Town (`af-south-1`), kept apart from the app because they outlive
 * it: the app stack can be torn down and redeployed without touching the data (ADR-0006).
 *
 * - A VPC across two zones with a public and an isolated `/24` subnet in each, and no NAT gateway: the Fargate task
 *   runs in a public subnet with a public address, so it reaches ECR and Secrets Manager without a NAT's hourly cost,
 *   while the database sits in subnets with no route to or from the internet.
 * - RDS for PostgreSQL 17 on `db.t4g.micro`, Single-AZ, encrypted at rest, with 7-day backups and TLS required. Its
 *   master credentials are generated into Secrets Manager and never appear in the template.
 * - The key the SMS daily cap fingerprints each number with, generated into Secrets Manager the same way.
 */
export class DataStack extends Stack {
  /** The network the app stack runs in. */
  public readonly vpc: ec2.Vpc;

  /** The database instance. The app stack opens its port to the task's security group only. */
  public readonly database: rds.DatabaseInstance;

  /** The HMAC key of the SMS daily cap's ledger, which the app stack injects into the task. */
  public readonly smsCapKey: secretsmanager.Secret;

  constructor(scope: Construct, id: string, props: DataStackProps) {
    super(scope, id, props);

    this.vpc = new ec2.Vpc(this, 'Network', {
      ipAddresses: ec2.IpAddresses.cidr('10.40.0.0/16'),
      availabilityZones: props.availabilityZones,
      natGateways: 0,
      subnetConfiguration: [
        { name: 'public', subnetType: ec2.SubnetType.PUBLIC, cidrMask: 24 },
        { name: 'data', subnetType: ec2.SubnetType.PRIVATE_ISOLATED, cidrMask: 24 },
      ],
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
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.MICRO),
      vpc: this.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      multiAz: false,
      publiclyAccessible: false,
      databaseName: DATABASE_NAME,
      credentials: rds.Credentials.fromGeneratedSecret('branchbook', {
        secretName: 'branchbook/database',
      }),
      parameterGroup: parameters,
      allocatedStorage: 20,
      maxAllocatedStorage: 50,
      storageType: rds.StorageType.GP3,
      storageEncrypted: true,
      backupRetention: Duration.days(7),
      // The demo holds synthetic bookings only, so it can be deleted outright when the review ends; a final
      // snapshot is still taken, in case a deletion was a mistake.
      deletionProtection: false,
      removalPolicy: RemovalPolicy.SNAPSHOT,
      // PostgreSQL's own log is not exported: a constraint violation's DETAIL line quotes the values, a mobile
      // number among them, and no log may hold personal data (BR-19).
      autoMinorVersionUpgrade: true,
    });

    // The app counts the SMS sent to each number in a ledger in this database, under an HMAC-SHA256 fingerprint of
    // the number keyed with this secret, so the ledger holds no number. The key lives here, with the ledger it keys:
    // a new task, or a redeployed app stack, keeps counting against the same fingerprints. 48 letters and digits.
    this.smsCapKey = new secretsmanager.Secret(this, 'SmsCapKey', {
      secretName: 'branchbook/sms-cap-key',
      description: 'BranchBook: the HMAC key that fingerprints each number in the SMS daily cap ledger',
      generateSecretString: { passwordLength: 48, excludePunctuation: true },
      removalPolicy: RemovalPolicy.DESTROY,
    });
  }
}
