/**
 * Unit tests for the schema migration's custom resource handlers (lambda/schema-migration): a migration task that
 * fails must fail the deployment, so the service is never updated to an image whose schema did not migrate.
 */
import { CallEcs, MigrationEvent, createHandlers } from '../lambda/schema-migration/index.js';

// Opaque stand-ins: the handlers pass ARNs through without reading them.
const TASK_ARN = 'task-arn-0123456789abcdef';

const properties: MigrationEvent['ResourceProperties'] = {
  Cluster: 'cluster-arn',
  TaskDefinition: 'migration-task-definition-arn',
  ContainerName: 'Migrate',
  Subnets: ['subnet-a', 'subnet-b'],
  SecurityGroups: ['sg-task'],
  ImageDigest: `sha256:${'0'.repeat(64)}`,
};

/**
 * A fake ECS that records each call and answers with the given responses, in order.
 *
 * @param responses - one answer per call
 */
function fakeEcs(...responses: unknown[]): { call: CallEcs; calls: { command: string; input: object }[] } {
  const calls: { command: string; input: object }[] = [];
  const call: CallEcs = (command, input) => {
    calls.push({ command, input });
    return Promise.resolve(responses.shift());
  };
  return { call, calls };
}

function stopped(exitCode: number | undefined, reason?: string): unknown {
  return { tasks: [{ lastStatus: 'STOPPED', stoppedReason: reason, containers: [{ name: 'Migrate', exitCode }] }] };
}

const running = { tasks: [{ lastStatus: 'RUNNING', containers: [{ name: 'Migrate' }] }] };

describe('schema migration handlers', () => {
  test('start the migration task in the private subnets, with no public address, on every create and update', async () => {
    for (const RequestType of ['Create', 'Update'] as const) {
      const ecs = fakeEcs({ tasks: [{ taskArn: TASK_ARN }], failures: [] });
      const result = await createHandlers(ecs.call).onEvent({ RequestType, ResourceProperties: properties });
      expect(result).toEqual({ PhysicalResourceId: 'schema-migration', Data: { TaskArn: TASK_ARN } });
      expect(ecs.calls).toEqual([
        {
          command: 'RunTask',
          input: expect.objectContaining({
            cluster: properties.Cluster,
            taskDefinition: properties.TaskDefinition,
            launchType: 'FARGATE',
            networkConfiguration: {
              awsvpcConfiguration: { subnets: properties.Subnets, securityGroups: properties.SecurityGroups, assignPublicIp: 'DISABLED' },
            },
          }) as object,
        },
      ]);
    }
  });

  test('fail the deployment when ECS cannot start the task', async () => {
    const ecs = fakeEcs({ tasks: [], failures: [{ reason: 'RESOURCE:ENI' }] });
    await expect(createHandlers(ecs.call).onEvent({ RequestType: 'Create', ResourceProperties: properties })).rejects.toThrow(
      'The migration task could not start: RESOURCE:ENI',
    );
  });

  test('keep waiting while the task runs, and finish when it exits 0', async () => {
    const ecs = fakeEcs(running, stopped(0));
    const handlers = createHandlers(ecs.call);
    const event: MigrationEvent = { RequestType: 'Update', ResourceProperties: properties, Data: { TaskArn: TASK_ARN } };
    await expect(handlers.isComplete(event)).resolves.toEqual({ IsComplete: false });
    await expect(handlers.isComplete(event)).resolves.toEqual({ IsComplete: true });
    expect(ecs.calls[0]).toEqual({ command: 'DescribeTasks', input: { cluster: properties.Cluster, tasks: [TASK_ARN] } });
  });

  test('fail the deployment when the migration exits non-zero', async () => {
    const ecs = fakeEcs(stopped(1, 'Essential container in task exited'));
    const event: MigrationEvent = { RequestType: 'Update', ResourceProperties: properties, Data: { TaskArn: TASK_ARN } };
    await expect(createHandlers(ecs.call).isComplete(event)).rejects.toThrow(/failed: exit code 1/);
  });

  test('fail the deployment when the task stops before its container ran', async () => {
    const ecs = fakeEcs(stopped(undefined, 'CannotPullContainerError'));
    const event: MigrationEvent = { RequestType: 'Create', ResourceProperties: properties, Data: { TaskArn: TASK_ARN } };
    await expect(createHandlers(ecs.call).isComplete(event)).rejects.toThrow(/exit code none \(CannotPullContainerError\)/);
  });

  test('do nothing on delete: a schema is never migrated backwards', async () => {
    const ecs = fakeEcs();
    const handlers = createHandlers(ecs.call);
    const event: MigrationEvent = { RequestType: 'Delete', PhysicalResourceId: 'schema-migration', ResourceProperties: properties };
    await expect(handlers.onEvent(event)).resolves.toEqual({ PhysicalResourceId: 'schema-migration' });
    await expect(handlers.isComplete(event)).resolves.toEqual({ IsComplete: true });
    expect(ecs.calls).toHaveLength(0);
  });
});
