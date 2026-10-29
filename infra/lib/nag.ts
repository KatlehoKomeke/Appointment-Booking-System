/**
 * The finding IDs of the cdk-nag (AwsSolutions) rules this app acknowledges more than once. IAM rules report one
 * finding per policy or resource, as `RuleId[FindingId]`, and an acknowledgment must name the finding exactly, so
 * nothing broader than the one finding is ever accepted. infra/test/nag.test.ts fails on any finding not acknowledged.
 *
 * @packageDocumentation
 */
import { CfnElement, Stack } from 'aws-cdk-lib/core';
import { IConstruct } from 'constructs';

/** A role that uses AWSLambdaBasicExecutionRole, which grants only writing the function's own logs. */
export const LAMBDA_BASIC_EXECUTION_FINDING =
  'AwsSolutions-IAM4[Policy::arn:<AWS::Partition>:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole]';

/** A statement on `Resource: *`. */
export const ANY_RESOURCE_FINDING = 'AwsSolutions-IAM5[Resource::*]';

/**
 * A statement that lets a caller invoke any version of one function (`<function ARN>:*`), as the Provider framework
 * grants itself.
 *
 * @param fn - the function (its L2 construct)
 */
export function anyVersionOfFinding(fn: IConstruct): string {
  const resource = fn.node.defaultChild as CfnElement;
  return `AwsSolutions-IAM5[Resource::<${Stack.of(fn).getLogicalId(resource)}.Arn>:*]`;
}
