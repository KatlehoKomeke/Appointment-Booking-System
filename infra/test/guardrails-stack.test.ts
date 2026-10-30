/**
 * Unit tests for {@link GuardrailsStack}: the audit trail, the detective controls, and the two policies that limit
 * what a deployment may do, checked on the synthesised CloudFormation template.
 */
import { App, CliCredentialsStackSynthesizer } from 'aws-cdk-lib/core';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { deployTargets, loadAccountConfig, loadWorkloadConfigs } from '../lib/account-stack';
import { CFN_EXEC_SERVICES, GuardrailsStack } from '../lib/guardrails-stack';

const config = loadAccountConfig();
const workloads = loadWorkloadConfigs();
// A placeholder account; the real one comes from the credentials in use and is never committed.
const ACCOUNT = '111111111111';

/** The parts of a synthesised IAM statement that the assertions below read. */
interface Statement {
  Sid?: string;
  Effect: 'Allow' | 'Deny';
  Action: string | string[];
  Resource: unknown;
  Condition?: Record<string, Record<string, unknown>>;
}

/** The parts of a synthesised `AWS::IAM::ManagedPolicy` resource that the assertions below read. */
interface ManagedPolicyResource {
  Properties: { ManagedPolicyName: string; PolicyDocument: { Statement: Statement[] } };
}

// The app sets this flag in cdk.json; with it, ARNs in a stack with a known Region are plain strings.
const app = new App({ context: { '@aws-cdk/core:enablePartitionLiterals': true } });
const stack = new GuardrailsStack(app, 'Guardrails', { env: { account: ACCOUNT, region: 'af-south-1' }, config, workloads });
const template = Template.fromStack(stack);

function policy(name: string): Statement[] {
  const policies = Object.values(template.findResources('AWS::IAM::ManagedPolicy') as Record<string, ManagedPolicyResource>);
  const found = policies.find((candidate) => candidate.Properties.ManagedPolicyName === name);
  if (found === undefined) {
    throw new Error(`no managed policy named ${name}`);
  }
  return found.Properties.PolicyDocument.Statement;
}

function statement(statements: Statement[], sid: string): Statement {
  const found = statements.find((candidate) => candidate.Sid === sid);
  if (found === undefined) {
    throw new Error(`no statement ${sid}`);
  }
  return found;
}

const actions = (found: Statement): string[] => (Array.isArray(found.Action) ? found.Action : [found.Action]);
const BOUNDARY_ARN = `:iam::${ACCOUNT}:policy/branchbook-permissions-boundary`;

describe('GuardrailsStack', () => {
  test('is deployed with the administrator credentials, never through the bootstrap roles the pipeline can reach', () => {
    expect(stack.synthesizer).toBeInstanceOf(CliCredentialsStackSynthesizer);
    expect(template.toJSON()).not.toHaveProperty('Parameters.BootstrapVersion');
  });

  test('records every Region and global service in one trail with log file validation, encrypted with its own rotating key', () => {
    template.hasResourceProperties('AWS::CloudTrail::Trail', {
      TrailName: 'branchbook-trail',
      IsLogging: true,
      IsMultiRegionTrail: true,
      IncludeGlobalServiceEvents: true,
      EnableLogFileValidation: true,
      S3KeyPrefix: 'cloudtrail',
      KMSKeyId: { 'Fn::GetAtt': [Match.stringLikeRegexp('^TrailKey'), 'Arn'] },
    });
    template.hasResourceProperties('AWS::KMS::Key', {
      EnableKeyRotation: true,
      KeyPolicy: {
        Statement: Match.arrayWith([
          Match.objectLike({
            Sid: 'CloudTrailEncryptsThisTrailsLogs',
            Principal: { Service: 'cloudtrail.amazonaws.com' },
            Condition: Match.objectLike({ StringEquals: { 'aws:SourceArn': Match.anyValue() } }),
          }),
        ]),
      },
    });
  });

  test('keeps audit records in a versioned, encrypted, private, retained bucket that expires them on the configured schedule', () => {
    template.hasResource('AWS::S3::Bucket', {
      DeletionPolicy: 'Retain',
      Properties: Match.objectLike({
        VersioningConfiguration: { Status: 'Enabled' },
        BucketEncryption: { ServerSideEncryptionConfiguration: [{ ServerSideEncryptionByDefault: { SSEAlgorithm: 'AES256' } }] },
        PublicAccessBlockConfiguration: {
          BlockPublicAcls: true,
          BlockPublicPolicy: true,
          IgnorePublicAcls: true,
          RestrictPublicBuckets: true,
        },
        LifecycleConfiguration: {
          Rules: [
            Match.objectLike({
              ExpirationInDays: config.guardrails.auditLogRetentionDays,
              NoncurrentVersionExpiration: { NoncurrentDays: config.guardrails.auditLogNoncurrentVersionDays },
              Status: 'Enabled',
            }),
          ],
        },
      }),
    });
  });

  test('the audit bucket refuses plain HTTP and old TLS, and lets only this trail and this account recorder write', () => {
    template.hasResourceProperties('AWS::S3::BucketPolicy', {
      PolicyDocument: {
        Statement: Match.arrayWith([
          Match.objectLike({ Effect: 'Deny', Condition: { Bool: { 'aws:SecureTransport': 'false' } } }),
          Match.objectLike({ Effect: 'Deny', Condition: { NumericLessThan: { 's3:TlsVersion': 1.2 } } }),
          Match.objectLike({
            Sid: 'CloudTrailWritesThisTrailsLogs',
            Action: 's3:PutObject',
            Condition: { StringEquals: { 's3:x-amz-acl': 'bucket-owner-full-control', 'aws:SourceArn': Match.anyValue() } },
          }),
          Match.objectLike({
            Sid: 'ConfigWritesThisAccountsHistory',
            Action: 's3:PutObject',
            Condition: { StringEquals: { 's3:x-amz-acl': 'bucket-owner-full-control', 'aws:SourceAccount': ACCOUNT } },
          }),
        ]),
      },
    });
  });

  test('turns on GuardDuty with exactly the configured protection plans', () => {
    template.hasResourceProperties('AWS::GuardDuty::Detector', {
      Enable: true,
      FindingPublishingFrequency: config.guardrails.guardDuty.findingPublishingFrequency,
      Features: Object.entries(config.guardrails.guardDuty.features).map(([Name, Status]) => ({ Name, Status })),
    });
  });

  test('records every supported resource with AWS Config, through its service-linked role, into the audit bucket', () => {
    template.hasResourceProperties('AWS::Config::ConfigurationRecorder', {
      RoleARN: Match.stringLikeRegexp('role/aws-service-role/config.amazonaws.com/AWSServiceRoleForConfig$'),
      RecordingGroup: { AllSupported: true, IncludeGlobalResourceTypes: true },
      RecordingMode: { RecordingFrequency: config.guardrails.config.recordingFrequency },
    });
    template.hasResource('AWS::Config::DeliveryChannel', {
      Properties: Match.objectLike({ S3KeyPrefix: 'config' }),
      DependsOn: Match.arrayWith([Match.stringLikeRegexp('^ConfigRecorder')]),
    });
  });

  test('enables Security Hub with only the AWS Foundational Security Best Practices standard, after AWS Config', () => {
    template.hasResource('AWS::SecurityHub::Hub', {
      Properties: { EnableDefaultStandards: false, ControlFindingGenerator: 'SECURITY_CONTROL', AutoEnableControls: true },
      DependsOn: Match.arrayWith([Match.stringLikeRegexp('^ConfigDeliveryChannel')]),
    });
    template.resourceCountIs('AWS::SecurityHub::Standard', 1);
    template.hasResource('AWS::SecurityHub::Standard', {
      Properties: {
        StandardsArn: Match.stringLikeRegexp(':securityhub:af-south-1::standards/aws-foundational-security-best-practices/v/1.0.0$'),
      },
      DependsOn: ['SecurityHub'],
    });
  });

  test('analyses the account for resources shared outside it', () => {
    template.hasResourceProperties('AWS::AccessAnalyzer::Analyzer', { Type: 'ACCOUNT' });
  });

  test('creates no IAM role of its own', () => {
    template.resourceCountIs('AWS::IAM::Role', 0);
  });

  describe.each(deployTargets(config, workloads).map((target) => [target.name, target] as const))('the %s CloudFormation execution policy', (name, target) => {
    const statements = policy(`branchbook-cdk-cfn-exec-${name}`);
    const otherStacks = deployTargets(config, workloads)
      .filter((other) => other.name !== name)
      .flatMap((other) => other.stackNames);

    test('allows each application service, and nothing else outright', () => {
      const allowed = statement(statements, 'DeployTheApplicationServices');
      expect(actions(allowed)).toEqual(CFN_EXEC_SERVICES.map((service) => `${service}:*`));
      const wholeServices = statements
        .filter((candidate) => candidate.Effect === 'Allow')
        .flatMap(actions)
        .filter((action) => action.endsWith(':*') || action === '*');
      expect(wholeServices).not.toContain('iam:*');
      expect(wholeServices).not.toContain('sts:*');
      expect(wholeServices).not.toContain('*');
    });

    test('creates or grants an IAM role only when the permissions boundary is attached', () => {
      const create = statement(statements, 'CreateAndGrantAppRolesOnlyWithTheBoundary');
      expect(actions(create).sort()).toEqual(['iam:AttachRolePolicy', 'iam:CreateRole', 'iam:PutRolePermissionsBoundary', 'iam:PutRolePolicy']);
      expect(JSON.stringify(create.Condition?.StringEquals['iam:PermissionsBoundary'])).toContain(BOUNDARY_ARN);
      // No other Allow statement may grant these actions without the condition.
      const unconditional = statements
        .filter((candidate) => candidate.Effect === 'Allow' && candidate.Sid !== create.Sid)
        .flatMap(actions);
      for (const action of actions(create)) {
        expect(unconditional).not.toContain(action);
      }
    });

    test('changes and passes only roles CloudFormation names after one of this environment\'s own stacks', () => {
      for (const sid of ['CreateAndGrantAppRolesOnlyWithTheBoundary', 'ManageAppRoles']) {
        const arns = (([] as unknown[]).concat(statement(statements, sid).Resource) as string[]).sort();
        expect(arns).toEqual(target.stackNames.map((stack) => `arn:aws:iam::${ACCOUNT}:role/${stack}-*`).sort());
        for (const other of otherStacks) {
          expect(arns).not.toContain(`arn:aws:iam::${ACCOUNT}:role/${other}-*`);
        }
      }
      const managed = (([] as unknown[]).concat(statement(statements, 'ManageAppManagedPolicies').Resource) as string[]).sort();
      expect(managed).toEqual(target.stackNames.map((stack) => `arn:aws:iam::${ACCOUNT}:policy/${stack}-*`).sort());
      expect(actions(statement(statements, 'ManageAppRoles'))).toContain('iam:PassRole');
      // Besides those, only service-linked roles, which nothing but their own service can assume, may be passed.
      const passing = statements.filter((candidate) => candidate.Effect === 'Allow' && actions(candidate).includes('iam:PassRole'));
      expect(passing.map((candidate) => candidate.Sid).sort()).toEqual(['ManageAppRoles', 'PassServiceLinkedRoles']);
      expect(statement(statements, 'PassServiceLinkedRoles').Resource).toBe(`arn:aws:iam::${ACCOUNT}:role/aws-service-role/*`);
    });

    test('never touches the bootstrap roles, the deploy roles or the guardrail policies', () => {
      const roles = statement(statements, 'DenyBootstrapAndDeployRoles');
      expect(roles).toMatchObject({ Effect: 'Deny', Action: 'iam:*' });
      expect(JSON.stringify(roles.Resource)).toContain(':role/cdk-*"');
      expect(JSON.stringify(roles.Resource)).toContain(':role/branchbook-github-deploy-*"');
      const policies = statement(statements, 'DenyChangingTheGuardrailPolicies');
      expect(policies.Effect).toBe('Deny');
      // Every execution policy, not only this one: no environment may loosen another's.
      expect(JSON.stringify(policies.Resource)).toContain(':policy/branchbook-cdk-cfn-exec-staging"');
      expect(JSON.stringify(policies.Resource)).toContain(':policy/branchbook-cdk-cfn-exec-production"');
      expect(JSON.stringify(policies.Resource)).toContain(`${BOUNDARY_ARN}"`);
    });

    test('fits within the 6,144-character limit IAM sets on a managed policy', () => {
      expect(JSON.stringify({ Version: '2012-10-17', Statement: statements }).length).toBeLessThan(6144);
    });
  });

  describe('the permissions boundary', () => {
    const statements = policy('branchbook-permissions-boundary');

    test('caps a role at what its own policies grant', () => {
      expect(statement(statements, 'AllowWhatTheRoleIsGranted')).toMatchObject({ Effect: 'Allow', Action: '*', Resource: '*' });
    });

    test('denies removing or changing the boundary, and creating or granting a role without it', () => {
      expect(statement(statements, 'DenyRemovingTheBoundary')).toMatchObject({ Effect: 'Deny', Action: 'iam:DeleteRolePermissionsBoundary' });
      expect(actions(statement(statements, 'DenyChangingTheGuardrailPolicies'))).toEqual(
        expect.arrayContaining(['iam:CreatePolicyVersion', 'iam:SetDefaultPolicyVersion', 'iam:DeletePolicy']),
      );
      const without = statement(statements, 'DenyRolesWithoutTheBoundary');
      expect(actions(without)).toEqual(expect.arrayContaining(['iam:CreateRole', 'iam:PutRolePolicy', 'iam:AttachRolePolicy']));
      expect(JSON.stringify(without.Condition?.StringNotEquals['iam:PermissionsBoundary'])).toContain(BOUNDARY_ARN);
    });

    test('denies IAM users and access keys, Organizations and account changes', () => {
      expect(actions(statement(statements, 'DenyIamUsersAndAccessKeys'))).toEqual(expect.arrayContaining(['iam:*User*', 'iam:*AccessKey*']));
      expect(actions(statement(statements, 'DenyOrganizationsAndAccountChanges'))).toEqual(
        expect.arrayContaining(['organizations:*', 'account:*']),
      );
    });

    test('denies tampering with CloudTrail, GuardDuty, AWS Config, Security Hub and the audit records', () => {
      expect(actions(statement(statements, 'DenyTamperingWithDetectiveControls'))).toEqual(
        expect.arrayContaining(['cloudtrail:*', 'guardduty:*', 'config:*', 'securityhub:*']),
      );
      const records = statement(statements, 'DenyTouchingTheAuditRecords');
      expect(actions(records)).toEqual(['kms:*', 's3:*']);
      expect(JSON.stringify(records.Resource)).toMatch(/AuditBucket.*TrailKey/);
    });

    test('denies borrowing an unbounded role: no role chaining, no trust changes, no role handed to CloudFormation', () => {
      expect(statement(statements, 'DenyRoleChaining')).toMatchObject({ Effect: 'Deny', Action: 'sts:AssumeRole', Resource: '*' });
      expect(statement(statements, 'DenyTrustPolicyChanges')).toMatchObject({ Effect: 'Deny', Action: 'iam:UpdateAssumeRolePolicy' });
      expect(statement(statements, 'DenyPassingRolesToCloudFormation')).toMatchObject({
        Effect: 'Deny',
        Action: 'iam:PassRole',
        Condition: { StringEquals: { 'iam:PassedToService': 'cloudformation.amazonaws.com' } },
      });
    });

    test('fits within the 6,144-character limit IAM sets on a managed policy', () => {
      expect(JSON.stringify({ Version: '2012-10-17', Statement: statements }).length).toBeLessThan(6144);
    });
  });

  test('outputs each environment\'s execution policy for its cdk bootstrap, the boundary and the audit bucket', () => {
    template.hasOutput('StagingCfnExecPolicyArn', { Description: Match.stringLikeRegexp('qualifier bbstg') });
    template.hasOutput('ProductionCfnExecPolicyArn', { Description: Match.stringLikeRegexp('qualifier hnb659fds') });
    template.hasOutput('PermissionsBoundaryArn', {});
    template.hasOutput('AuditBucketName', {});
  });
});
