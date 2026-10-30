import { Stack, Validations } from 'aws-cdk-lib/core';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as fis from 'aws-cdk-lib/aws-fis';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as rds from 'aws-cdk-lib/aws-rds';
import { Construct } from 'constructs';
import { EnvironmentConfig } from '../config';
import { ANY_RESOURCE_FINDING } from '../nag';
import { nagArnPrefix } from './nag-arn';

/** FIS's action that stops ECS tasks (the ECS API's StopTask). */
export const STOP_TASK_ACTION = 'aws:ecs:stop-task';

/** FIS's action that reboots RDS instances (the RDS API's RebootDBInstance), here with a forced Multi-AZ failover. */
export const REBOOT_DATABASE_ACTION = 'aws:rds:reboot-db-instances';

/** A FIS stop condition's source for a CloudWatch alarm. */
const ALARM_STOP_CONDITION = 'aws:cloudwatch:alarm';

/**
 * Configuration for {@link FaultInjection}.
 */
export interface FaultInjectionProps {
  /** The environment: its name, prefix and whether its database is Multi-AZ. */
  readonly config: EnvironmentConfig;
  /** The service whose tasks one experiment stops. */
  readonly service: ecs.FargateService;
  /** The database the other experiment fails over. */
  readonly database: rds.DatabaseInstance;
  /** The alarms that stop an experiment when they are raised: the app failing requests, and no healthy task. */
  readonly stopConditions: readonly cloudwatch.IAlarm[];
}

/**
 * AWS FIS experiment templates that prove the environment survives the failures it is built for. A template does
 * nothing by itself: an operator starts an experiment from it, and FIS stops the experiment, or refuses to start it,
 * while any stop-condition alarm is raised.
 *
 * - **Stop one task**: ECS stops one of the service's tasks, chosen at random. The load balancer keeps serving from the
 *   others, ECS starts a replacement, and the outbox's other dispatchers keep sending.
 * - **Database failover** (Multi-AZ only): RDS reboots the instance with a forced failover to the standby in the other
 *   zone. The tasks' connection pools reconnect to the same endpoint once DNS points at the new primary.
 *
 * The experiments run as their own role, which may stop only this cluster's tasks and reboot only this database.
 */
export class FaultInjection extends Construct {
  /** The role FIS assumes to run the experiments. */
  public readonly role: iam.Role;

  /** The template that stops one task. */
  public readonly stopTask: fis.CfnExperimentTemplate;

  /** The template that fails the database over, where it is Multi-AZ. */
  public readonly databaseFailover?: fis.CfnExperimentTemplate;

  constructor(scope: Construct, id: string, props: FaultInjectionProps) {
    super(scope, id);
    const { config, service, database } = props;
    const stack = Stack.of(this);
    const cluster = service.cluster;

    // FIS may assume the role only for this account's experiments (the confused-deputy problem).
    this.role = new iam.Role(this, 'Role', {
      description: `BranchBook ${config.name}: lets AWS FIS stop one task of the service and fail the database over, and nothing else.`,
      assumedBy: new iam.ServicePrincipal('fis.amazonaws.com').withConditions({
        StringEquals: { 'aws:SourceAccount': stack.account },
        ArnLike: { 'aws:SourceArn': stack.formatArn({ service: 'fis', resource: 'experiment', resourceName: '*' }) },
      }),
    });
    // A task's ID is assigned when it starts, so this cluster's task ARNs are the narrowest resource; the condition
    // repeats the cluster for the actions that accept it.
    const inThisCluster = { ArnEquals: { 'ecs:cluster': cluster.clusterArn } };
    this.role.addToPolicy(
      new iam.PolicyStatement({
        sid: 'StopAndDescribeThisClustersTasks',
        actions: ['ecs:StopTask', 'ecs:DescribeTasks'],
        resources: [stack.formatArn({ service: 'ecs', resource: 'task', resourceName: `${cluster.clusterName}/*` })],
        conditions: inThisCluster,
      }),
    );
    this.role.addToPolicy(
      new iam.PolicyStatement({
        sid: 'ListThisClustersTasks',
        actions: ['ecs:ListTasks'],
        resources: ['*'],
        conditions: inThisCluster,
      }),
    );
    // FIS resolves targets through the Resource Groups Tagging API, which accepts no resource ARN.
    this.role.addToPolicy(new iam.PolicyStatement({ sid: 'ResolveTargets', actions: ['tag:GetResources'], resources: ['*'] }));

    const stopConditions = props.stopConditions.map((alarm) => ({ source: ALARM_STOP_CONDITION, value: alarm.alarmArn }));
    const prefix = config.resourcePrefix;

    const tasks = 'ServiceTasks';
    this.stopTask = new fis.CfnExperimentTemplate(this, 'StopOneTask', {
      description: `BranchBook ${config.name}: stop one of the service's tasks. The others keep serving and ECS replaces it.`,
      roleArn: this.role.roleArn,
      stopConditions,
      targets: {
        [tasks]: {
          resourceType: 'aws:ecs:task',
          parameters: { cluster: cluster.clusterName, service: service.serviceName },
          selectionMode: 'COUNT(1)',
        },
      },
      actions: {
        StopTask: { actionId: STOP_TASK_ACTION, description: 'Stop the chosen task', targets: { Tasks: tasks } },
      },
      tags: { Name: `${prefix}-stop-one-task` },
    });

    if (config.database.multiAz) {
      this.role.addToPolicy(
        new iam.PolicyStatement({
          sid: 'RebootThisDatabase',
          actions: ['rds:RebootDBInstance', 'rds:DescribeDBInstances'],
          resources: [database.instanceArn],
        }),
      );
      const instance = 'Database';
      this.databaseFailover = new fis.CfnExperimentTemplate(this, 'DatabaseFailover', {
        description: `BranchBook ${config.name}: reboot the database with a forced failover to its standby in the other zone.`,
        roleArn: this.role.roleArn,
        stopConditions,
        targets: {
          [instance]: { resourceType: 'aws:rds:db', resourceArns: [database.instanceArn], selectionMode: 'ALL' },
        },
        actions: {
          Failover: {
            actionId: REBOOT_DATABASE_ACTION,
            description: 'Reboot with a forced Multi-AZ failover',
            parameters: { forceFailover: 'true' },
            targets: { DBInstances: instance },
          },
        },
        tags: { Name: `${prefix}-database-failover` },
      });
    }

    // The cdk-nag (AwsSolutions) findings this construct accepts, each with its reason.
    Validations.of(this.role).acknowledge(
      {
        id: ANY_RESOURCE_FINDING,
        reason: 'tag:GetResources accepts no resource ARN, and ecs:ListTasks is limited to this cluster by the ecs:cluster condition.',
      },
      {
        id: `AwsSolutions-IAM5[Resource::${nagArnPrefix(stack, 'ecs')}:task/<${stack.getLogicalId(cluster.node.defaultChild as ecs.CfnCluster)}>/*]`,
        reason: 'A task\'s ID is assigned when it starts, so the experiment may stop any task of this one cluster, and of no other.',
      },
    );
  }
}
