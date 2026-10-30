import { CfnOutput, CliCredentialsStackSynthesizer, Duration, RemovalPolicy, Stack, StackProps } from 'aws-cdk-lib/core';
import * as accessanalyzer from 'aws-cdk-lib/aws-accessanalyzer';
import * as cloudtrail from 'aws-cdk-lib/aws-cloudtrail';
import * as awsconfig from 'aws-cdk-lib/aws-config';
import * as guardduty from 'aws-cdk-lib/aws-guardduty';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as kms from 'aws-cdk-lib/aws-kms';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as securityhub from 'aws-cdk-lib/aws-securityhub';
import { Construct } from 'constructs';
import { AccountConfig, DeployTarget, deployRoleName, deployTargets, WorkloadConfigs } from './account-stack';

/**
 * The services whose resources the application stacks create, so the CloudFormation execution role may manage them.
 * Each is allowed whole (`service:*`) because CloudFormation's resource handlers call a wide and changing set of
 * actions; what keeps this safe is IAM, which is handled separately and narrowly, and the explicit denies.
 */
export const CFN_EXEC_SERVICES = [
  'acm',
  'application-autoscaling',
  'cloudwatch',
  'ec2',
  'ecr',
  'ecs',
  'elasticloadbalancing',
  'fis',
  'kms',
  'lambda',
  'logs',
  'rds',
  'route53',
  's3',
  'secretsmanager',
  'sms-voice',
  'sns',
  'ssm',
  // The Provider framework behind the schema migration waits for the task in a Step Functions state machine.
  'states',
  'synthetics',
  'wafv2',
] as const;

/**
 * The AWS services whose service-linked roles a deployment may create: ECS, its auto scaling, the load balancer, RDS
 * and WAF logging each create one the first time they are used in an account.
 */
export const SERVICE_LINKED_ROLE_SERVICES = [
  'ecs.amazonaws.com',
  'ecs.application-autoscaling.amazonaws.com',
  'elasticloadbalancing.amazonaws.com',
  'rds.amazonaws.com',
  'wafv2.amazonaws.com',
] as const;

/** The AWS Foundational Security Best Practices standard, as Security Hub names it after `standards/`. */
export const FOUNDATIONAL_BEST_PRACTICES = 'aws-foundational-security-best-practices/v/1.0.0';

// Object key prefixes inside the audit bucket. CloudTrail and AWS Config append `AWSLogs/<account>/...` themselves.
const TRAIL_KEY_PREFIX = 'cloudtrail';
const CONFIG_KEY_PREFIX = 'config';

// The audit bucket refuses connections older than TLS 1.2, the oldest version AWS still recommends.
const MINIMUM_TLS_VERSION = 1.2;

/**
 * The name of the customer-managed policy that `cdk bootstrap --cloudformation-execution-policies` attaches to one
 * environment's CloudFormation execution role, for example `branchbook-cdk-cfn-exec-staging`.
 *
 * @param config - the account configuration, for its name prefix
 * @param environment - the environment's name
 */
export function cfnExecPolicyName(config: Pick<AccountConfig, 'namePrefix'>, environment: string): string {
  return `${config.namePrefix}-cdk-cfn-exec-${environment}`;
}

/**
 * The name of the permissions boundary every role the application stacks create must carry, for example
 * `branchbook-permissions-boundary`. The CDK app attaches it through the `@aws-cdk/core:permissionsBoundary` context.
 *
 * @param config - the account configuration, for its name prefix
 */
export function permissionsBoundaryName(config: Pick<AccountConfig, 'namePrefix'>): string {
  return `${config.namePrefix}-permissions-boundary`;
}

/**
 * Configuration for {@link GuardrailsStack}.
 */
export interface GuardrailsStackProps extends StackProps {
  /** The account-level configuration, from `loadAccountConfig`. */
  readonly config: AccountConfig;

  /** The environments' and the registry's configuration, which each execution policy's stack names derive from. */
  readonly workloads: WorkloadConfigs;
}

/**
 * The account's detective controls and the limits on what a deployment may do.
 *
 * - **Audit**: a multi-Region CloudTrail trail with log file validation, encrypted with its own KMS key, into a
 *   versioned bucket that blocks public access, refuses plain HTTP and expires objects after the configured days.
 * - **Detection**: a GuardDuty detector, AWS Config recording every supported resource, Security Hub with the AWS
 *   Foundational Security Best Practices standard, and an account IAM Access Analyzer.
 * - **Deployment limits**: one customer-managed policy per environment, `branchbook-cdk-cfn-exec-<environment>`,
 *   which replaces CDK's default `AdministratorAccess` on that environment's bootstrap execution role and lets it
 *   change only that environment's stacks' IAM roles, and the permissions boundary `branchbook-permissions-boundary`,
 *   which every role those execution roles create must carry.
 *
 * An administrator deploys it, never the pipeline: it uses the administrator's own credentials instead of the
 * bootstrap roles, because the hardened execution role may not touch these services, and the pipeline's deploy roles
 * have no permissions of their own to deploy it with.
 */
export class GuardrailsStack extends Stack {
  /** The bucket that holds CloudTrail logs and AWS Config history. */
  public readonly auditBucket: s3.Bucket;

  /** Each environment's policy for its bootstrap's CloudFormation execution role, by environment name. */
  public readonly cfnExecPolicies: Readonly<Record<string, iam.ManagedPolicy>>;

  /** The boundary every role the application stacks create must carry. */
  public readonly permissionsBoundary: iam.ManagedPolicy;

  constructor(scope: Construct, id: string, props: GuardrailsStackProps) {
    super(scope, id, { ...props, synthesizer: new CliCredentialsStackSynthesizer() });
    const { config } = props;
    const settings = config.guardrails;
    const { partition, account, region } = this;

    const trailName = `${config.namePrefix}-trail`;
    const trailArn = `arn:${partition}:cloudtrail:${region}:${account}:trail/${trailName}`;
    const cloudTrailService = new iam.ServicePrincipal('cloudtrail.amazonaws.com');
    const configService = new iam.ServicePrincipal('config.amazonaws.com');

    // The trail's own key, so CloudTrail log files are encrypted with a key whose policy this stack controls. The
    // default key policy lets IAM in this account grant decryption; CloudTrail may only encrypt, for this trail.
    const trailKey = new kms.Key(this, 'TrailKey', {
      alias: `alias/${config.namePrefix}-cloudtrail`,
      description: 'Encrypts the BranchBook CloudTrail log files.',
      enableKeyRotation: true,
      removalPolicy: RemovalPolicy.RETAIN,
    });
    trailKey.addToResourcePolicy(
      new iam.PolicyStatement({
        sid: 'CloudTrailEncryptsThisTrailsLogs',
        principals: [cloudTrailService],
        actions: ['kms:GenerateDataKey*'],
        resources: ['*'],
        conditions: {
          StringEquals: { 'aws:SourceArn': trailArn },
          StringLike: { 'kms:EncryptionContext:aws:cloudtrail:arn': `arn:${partition}:cloudtrail:*:${account}:trail/*` },
        },
      }),
    );
    trailKey.addToResourcePolicy(
      new iam.PolicyStatement({
        sid: 'CloudTrailDescribesTheKey',
        principals: [cloudTrailService],
        actions: ['kms:DescribeKey'],
        resources: ['*'],
        conditions: { StringEquals: { 'aws:SourceArn': trailArn } },
      }),
    );

    // Audit records outlive the stack (RETAIN), and every overwrite or delete keeps the previous version for a while.
    // The bucket's default encryption is S3-managed because AWS Config writes here too; CloudTrail's files are
    // encrypted with the trail key regardless.
    this.auditBucket = new s3.Bucket(this, 'AuditBucket', {
      encryption: s3.BucketEncryption.S3_MANAGED,
      versioned: true,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      objectOwnership: s3.ObjectOwnership.BUCKET_OWNER_ENFORCED,
      enforceSSL: true,
      minimumTLSVersion: MINIMUM_TLS_VERSION,
      lifecycleRules: [
        {
          id: 'expire-audit-records',
          expiration: Duration.days(settings.auditLogRetentionDays),
          noncurrentVersionExpiration: Duration.days(settings.auditLogNoncurrentVersionDays),
        },
      ],
      removalPolicy: RemovalPolicy.RETAIN,
    });
    const bucket = this.auditBucket;

    // Each service may write only under its own prefix and this account's folder. The source conditions stop another
    // account's trail or recorder from using this bucket (the confused-deputy problem).
    bucket.addToResourcePolicy(
      new iam.PolicyStatement({
        sid: 'CloudTrailChecksTheBucketAcl',
        principals: [cloudTrailService],
        actions: ['s3:GetBucketAcl'],
        resources: [bucket.bucketArn],
        conditions: { StringEquals: { 'aws:SourceArn': trailArn } },
      }),
    );
    bucket.addToResourcePolicy(
      new iam.PolicyStatement({
        sid: 'CloudTrailWritesThisTrailsLogs',
        principals: [cloudTrailService],
        actions: ['s3:PutObject'],
        resources: [bucket.arnForObjects(`${TRAIL_KEY_PREFIX}/AWSLogs/${account}/*`)],
        conditions: { StringEquals: { 's3:x-amz-acl': 'bucket-owner-full-control', 'aws:SourceArn': trailArn } },
      }),
    );
    bucket.addToResourcePolicy(
      new iam.PolicyStatement({
        sid: 'ConfigChecksTheBucket',
        principals: [configService],
        actions: ['s3:GetBucketAcl', 's3:ListBucket'],
        resources: [bucket.bucketArn],
        conditions: { StringEquals: { 'aws:SourceAccount': account } },
      }),
    );
    bucket.addToResourcePolicy(
      new iam.PolicyStatement({
        sid: 'ConfigWritesThisAccountsHistory',
        principals: [configService],
        actions: ['s3:PutObject'],
        resources: [bucket.arnForObjects(`${CONFIG_KEY_PREFIX}/AWSLogs/${account}/Config/*`)],
        conditions: { StringEquals: { 's3:x-amz-acl': 'bucket-owner-full-control', 'aws:SourceAccount': account } },
      }),
    );
    const bucketPolicy = bucket.policy;
    if (bucketPolicy === undefined) {
      throw new Error('The audit bucket has no policy; CloudTrail and AWS Config could not write to it');
    }

    // One trail for every Region, global services (IAM, STS) included, so activity anywhere in the account is
    // recorded once. Log file validation writes signed digests, which prove later that no log file was changed.
    const trail = new cloudtrail.CfnTrail(this, 'Trail', {
      trailName,
      isLogging: true,
      isMultiRegionTrail: true,
      includeGlobalServiceEvents: true,
      enableLogFileValidation: true,
      s3BucketName: bucket.bucketName,
      s3KeyPrefix: TRAIL_KEY_PREFIX,
      kmsKeyId: trailKey.keyArn,
    });
    // CloudTrail checks it may write to the bucket when the trail is created.
    trail.node.addDependency(bucketPolicy);

    new guardduty.CfnDetector(this, 'GuardDuty', {
      enable: true,
      findingPublishingFrequency: settings.guardDuty.findingPublishingFrequency,
      features: Object.entries(settings.guardDuty.features).map(([name, status]) => ({ name, status })),
    });

    // AWS Config records through its service-linked role, which Security Hub's control Config.1 expects; the role is
    // created once by an administrator (infra/README.md), because creating it here would fail if it already exists.
    const recorder = new awsconfig.CfnConfigurationRecorder(this, 'ConfigRecorder', {
      roleArn: `arn:${partition}:iam::${account}:role/aws-service-role/config.amazonaws.com/AWSServiceRoleForConfig`,
      recordingGroup: { allSupported: true, includeGlobalResourceTypes: true },
      recordingMode: { recordingFrequency: settings.config.recordingFrequency },
    });
    const deliveryChannel = new awsconfig.CfnDeliveryChannel(this, 'ConfigDeliveryChannel', {
      s3BucketName: bucket.bucketName,
      s3KeyPrefix: CONFIG_KEY_PREFIX,
      configSnapshotDeliveryProperties: { deliveryFrequency: settings.config.snapshotDeliveryFrequency },
    });
    // AWS Config requires the recorder before the channel, and the channel needs the bucket policy to write.
    deliveryChannel.node.addDependency(recorder);
    deliveryChannel.node.addDependency(bucketPolicy);

    // Exactly one standard: Security Hub's defaults would also enable the older CIS 1.2.0 benchmark. One finding per
    // control (SECURITY_CONTROL), and new controls of the standard switch on as AWS adds them.
    const hub = new securityhub.CfnHub(this, 'SecurityHub', {
      enableDefaultStandards: false,
      controlFindingGenerator: 'SECURITY_CONTROL',
      autoEnableControls: true,
    });
    // Security Hub's controls evaluate through AWS Config, so Config records before Security Hub starts.
    hub.node.addDependency(deliveryChannel);
    const standard = new securityhub.CfnStandard(this, 'FoundationalBestPractices', {
      standardsArn: `arn:${partition}:securityhub:${region}::standards/${FOUNDATIONAL_BEST_PRACTICES}`,
    });
    standard.node.addDependency(hub);

    // Reports any resource policy in the account that grants access to a principal outside it. Free.
    new accessanalyzer.CfnAnalyzer(this, 'AccessAnalyzer', {
      analyzerName: `${config.namePrefix}-account`,
      type: 'ACCOUNT',
    });

    // The policies refer to each other's ARNs, and to their own, so the ARNs are built from the names rather than
    // referenced, which would be circular.
    const policyArn = (name: string): string => `arn:${partition}:iam::${account}:policy/${name}`;
    const targets = deployTargets(config, props.workloads);
    const boundaryArn = policyArn(permissionsBoundaryName(config));
    const protectedStatements = guardrailDenies({
      policyArns: [boundaryArn, ...targets.map((target) => policyArn(cfnExecPolicyName(config, target.name)))],
      boundaryArn,
      auditBucketArn: bucket.bucketArn,
      trailKeyArn: trailKey.keyArn,
    });

    this.permissionsBoundary = new iam.ManagedPolicy(this, 'PermissionsBoundary', {
      managedPolicyName: permissionsBoundaryName(config),
      description: 'The most any BranchBook application role may do: no IAM users or keys, no escape from this boundary, no tampering with the audit trail or detective controls.',
      statements: [
        // A boundary only caps what a role's own policies grant; it grants nothing itself.
        new iam.PolicyStatement({ sid: 'AllowWhatTheRoleIsGranted', actions: ['*'], resources: ['*'] }),
        ...protectedStatements,
        // Without these three, a bounded role could still borrow an unbounded one: the CDK bootstrap roles trust the
        // whole account, the us-east-1 execution role holds AdministratorAccess, and CloudFormation acts as whatever
        // role it is handed.
        deny('DenyRoleChaining', ['sts:AssumeRole'], ['*']),
        deny('DenyTrustPolicyChanges', ['iam:UpdateAssumeRolePolicy'], ['*']),
        deny('DenyPassingRolesToCloudFormation', ['iam:PassRole'], ['*'], {
          StringEquals: { 'iam:PassedToService': 'cloudformation.amazonaws.com' },
        }),
      ],
    });

    const policies: Record<string, iam.ManagedPolicy> = {};
    for (const target of targets) {
      const label = target.name.charAt(0).toUpperCase() + target.name.slice(1);
      const policy = this.cfnExecPolicy(`${label}CfnExecPolicy`, config, target, boundaryArn, protectedStatements);
      policies[target.name] = policy;
      new CfnOutput(this, `${label}CfnExecPolicyArn`, {
        value: policy.managedPolicyArn,
        description: `Pass to cdk bootstrap --cloudformation-execution-policies for the ${target.name} bootstrap (qualifier ${target.bootstrapQualifier}).`,
      });
    }
    this.cfnExecPolicies = policies;

    new CfnOutput(this, 'PermissionsBoundaryArn', {
      value: this.permissionsBoundary.managedPolicyArn,
      description: 'Attached to every role the application stacks create (@aws-cdk/core:permissionsBoundary).',
    });
    new CfnOutput(this, 'AuditBucketName', {
      value: bucket.bucketName,
      description: 'Where CloudTrail logs and AWS Config history are kept.',
    });
  }

  /**
   * One environment's execution policy: the application services, and IAM only for the roles and managed policies
   * CloudFormation names after that environment's own stacks.
   *
   * @param id - the policy's construct ID
   * @param config - the account configuration
   * @param target - the environment
   * @param boundaryArn - the permissions boundary every role must carry
   * @param protectedStatements - the denies every deployment policy carries
   */
  private cfnExecPolicy(
    id: string,
    config: AccountConfig,
    target: DeployTarget,
    boundaryArn: string,
    protectedStatements: iam.PolicyStatement[],
  ): iam.ManagedPolicy {
    const { partition, account } = this;
    // CloudFormation names a role `<stack>-<logical id>-<suffix>`, and a managed policy likewise, so these patterns
    // cover this environment's stacks' roles and nothing else: not the other environment's, whose stack IDs differ.
    const roleArns = target.stackNames.map((name) => `arn:${partition}:iam::${account}:role/${name}-*`);
    const managedPolicyArns = target.stackNames.map((name) => `arn:${partition}:iam::${account}:policy/${name}-*`);
    return new iam.ManagedPolicy(this, id, {
      managedPolicyName: cfnExecPolicyName(config, target.name),
      description: `What CloudFormation may do when the pipeline deploys ${target.name}: the application services, and IAM only for ${target.name}'s roles, which must carry the permissions boundary.`,
      statements: [
        new iam.PolicyStatement({
          sid: 'DeployTheApplicationServices',
          actions: CFN_EXEC_SERVICES.map((service) => `${service}:*`),
          resources: ['*'],
        }),
        new iam.PolicyStatement({ sid: 'ReadIam', actions: ['iam:Get*', 'iam:List*'], resources: ['*'] }),
        new iam.PolicyStatement({
          sid: 'CreateServiceLinkedRoles',
          actions: ['iam:CreateServiceLinkedRole'],
          resources: [`arn:${partition}:iam::${account}:role/aws-service-role/*`],
          conditions: { StringEquals: { 'iam:AWSServiceName': [...SERVICE_LINKED_ROLE_SERVICES] } },
        }),
        // Some resources name a service-linked role (an ECS scalable target, for one). Only its own service can assume
        // such a role, so passing it grants nothing else.
        new iam.PolicyStatement({
          sid: 'PassServiceLinkedRoles',
          actions: ['iam:PassRole'],
          resources: [`arn:${partition}:iam::${account}:role/aws-service-role/*`],
        }),
        // Creating a role, or giving one permissions, requires the boundary.
        new iam.PolicyStatement({
          sid: 'CreateAndGrantAppRolesOnlyWithTheBoundary',
          actions: ['iam:AttachRolePolicy', 'iam:CreateRole', 'iam:PutRolePermissionsBoundary', 'iam:PutRolePolicy'],
          resources: roleArns,
          conditions: { StringEquals: { 'iam:PermissionsBoundary': boundaryArn } },
        }),
        new iam.PolicyStatement({
          sid: 'ManageAppRoles',
          actions: [
            'iam:DeleteRole',
            'iam:DeleteRolePolicy',
            'iam:DetachRolePolicy',
            'iam:PassRole',
            'iam:TagRole',
            'iam:UntagRole',
            'iam:UpdateAssumeRolePolicy',
            'iam:UpdateRole',
            'iam:UpdateRoleDescription',
          ],
          resources: roleArns,
        }),
        new iam.PolicyStatement({
          sid: 'ManageAppManagedPolicies',
          actions: [
            'iam:CreatePolicy',
            'iam:CreatePolicyVersion',
            'iam:DeletePolicy',
            'iam:DeletePolicyVersion',
            'iam:TagPolicy',
            'iam:UntagPolicy',
          ],
          resources: managedPolicyArns,
        }),
        // The roles that deploy (the bootstrap roles and the pipeline's own) are never a deployment's to change.
        deny('DenyBootstrapAndDeployRoles', ['iam:*'], [
          `arn:${partition}:iam::${account}:role/cdk-*`,
          `arn:${partition}:iam::${account}:role/${deployRoleName(config, '*')}`,
        ]),
        ...protectedStatements,
      ],
    });
  }
}

/** The ARNs the shared deny statements protect. */
interface ProtectedArns {
  /** The boundary and every execution policy: none may be changed or deleted. */
  readonly policyArns: readonly string[];
  readonly boundaryArn: string;
  readonly auditBucketArn: string;
  readonly trailKeyArn: string;
}

/**
 * The denies both policies carry: neither the execution role nor a bounded role may loosen either policy, shed the
 * boundary, create IAM users or keys, change the account or its organization, or blind the detective controls.
 *
 * @param arns - the policies, bucket and key the denies protect
 */
function guardrailDenies(arns: ProtectedArns): iam.PolicyStatement[] {
  return [
    deny(
      'DenyChangingTheGuardrailPolicies',
      ['iam:CreatePolicyVersion', 'iam:DeletePolicy', 'iam:DeletePolicyVersion', 'iam:SetDefaultPolicyVersion'],
      [...arns.policyArns],
    ),
    deny('DenyRemovingTheBoundary', ['iam:DeleteRolePermissionsBoundary'], ['*']),
    // A role may only be created, or given permissions, while it carries this boundary.
    deny('DenyRolesWithoutTheBoundary', ['iam:AttachRolePolicy', 'iam:CreateRole', 'iam:PutRolePermissionsBoundary', 'iam:PutRolePolicy'], ['*'], {
      StringNotEquals: { 'iam:PermissionsBoundary': arns.boundaryArn },
    }),
    // Long-lived credentials are exactly what the OIDC design avoids.
    deny(
      'DenyIamUsersAndAccessKeys',
      ['iam:*AccessKey*', 'iam:*LoginProfile*', 'iam:*ServiceSpecificCredential*', 'iam:*SigningCertificate*', 'iam:*SSHPublicKey*', 'iam:*User*'],
      ['*'],
    ),
    deny('DenyOrganizationsAndAccountChanges', ['account:*', 'organizations:*', 's3:PutAccountPublicAccessBlock'], ['*']),
    // No application role reads these services either, so denying every action is simpler and stronger than listing
    // the ones that could switch a control off or suppress its findings.
    deny('DenyTamperingWithDetectiveControls', ['access-analyzer:*', 'cloudtrail:*', 'config:*', 'guardduty:*', 'securityhub:*'], ['*']),
    deny('DenyTouchingTheAuditRecords', ['kms:*', 's3:*'], [arns.auditBucketArn, `${arns.auditBucketArn}/*`, arns.trailKeyArn]),
  ];
}

/**
 * A deny statement with a statement ID, so CDK never merges it with another and tests can find it by name.
 *
 * @param sid - the statement ID
 * @param actions - the actions denied
 * @param resources - the resources they are denied on
 * @param conditions - when the deny applies; always, if omitted
 */
function deny(sid: string, actions: string[], resources: string[], conditions?: Record<string, unknown>): iam.PolicyStatement {
  return new iam.PolicyStatement({ sid, effect: iam.Effect.DENY, actions, resources, conditions });
}
