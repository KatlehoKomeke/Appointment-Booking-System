/**
 * Types for `index.js`, the schema migration's custom resource handlers, so the infra tests can call them type-safely.
 *
 * @packageDocumentation
 */

/** The part of a Provider framework event the handlers read. */
export interface MigrationEvent {
  /** Create, Update or Delete. */
  readonly RequestType: 'Create' | 'Update' | 'Delete';
  /** Absent on Create. */
  readonly PhysicalResourceId?: string;
  /** The custom resource's properties, as infra/lib/schema-migration.ts sets them. */
  readonly ResourceProperties: {
    readonly Cluster: string;
    readonly TaskDefinition: string;
    readonly ContainerName: string;
    readonly Subnets: readonly string[];
    readonly SecurityGroups: readonly string[];
    readonly ImageDigest: string;
  };
  /** What onEvent returned, passed on to isComplete by the framework. */
  readonly Data?: { readonly TaskArn?: string };
}

/** A function that sends one ECS command and returns its response. */
export type CallEcs = (command: 'RunTask' | 'DescribeTasks', input: object) => Promise<unknown>;

/** The two handlers the Provider framework calls. */
export interface MigrationHandlers {
  /** Starts the migration task on Create and Update; does nothing on Delete. */
  onEvent(event: MigrationEvent): Promise<{ PhysicalResourceId: string; Data?: { TaskArn: string } }>;
  /** Done once the task stopped with exit code 0; throws, failing the deployment, on any other end. */
  isComplete(event: MigrationEvent): Promise<{ IsComplete: boolean }>;
}

/**
 * Builds the handlers around a function that calls ECS.
 *
 * @param callEcs - sends one ECS command
 */
export function createHandlers(callEcs: CallEcs): MigrationHandlers;
