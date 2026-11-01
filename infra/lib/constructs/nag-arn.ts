import { Stack, Token } from 'aws-cdk-lib/core';

/**
 * The start of an ARN in this stack, `arn:<partition>:<service>:<region>:<account>`, as cdk-nag writes it in a
 * finding's ID: the values where the stack knows them, and `<AWS::Region>`-style placeholders where it does not (a
 * synth without credentials has no account). An acknowledgment must name the finding exactly.
 *
 * @param stack - the stack the ARN is in
 * @param service - the service part of the ARN, for example `ecs`
 */
export function nagArnPrefix(stack: Stack, service: string): string {
  const part = (value: string, pseudo: string): string => (Token.isUnresolved(value) ? `<AWS::${pseudo}>` : value);
  return `arn:${part(stack.partition, 'Partition')}:${service}:${part(stack.region, 'Region')}:${part(stack.account, 'AccountId')}`;
}
