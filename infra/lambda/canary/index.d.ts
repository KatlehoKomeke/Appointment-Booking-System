/**
 * Types for `index.js`, the canary's script, so the infra tests can call its parts type-safely.
 *
 * @packageDocumentation
 */
import type { IncomingMessage } from 'node:http';

/** The canary's settings, from its environment variables. */
export interface CanarySettings {
  /** The environment's public address. */
  readonly baseUrl: URL;
  /** The branch whose availability is read. */
  readonly branchCode: string;
  /** The service whose availability is read. */
  readonly serviceCode: string;
  /** The branches' time zone, in which tomorrow is counted. */
  readonly timeZone: string;
}

/** One step of the canary. */
export interface CanaryCheck {
  /** The step's name, which names its metrics. */
  readonly name: string;
  /** The path and query to GET. */
  readonly path: string;
  /** Throws when the JSON body is wrong. */
  readonly verify: (body: unknown) => void;
}

/** The canary's entry point. */
export function handler(): Promise<void>;

/**
 * The checks one run makes.
 *
 * @param settings - the branch, the service and the time zone
 * @param now - the moment the run started
 */
export function checks(settings: Omit<CanarySettings, 'baseUrl'>, now: Date): CanaryCheck[];

/**
 * Reads the settings from the environment, and throws when one is missing.
 *
 * @param env - the process's environment
 */
export function settingsFrom(env: Record<string, string | undefined>): CanarySettings;

/**
 * The calendar date one day after `now` in `timeZone`, as YYYY-MM-DD.
 *
 * @param timeZone - an IANA time zone
 * @param now - the moment to count from
 */
export function tomorrowIn(timeZone: string, now: Date): string;

/**
 * Resolves when the response is a 200 whose JSON body `verify` accepts; rejects otherwise.
 *
 * @param response - the response
 * @param verify - throws when the body is wrong
 */
export function verifyResponse(response: IncomingMessage, verify: (body: unknown) => void): Promise<void>;
