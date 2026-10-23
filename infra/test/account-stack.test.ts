/**
 * Unit tests for {@link AccountStack}: they prove the security properties of the GitHub-to-AWS connection on the
 * synthesised CloudFormation template, so a regression fails CI before anything reaches AWS.
 */
import { App } from 'aws-cdk-lib/core';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { AccountStack, AccountStackProps, DEPLOY_ROLE_NAME } from '../lib/account-stack';

const validProps: AccountStackProps = {
  env: { account: '111111111111', region: 'us-east-1' },
  githubRepository: 'KatlehoKomeke/Appointment-Booking-System',
  githubOwnerId: '88820937',
  githubRepositoryId: '1423867330',
  githubEnvironment: 'aws-demo',
  alertEmail: 'alerts@example.com',
  monthlyBudgetUsd: 90,
  releaseRegion: 'af-south-1',
};

/** The parts of a synthesised `AWS::IAM::Policy` resource that the assertions below read. */
interface PolicyResource {
  Properties: { PolicyDocument: { Statement: { Action: string | string[]; Resource: unknown }[] } };
}

function synth(overrides: Partial<AccountStackProps> = {}): Template {
  const app = new App();
  return Template.fromStack(new AccountStack(app, 'Account', { ...validProps, ...overrides }));
}

describe('AccountStack', () => {
  test('trusts GitHub Actions as an OpenID Connect provider for AWS STS', () => {
    synth().hasResourceProperties('AWS::IAM::OIDCProvider', {
      Url: 'https://token.actions.githubusercontent.com',
      ClientIdList: ['sts.amazonaws.com'],
    });
  });

  test('lets only this repository, identified by its immutable IDs, in the aws-demo environment, assume the deploy role', () => {
    synth().hasResourceProperties('AWS::IAM::Role', {
      RoleName: DEPLOY_ROLE_NAME,
      MaxSessionDuration: 3600,
      AssumeRolePolicyDocument: {
        Statement: [
          Match.objectLike({
            Action: 'sts:AssumeRoleWithWebIdentity',
            Condition: {
              StringEquals: {
                'token.actions.githubusercontent.com:aud': 'sts.amazonaws.com',
                'token.actions.githubusercontent.com:sub':
                  'repo:KatlehoKomeke@88820937/Appointment-Booking-System@1423867330:environment:aws-demo',
              },
            },
          }),
        ],
      },
    });
  });

  test('grants the deploy role only the CDK bootstrap roles and a read of the release record', () => {
    const template = synth();
    const policies = template.findResources('AWS::IAM::Policy') as Record<string, PolicyResource>;
    const statements = Object.values(policies).flatMap((policy) => policy.Properties.PolicyDocument.Statement);
    expect(statements).toHaveLength(2);
    expect(statements[0].Action).toBe('sts:AssumeRole');
    expect(JSON.stringify(statements[0].Resource)).toContain(':role/cdk-hnb659fds-*');
    expect(statements[1].Action).toBe('ssm:GetParameter');
    expect(JSON.stringify(statements[1].Resource)).toContain(':ssm:af-south-1:');
    expect(JSON.stringify(statements[1].Resource)).toContain(':parameter/branchbook/release');
    expect(JSON.stringify(statements)).not.toContain('"*"');
  });

  test('e-mails budget alerts at 50, 80 and 100 percent of the monthly limit', () => {
    synth().hasResourceProperties('AWS::Budgets::Budget', {
      Budget: Match.objectLike({ BudgetLimit: { Amount: 90, Unit: 'USD' }, TimeUnit: 'MONTHLY' }),
      NotificationsWithSubscribers: [50, 80, 100].map((threshold) =>
        Match.objectLike({ Notification: Match.objectLike({ Threshold: threshold }) }),
      ),
    });
  });

  test.each([
    ['a repository without an owner', { githubRepository: 'Appointment-Booking-System' }, /owner\/name/],
    ['a non-numeric repository ID', { githubRepositoryId: 'Appointment-Booking-System' }, /numeric IDs/],
    ['an environment name with spaces', { githubEnvironment: 'aws demo' }, /environment name/],
    ['a missing alert address', { alertEmail: '' }, /BRANCHBOOK_ALERT_EMAIL/],
    ['a zero budget', { monthlyBudgetUsd: 0 }, /positive number/],
    ['a misspelt release Region', { releaseRegion: 'cape-town' }, /AWS Region/],
  ])('refuses to synthesise with %s', (_label, overrides, message) => {
    expect(() => synth(overrides as Partial<AccountStackProps>)).toThrow(message);
  });
});
