/**
 * A CDK app for the tests, with the feature flags and context of infra/cdk.json, so the templates the tests check
 * are the ones `cdk synth` produces (for example, its weak cross-stack references).
 *
 * @packageDocumentation
 */
import * as fs from 'node:fs';
import * as path from 'node:path';
import { App } from 'aws-cdk-lib/core';

const cdkJson = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'cdk.json'), 'utf8')) as { context: Record<string, unknown> };

/** A new app with cdk.json's context. */
export function newApp(): App {
  return new App({ context: cdkJson.context });
}
