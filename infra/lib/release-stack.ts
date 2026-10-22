import { Stack, StackProps } from 'aws-cdk-lib/core';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';

/** Where the deployed commit is recorded. Read by the deploy workflow to prove the new version is live. */
export const RELEASE_PARAMETER_NAME = '/branchbook/release';

/**
 * Configuration for {@link ReleaseStack}.
 */
export interface ReleaseStackProps extends StackProps {
  /**
   * The git commit being deployed: the full SHA from `GITHUB_SHA` in the pipeline, or `local` when an administrator
   * deploys from their own machine.
   */
  readonly version: string;
}

/**
 * Records which commit is deployed to the account, in Cape Town (`af-south-1`) where the application runs.
 *
 * The deploy workflow writes the commit SHA to the SSM parameter {@link RELEASE_PARAMETER_NAME} on every push to
 * `main`, then reads it back to prove the deployment really happened. It is the first workload stack in the
 * pipeline; the application stacks (DNS, data and app) join the same pipeline when the application exists.
 * A standard SSM parameter costs nothing.
 */
export class ReleaseStack extends Stack {
  /** The parameter that holds the deployed commit. */
  public readonly releaseParameter: ssm.StringParameter;

  constructor(scope: Construct, id: string, props: ReleaseStackProps) {
    super(scope, id, props);

    if (!/^([0-9a-f]{7,40}|local)$/.test(props.version)) {
      throw new Error(`version must be a git commit SHA or "local", got "${props.version}"`);
    }

    this.releaseParameter = new ssm.StringParameter(this, 'Release', {
      parameterName: RELEASE_PARAMETER_NAME,
      description: 'The git commit most recently deployed by the BranchBook deploy pipeline.',
      stringValue: props.version,
      tier: ssm.ParameterTier.STANDARD,
    });
  }
}
