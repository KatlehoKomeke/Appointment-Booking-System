import { RemovalPolicy, Stack, StackProps } from 'aws-cdk-lib/core';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import { Construct } from 'constructs';
import { RegistryConfig } from './config';

/** The registry stack's ID: one repository serves every environment, so there is one stack, outside them all. */
export const REGISTRY_STACK_ID = 'BranchbookRegistry';

/**
 * Configuration for {@link RegistryStack}.
 */
export interface RegistryStackProps extends StackProps {
  /** The repository's name and how many images it keeps, from `infra/config/registry.json`. */
  readonly config: RegistryConfig;
}

/**
 * The ECR repository every environment pulls the application image from. The pipeline builds one ARM64 image per
 * commit, tags it with the full commit SHA and pushes it here once; staging and then production run that same image,
 * named by its digest, so what production runs is byte for byte what staging tested.
 *
 * - **Tags are immutable,** so a tag can never be pointed at different content after it was tested.
 * - **Every push is scanned** for known vulnerabilities (ECR basic scanning).
 * - **A lifecycle rule keeps the newest images** and expires the rest, which bounds storage while leaving many
 *   releases to roll back to.
 * - **The repository is retained** if the stack is deleted: the images running in each environment must outlive it.
 */
export class RegistryStack extends Stack {
  /** The repository. */
  public readonly repository: ecr.Repository;

  constructor(scope: Construct, id: string, props: RegistryStackProps) {
    super(scope, id, props);

    this.repository = new ecr.Repository(this, 'Repository', {
      repositoryName: props.config.repositoryName,
      imageTagMutability: ecr.TagMutability.IMMUTABLE,
      imageScanOnPush: true,
      encryption: ecr.RepositoryEncryption.AES_256,
      lifecycleRules: [
        {
          description: `Keep the newest ${String(props.config.imagesToKeep)} images`,
          tagStatus: ecr.TagStatus.ANY,
          maxImageCount: props.config.imagesToKeep,
        },
      ],
      removalPolicy: RemovalPolicy.RETAIN,
    });
  }
}
