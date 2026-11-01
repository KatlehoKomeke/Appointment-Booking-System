'use strict';
// Runs BranchBook's schema migration as a one-off ECS task during a CloudFormation deployment, for the custom
// resource in infra/lib/schema-migration.ts (CDK's Provider framework).
//
// - onEvent starts the task on Create and Update; there is nothing to undo on Delete, since a schema is never
//   migrated backwards by deleting a stack.
// - isComplete is polled until the task stops. It answers done when the migration container exited 0, and throws
//   otherwise: the framework then fails the resource, CloudFormation fails the deployment, and the service, which
//   depends on this resource, is never updated to an image whose schema did not migrate.
//
// Plain CommonJS on purpose: the Lambda Node.js runtime runs it as it is, with the AWS SDK it ships, so nothing is
// bundled at deploy time. Nothing personal is logged: only task ARNs, exit codes and ECS's stop reasons.

/** The resource's one physical ID: it never changes, so an update never makes CloudFormation delete the old one. */
const DEFAULT_PHYSICAL_ID = 'schema-migration';

/**
 * Builds the two handlers around one function that calls ECS, so the tests can pass a fake.
 *
 * @param {(command: 'RunTask' | 'DescribeTasks', input: object) => Promise<any>} callEcs
 */
function createHandlers(callEcs) {
  async function onEvent(event) {
    const physicalResourceId = event.PhysicalResourceId ?? DEFAULT_PHYSICAL_ID;
    if (event.RequestType === 'Delete') {
      return { PhysicalResourceId: physicalResourceId };
    }
    const props = event.ResourceProperties;
    const result = await callEcs('RunTask', {
      cluster: props.Cluster,
      taskDefinition: props.TaskDefinition,
      launchType: 'FARGATE',
      count: 1,
      startedBy: 'cloudformation',
      networkConfiguration: {
        awsvpcConfiguration: {
          subnets: props.Subnets,
          securityGroups: props.SecurityGroups,
          assignPublicIp: 'DISABLED',
        },
      },
    });
    const task = result.tasks?.[0];
    if ((result.failures ?? []).length > 0 || task?.taskArn === undefined) {
      const reasons = (result.failures ?? []).map((f) => f.reason).join(', ') || 'no task was started';
      throw new Error(`The migration task could not start: ${reasons}`);
    }
    console.log(`Started migration task ${task.taskArn} for image ${props.ImageDigest}`);
    return { PhysicalResourceId: physicalResourceId, Data: { TaskArn: task.taskArn } };
  }

  async function isComplete(event) {
    if (event.RequestType === 'Delete') {
      return { IsComplete: true };
    }
    const props = event.ResourceProperties;
    const taskArn = event.Data?.TaskArn;
    const result = await callEcs('DescribeTasks', { cluster: props.Cluster, tasks: [taskArn] });
    const task = result.tasks?.[0];
    if (task === undefined) {
      throw new Error(`The migration task ${taskArn} is not known to ECS`);
    }
    if (task.lastStatus !== 'STOPPED') {
      return { IsComplete: false };
    }
    const container = (task.containers ?? []).find((c) => c.name === props.ContainerName);
    if (container?.exitCode === 0) {
      console.log(`Migration task ${taskArn} exited 0`);
      return { IsComplete: true };
    }
    const exit = container?.exitCode ?? 'none';
    const reason = container?.reason ?? task.stoppedReason ?? 'no reason given';
    throw new Error(`The migration task ${taskArn} failed: exit code ${exit} (${reason}). See its log stream.`);
  }

  return { onEvent, isComplete };
}

let client;

/**
 * Calls ECS with the SDK the Lambda runtime ships, loaded on first use so the tests never need it.
 *
 * @param {'RunTask' | 'DescribeTasks'} command
 * @param {object} input
 */
async function callEcs(command, input) {
  const sdk = require('@aws-sdk/client-ecs');
  client ??= new sdk.ECSClient({});
  return client.send(new sdk[`${command}Command`](input));
}

const handlers = createHandlers(callEcs);

module.exports = { createHandlers, onEvent: handlers.onEvent, isComplete: handlers.isComplete };
