import { RemovalPolicy, Validations } from 'aws-cdk-lib/core';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as cr from 'aws-cdk-lib/custom-resources';
import { Construct } from 'constructs';
import { ANY_RESOURCE_FINDING } from './nag';

/** The scope of a web ACL attached to a load balancer, and so of the API key its CAPTCHA uses. */
const SCOPE = 'REGIONAL';

/** The CAPTCHA JavaScript API's file, under the account's integration URL. */
const CAPTCHA_SCRIPT = 'jsapi.js';

/**
 * Configuration for {@link CaptchaApiKey}.
 */
export interface CaptchaApiKeyProps {
  /** The domain the SPA is served from; the key is valid for that token domain only. */
  readonly domainName: string;
  /** How long the custom resource handler's logs are kept. */
  readonly logRetention: logs.RetentionDays;
}

/**
 * The API key and script URL the SPA needs to show AWS WAF's CAPTCHA puzzle (`AwsWafCaptcha.renderCaptcha`). AWS
 * binds the key to the token domain, so it is public by design and goes to the task as a plain variable.
 *
 * Two AWS SDK custom resources, since CloudFormation has no resource for either:
 *
 * - **The key** (`CreateAPIKey`, token domain = the environment's domain). Its physical ID is the key itself. A new
 *   domain creates a new key, and CloudFormation then deletes the old one (`DeleteAPIKey`); a deploy that changes
 *   nothing calls nothing. Deleting a key that is already gone is not an error, so a delete or a rollback never
 *   sticks.
 * - **The integration URL** (`ListAPIKeys`, `ApplicationIntegrationURL`), the account's per-Region address of the
 *   CAPTCHA script, read once the key exists.
 */
export class CaptchaApiKey extends Construct {
  /** The API key. */
  public readonly apiKey: string;

  /** The CAPTCHA JavaScript API's URL: the integration URL followed by `jsapi.js`. */
  public readonly scriptUrl: string;

  constructor(scope: Construct, id: string, props: CaptchaApiKeyProps) {
    super(scope, id);

    // The API-key actions act on the account's keys for the scope and take no resource ARN.
    const policy = cr.AwsCustomResourcePolicy.fromStatements([
      new iam.PolicyStatement({
        actions: ['wafv2:CreateAPIKey', 'wafv2:DeleteAPIKey', 'wafv2:ListAPIKeys'],
        resources: ['*'],
      }),
    ]);
    const logGroup = new logs.LogGroup(this, 'Logs', {
      retention: props.logRetention,
      removalPolicy: RemovalPolicy.DESTROY,
    });

    const create: cr.AwsSdkCall = {
      service: 'WAFV2',
      action: 'CreateAPIKey',
      parameters: { Scope: SCOPE, TokenDomains: [props.domainName] },
      physicalResourceId: cr.PhysicalResourceId.fromResponse('APIKey'),
      outputPaths: ['APIKey'],
    };
    const key = new cr.AwsCustomResource(this, 'Key', {
      resourceType: 'Custom::WafCaptchaApiKey',
      onCreate: create,
      onUpdate: create,
      onDelete: {
        service: 'WAFV2',
        action: 'DeleteAPIKey',
        parameters: { Scope: SCOPE, APIKey: new cr.PhysicalResourceIdReference() },
        ignoreErrorCodesMatching: 'WAFNonexistentItemException',
      },
      policy,
      installLatestAwsSdk: false,
      logGroup,
    });
    this.apiKey = key.getResponseField('APIKey');

    const list: cr.AwsSdkCall = {
      service: 'WAFV2',
      action: 'ListAPIKeys',
      parameters: { Scope: SCOPE, Limit: 1 },
      physicalResourceId: cr.PhysicalResourceId.of(`captcha-integration-url-${props.domainName}`),
      outputPaths: ['ApplicationIntegrationURL'],
    };
    const integration = new cr.AwsCustomResource(this, 'IntegrationUrl', {
      resourceType: 'Custom::WafCaptchaIntegrationUrl',
      onCreate: list,
      onUpdate: list,
      policy,
      installLatestAwsSdk: false,
      logGroup,
    });
    integration.node.addDependency(key);
    this.scriptUrl = `${integration.getResponseField('ApplicationIntegrationURL')}${CAPTCHA_SCRIPT}`;

    Validations.of(this).acknowledge({
      id: ANY_RESOURCE_FINDING,
      reason: 'CreateAPIKey, DeleteAPIKey and ListAPIKeys act on the account\'s CAPTCHA keys for the scope and accept no resource ARN.',
    });
  }
}
