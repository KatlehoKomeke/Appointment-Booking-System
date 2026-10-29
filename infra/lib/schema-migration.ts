import * as path from 'node:path';
import { CustomResource, Duration, RemovalPolicy, Validations } from 'aws-cdk-lib/core';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as cr from 'aws-cdk-lib/custom-resources';
import { Construct } from 'constructs';
import { ANY_RESOURCE_FINDING, LAMBDA_BASIC_EXECUTION_FINDING, anyVersionOfFinding } from './nag';

/** How often the deployment asks ECS whether the migration task has stopped. */
const POLL_INTERVAL = Duration.seconds(15);

/**
 * Configuration for {@link SchemaMigration}.
 */
export interface SchemaMigrationProps {
  /** The cluster the task runs in. */
  readonly cluster: ecs.ICluster;
  /** The one-off migration task definition. */
  readonly taskDefinition: ecs.FargateTaskDefinition;
  /** The container whose exit code decides the outcome. */
  readonly containerName: string;
  /** The private subnets the task runs in. */
  readonly subnets: ec2.ISubnet[];
  /** The security group that lets the task reach the database and AWS's APIs. */
  readonly securityGroup: ec2.ISecurityGroup;
  /** The image digest; a new digest is what makes CloudFormation run the migration again. */
  readonly imageDigest: string;
  /** How long the deployment waits for the migration before it fails. */
  readonly timeout: Duration;
  /** How long the handlers' logs are kept. */
  readonly logRetention: logs.RetentionDays;
}

/**
 * Migrates the schema as a step of the deployment, not of the app's start-up: a custom resource that runs the
 * migration task once for every new image digest and waits for it.
 *
 * The app's service depends on {@link resource}, so CloudFormation updates the service only after the migration
 * task has exited 0. A task that exits non-zero, cannot start, or outlives the timeout fails the resource, which
 * fails the deployment and rolls the stack back; the running tasks keep serving the previous version throughout.
 *
 * On a rollback CloudFormation runs the previous digest's migration again. Flyway then finds nothing to apply (a
 * failed migration is rolled back in its own transaction, and an older image ignores newer migrations), so that run
 * is a no-op that exits 0.
 */
export class SchemaMigration extends Construct {
  /** The custom resource; whatever must wait for the migration depends on it. */
  public readonly resource: CustomResource;

  constructor(scope: Construct, id: string, props: SchemaMigrationProps) {
    super(scope, id);

    const logGroup = new logs.LogGroup(this, 'Logs', {
      retention: props.logRetention,
      removalPolicy: RemovalPolicy.DESTROY,
    });
    const handler = (name: string, entry: string, description: string): lambda.Function =>
      new lambda.Function(this, name, {
        runtime: lambda.Runtime.NODEJS_24_X,
        architecture: lambda.Architecture.ARM_64,
        code: lambda.Code.fromAsset(path.join(__dirname, '..', 'lambda', 'schema-migration'), { exclude: ['*.d.ts'] }),
        handler: `index.${entry}`,
        description,
        timeout: Duration.seconds(30),
        logGroup,
      });
    const onEvent = handler('OnEvent', 'onEvent', 'BranchBook: starts the schema migration task during a deployment');
    const isComplete = handler('IsComplete', 'isComplete', 'BranchBook: waits for the schema migration task and fails the deployment if it fails');

    // RunTask on this task definition alone, and PassRole on its two roles alone.
    props.taskDefinition.grantRun(onEvent);
    // DescribeTasks in this cluster alone; the task's ID is assigned at run time, so the cluster is the boundary.
    isComplete.addToRolePolicy(
      new iam.PolicyStatement({
        actions: ['ecs:DescribeTasks'],
        resources: ['*'],
        conditions: { ArnEquals: { 'ecs:cluster': props.cluster.clusterArn } },
      }),
    );
    Validations.of(isComplete).acknowledge({
      id: ANY_RESOURCE_FINDING,
      reason: 'DescribeTasks is limited to this cluster by the ecs:cluster condition; the task\'s ARN is not known until it runs.',
    });

    const provider = new cr.Provider(this, 'Provider', {
      onEventHandler: onEvent,
      isCompleteHandler: isComplete,
      queryInterval: POLL_INTERVAL,
      totalTimeout: props.timeout,
      logGroup,
    });

    this.resource = new CustomResource(this, 'Resource', {
      serviceToken: provider.serviceToken,
      resourceType: 'Custom::SchemaMigration',
      properties: {
        Cluster: props.cluster.clusterArn,
        TaskDefinition: props.taskDefinition.taskDefinitionArn,
        ContainerName: props.containerName,
        Subnets: props.subnets.map((subnet) => subnet.subnetId),
        SecurityGroups: [props.securityGroup.securityGroupId],
        ImageDigest: props.imageDigest,
      },
    });

    // The cdk-nag (AwsSolutions) findings this construct accepts, each with its reason.
    const frameworkFunctions = ['framework-isComplete', 'framework-onTimeout'].map((id) => provider.node.findChild(id));
    Validations.of(this).acknowledge(
      {
        id: LAMBDA_BASIC_EXECUTION_FINDING,
        reason: 'The handlers, and the Provider framework\'s, use AWSLambdaBasicExecutionRole, which grants only writing their own logs.',
      },
      ...[onEvent, isComplete, ...frameworkFunctions].map((fn) => ({
        id: anyVersionOfFinding(fn),
        reason: 'The Provider framework invokes this one function, in whichever version is current (:*), and nothing else.',
      })),
      {
        id: 'AwsSolutions-SF1',
        reason: 'The Provider framework\'s waiter state machine only polls isComplete; every outcome is in the handlers\' log group and in CloudFormation\'s events.',
      },
      {
        id: 'AwsSolutions-SF2',
        reason: 'The Provider framework\'s waiter state machine is a short polling loop; X-Ray tracing would add nothing the handlers\' logs do not show.',
      },
    );
  }
}
