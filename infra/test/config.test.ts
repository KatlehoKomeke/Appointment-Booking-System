/**
 * Unit tests for lib/config.ts: every environment's configuration is checked before any stack is built, and a
 * mistake fails synthesis with the file and the field it is in.
 */
import * as fs from 'node:fs';
import * as path from 'node:path';
import {
  CONFIG_DIRECTORY,
  ENVIRONMENT_NAMES,
  connectionPeak,
  loadEnvironmentConfig,
  loadRegistryConfig,
  parseEnvironmentConfig,
  requireImageDigest,
} from '../lib/config';

type Json = Record<string, unknown> & {
  database: Record<string, unknown>;
  service: Record<string, unknown>;
  domain: Record<string, unknown>;
  waf: Record<string, unknown>;
  network: Record<string, unknown>;
  appEnvironment: Record<string, unknown>;
};

function raw(name: string): Json {
  return JSON.parse(fs.readFileSync(path.join(CONFIG_DIRECTORY, `${name}.json`), 'utf8')) as Json;
}

/**
 * Parses production.json after changing it, as a typo or a careless edit would.
 *
 * @param change - the edit
 */
function production(change: (json: Json) => void): () => unknown {
  const json = raw('production');
  change(json);
  return () => parseEnvironmentConfig(json, 'production.json');
}

describe('environment configuration', () => {
  test.each(ENVIRONMENT_NAMES)('%s.json loads and passes every check', (name) => {
    const config = loadEnvironmentConfig(name);
    expect(config.name).toBe(name);
    expect(connectionPeak(config)).toBeLessThanOrEqual(config.database.connectionBudget);
  });

  test('keeps production on its live names and stack IDs, and gives staging its own', () => {
    const prod = loadEnvironmentConfig('production');
    const staging = loadEnvironmentConfig('staging');
    expect([prod.stackIdPrefix, prod.resourcePrefix, prod.domain.name]).toEqual(['Branchbook', 'branchbook', 'branchbook.click']);
    expect([staging.stackIdPrefix, staging.resourcePrefix, staging.domain.name]).toEqual([
      'BranchbookStaging',
      'branchbook-staging',
      'stg.branchbook.click',
    ]);
    expect(staging.domain.hostedZoneId).toBe(prod.domain.hostedZoneId);
  });

  test('runs both environments on the prod profile, with migrations left to the migration task', () => {
    for (const name of ENVIRONMENT_NAMES) {
      const { appEnvironment } = loadEnvironmentConfig(name);
      expect(appEnvironment.SPRING_PROFILES_ACTIVE).toBe('prod');
      expect(appEnvironment.BRANCHBOOK_MIGRATE_ON_START).toBe('false');
    }
  });

  test('refuses an environment it does not know', () => {
    expect(() => loadEnvironmentConfig('qa')).toThrow(/BRANCHBOOK_ENV must be one of staging, production/);
  });

  test('refuses a missing value, naming the file and the field', () => {
    expect(production((json) => delete json.database.multiAz)).toThrow('production.json: database.multiAz is missing');
  });

  test('refuses a value of the wrong type', () => {
    expect(production((json) => (json.service.maxTasks = '4'))).toThrow(/service\.maxTasks must be a whole number/);
  });

  test('refuses an unknown field, which is most likely a misspelling', () => {
    expect(production((json) => (json.service.maxTask = 4))).toThrow(/service\.maxTask is not a known setting/);
  });

  test('refuses a log retention CloudWatch does not offer', () => {
    expect(production((json) => ((json.logs as Record<string, unknown>).appRetentionDays = 10))).toThrow(
      /logs\.appRetentionDays must be one of 1, 3, 5, 7, 14, 30/,
    );
  });

  test('refuses a WAF window WAF does not offer', () => {
    expect(production((json) => (json.waf.bookingWindowSeconds = 900))).toThrow(/waf\.bookingWindowSeconds must be one of 60, 120, 300, 600/);
  });

  test('refuses task sizes Fargate does not offer', () => {
    expect(production((json) => (json.service.memoryMiB = 5120))).toThrow(/service\.memoryMiB 5120 is not a size Fargate offers/);
  });

  test('refuses pools that could open more connections than the database allows', () => {
    // 8 tasks in a deployment (4 x 200%) x 9 connections + 2 + 10 = 84 > 75.
    expect(production((json) => (json.service.dbPoolSize = 9))).toThrow(/connection budget \(75\) is below the peak the service can open \(84/);
  });

  test('refuses production without Multi-AZ, deletion protection, two tasks, or a NAT gateway per zone', () => {
    expect(production((json) => (json.database.multiAz = false))).toThrow(/production must run the database Multi-AZ/);
    expect(production((json) => (json.database.deletionProtection = false))).toThrow(/production must turn on the database deletion protection/);
    expect(production((json) => (json.service.minTasks = 1))).toThrow(/production must keep at least two tasks/);
    expect(production((json) => (json.network.natGateways = 1))).toThrow(/one NAT gateway per availability zone/);
  });

  test('refuses a synthetic-traffic bypass in production', () => {
    expect(production((json) => (json.waf.syntheticTrafficBypass = true))).toThrow(/production must not let synthetic traffic skip the CAPTCHA/);
  });

  test('refuses a secret, or a variable the stack computes, in the app environment', () => {
    expect(production((json) => (json.appEnvironment.BRANCHBOOK_SMS_RECIPIENT_CAP_KEY = 'x'))).toThrow(/is set by the stack itself/);
    expect(production((json) => (json.appEnvironment.SOME_API_TOKEN = 'x'))).toThrow(/looks like a secret/);
    expect(production((json) => (json.appEnvironment.DB_URL = 'jdbc:postgresql://x'))).toThrow(/is set by the stack itself/);
  });

  test('refuses migrate-on-start, another profile, or an unredacted inbox', () => {
    expect(production((json) => (json.appEnvironment.BRANCHBOOK_MIGRATE_ON_START = 'true'))).toThrow(/BRANCHBOOK_MIGRATE_ON_START must be "false"/);
    expect(production((json) => (json.appEnvironment.SPRING_PROFILES_ACTIVE = 'default'))).toThrow(/SPRING_PROFILES_ACTIVE must be "prod"/);
    expect(
      production((json) => {
        json.appEnvironment.BRANCHBOOK_INBOX_ENABLED = 'true';
        json.appEnvironment.BRANCHBOOK_INBOX_REDACT_REFERENCES = 'false';
      }),
    ).toThrow(/inbox on without BRANCHBOOK_INBOX_REDACT_REFERENCES=true/);
  });

  test('refuses a domain too long for the confirmation SMS link (BR-18), or outside its hosted zone', () => {
    expect(production((json) => (json.domain.name = 'a-much-too-long.branchbook.click'))).toThrow(/at most 20 characters/);
    expect(production((json) => (json.domain.name = 'branchbook.link'))).toThrow(/is not in the hosted zone branchbook\.click/);
  });

  test('refuses the metrics switches in the app environment, which the stack sets for the service alone', () => {
    expect(production((json) => (json.appEnvironment.BRANCHBOOK_METRICS_CLOUDWATCH_ENABLED = 'true'))).toThrow(/is set by the stack itself/);
    expect(production((json) => (json.appEnvironment.BRANCHBOOK_METRICS_CLOUDWATCH_NAMESPACE = 'X'))).toThrow(/is set by the stack itself/);
  });

  test('refuses a namespace CloudWatch reserves for AWS, or an export step no alarm period matches', () => {
    const monitoring = (json: Json) => json.monitoring as Record<string, unknown>;
    expect(production((json) => (monitoring(json).metricsNamespace = 'AWS/BranchBook'))).toThrow(/monitoring\.metricsNamespace must be a CloudWatch namespace/);
    expect(production((json) => (monitoring(json).metricsStepSeconds = 30))).toThrow(/monitoring\.metricsStepSeconds must be one of 60, 120, 300/);
  });

  test('refuses a canary run longer than its schedule, and a production canary whose artifacts die with the stack', () => {
    const canary = (json: Json) => json.canary as Record<string, unknown>;
    expect(production((json) => (canary(json).timeoutSeconds = 301))).toThrow(/canary\.timeoutSeconds is longer than canary\.scheduleMinutes/);
    expect(production((json) => (canary(json).autoDeleteArtifacts = true))).toThrow(/production must keep the canary's artifacts/);
    expect(production((json) => (canary(json).branchCode = 'san'))).toThrow(/canary\.branchCode must be a branch code/);
  });
});

describe('registry configuration', () => {
  test('names the one repository both environments pull from, in their Region', () => {
    const registry = loadRegistryConfig();
    expect(registry).toEqual({ region: 'af-south-1', repositoryName: 'branchbook', imagesToKeep: 50 });
    for (const name of ENVIRONMENT_NAMES) {
      expect(loadEnvironmentConfig(name).region).toBe(registry.region);
    }
  });
});

describe('requireImageDigest', () => {
  test('accepts a sha256 digest', () => {
    const digest = `sha256:${'a1'.repeat(32)}`;
    expect(requireImageDigest(digest)).toBe(digest);
  });

  test.each([['nothing', undefined], ['a tag', 'latest'], ['a short digest', 'sha256:abc']])('refuses %s', (_label, value) => {
    expect(() => requireImageDigest(value)).toThrow(/BRANCHBOOK_IMAGE_DIGEST must be the image's digest/);
  });
});
