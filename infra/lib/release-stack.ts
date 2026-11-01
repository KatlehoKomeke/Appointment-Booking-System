import { Stack, StackProps } from 'aws-cdk-lib/core';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';
import { IMAGE_DIGEST } from './config';

/**
 * The parameter that records the commit an environment runs, under its resource prefix: `/branchbook/release` for
 * production, `/branchbook-staging/release` for staging. The pipeline reads it back to prove the version is live.
 *
 * @param resourcePrefix - the environment's `resourcePrefix`
 */
export function releaseParameterName(resourcePrefix: string): string {
  return `/${resourcePrefix}/release`;
}

/**
 * The parameter that records the image digest an environment runs. The pipeline's rollback reads it to redeploy the
 * last good image.
 *
 * @param resourcePrefix - the environment's `resourcePrefix`
 */
export function releaseImageParameterName(resourcePrefix: string): string {
  return `/${resourcePrefix}/release-image`;
}

/** Production's release record, the one the account stack's deploy role reads. */
export const RELEASE_PARAMETER_NAME = releaseParameterName('branchbook');

/**
 * Configuration for {@link ReleaseStack}.
 */
export interface ReleaseStackProps extends StackProps {
  /** The environment's `resourcePrefix`, which names both parameters. */
  readonly resourcePrefix: string;

  /**
   * The git commit being deployed: the full SHA from `GITHUB_SHA` in the pipeline, or `local` when an administrator
   * deploys from their own machine.
   */
  readonly version: string;

  /** The digest of the image the environment now runs, `sha256:` and 64 hex digits. */
  readonly imageDigest: string;
}

/**
 * Records which commit, and which image, an environment runs, in Cape Town (`af-south-1`) where it runs.
 *
 * The pipeline deploys this stack last, after the app stack's smoke test passes, so the record names a version that
 * is live and works; it then reads the commit back to prove the deployment really happened, and a rollback reads the
 * image digest. Standard SSM parameters cost nothing.
 */
export class ReleaseStack extends Stack {
  /** The parameter that holds the deployed commit. */
  public readonly releaseParameter: ssm.StringParameter;

  /** The parameter that holds the deployed image digest. */
  public readonly releaseImageParameter: ssm.StringParameter;

  constructor(scope: Construct, id: string, props: ReleaseStackProps) {
    super(scope, id, props);

    if (!/^([0-9a-f]{7,40}|local)$/.test(props.version)) {
      throw new Error(`version must be a git commit SHA or "local", got "${props.version}"`);
    }
    if (!IMAGE_DIGEST.test(props.imageDigest)) {
      throw new Error(`imageDigest must be sha256: and 64 hex digits, got "${props.imageDigest}"`);
    }

    this.releaseParameter = new ssm.StringParameter(this, 'Release', {
      parameterName: releaseParameterName(props.resourcePrefix),
      description: 'The git commit most recently deployed by the BranchBook deploy pipeline.',
      stringValue: props.version,
      tier: ssm.ParameterTier.STANDARD,
    });

    this.releaseImageParameter = new ssm.StringParameter(this, 'ReleaseImage', {
      parameterName: releaseImageParameterName(props.resourcePrefix),
      description: 'The image digest most recently deployed by the BranchBook deploy pipeline; rollback reads it.',
      stringValue: props.imageDigest,
      tier: ssm.ParameterTier.STANDARD,
    });
  }
}
