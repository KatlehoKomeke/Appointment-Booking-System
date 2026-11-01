'use strict';
// The outside-in check of one BranchBook environment, which CloudWatch Synthetics runs every few minutes for the
// canary in infra/lib/constructs/observability.ts. It makes two GETs, the way a customer's browser does, through
// Route 53, WAF and the load balancer:
//
// 1. /readyz answers 200 with status UP: a task is serving and reaches the database.
// 2. One service's availability at one branch, for tomorrow in the branches' time zone, answers 200 with that day's
//    slots: the API reads the catalogue and the bookings. Tomorrow is always inside the 30-day booking window, even
//    at midnight, and a closed day still answers 200 with no slots.
//
// GET only: WAF's CAPTCHA guards bookings and cancellations, which are POSTs, so it never fires, and the canary
// creates nothing. Its settings come from the canary's environment variables. It reads and logs nothing personal,
// and keeps request and response bodies out of its reports.
//
// Plain CommonJS on purpose: the Synthetics runtime runs it as it is. The runtime's library is required inside the
// handler, so the infra tests can load the rest of this file without it.

/**
 * The calendar date one day after `now` in `timeZone`, as YYYY-MM-DD.
 *
 * @param {string} timeZone - an IANA time zone, such as Africa/Johannesburg
 * @param {Date} now - the moment to count from
 * @returns {string}
 */
function tomorrowIn(timeZone, now) {
  const parts = new Intl.DateTimeFormat('en-GB', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now);
  const part = (type) => Number(parts.find((p) => p.type === type).value);
  // Date.UTC rolls the 32nd of a month, or of December, into the next month or year.
  return new Date(Date.UTC(part('year'), part('month') - 1, part('day') + 1)).toISOString().slice(0, 10);
}

/**
 * Reads the canary's settings from its environment variables.
 *
 * @param {Record<string, string | undefined>} env - the process's environment
 * @returns {{ baseUrl: URL, branchCode: string, serviceCode: string, timeZone: string }}
 */
function settingsFrom(env) {
  const required = (name) => {
    const value = env[name];
    if (value === undefined || value === '') {
      throw new Error(`${name} is not set`);
    }
    return value;
  };
  return {
    baseUrl: new URL(required('BRANCHBOOK_BASE_URL')),
    branchCode: required('BRANCHBOOK_BRANCH_CODE'),
    serviceCode: required('BRANCHBOOK_SERVICE_CODE'),
    timeZone: required('BRANCHBOOK_TIME_ZONE'),
  };
}

/**
 * The two checks, each a step: its name, its path, and what its JSON body must say.
 *
 * @param {{ branchCode: string, serviceCode: string, timeZone: string }} settings - the canary's settings
 * @param {Date} now - the moment the run started
 * @returns {{ name: string, path: string, verify: (body: any) => void }[]}
 */
function checks(settings, now) {
  const date = tomorrowIn(settings.timeZone, now);
  const query = new URLSearchParams({ service: settings.serviceCode, date });
  return [
    {
      name: 'readiness',
      path: '/readyz',
      verify: (body) => {
        if (body.status !== 'UP') {
          throw new Error(`readiness is ${JSON.stringify(body.status)}, not "UP"`);
        }
      },
    },
    {
      name: 'availability',
      path: `/api/v1/branches/${encodeURIComponent(settings.branchCode)}/availability?${query.toString()}`,
      verify: (body) => {
        const expected = { branchCode: settings.branchCode, serviceCode: settings.serviceCode, date };
        for (const [field, value] of Object.entries(expected)) {
          if (body[field] !== value) {
            throw new Error(`availability ${field} is ${JSON.stringify(body[field])}, not ${JSON.stringify(value)}`);
          }
        }
        if (!Array.isArray(body.slots)) {
          throw new Error('availability has no list of slots');
        }
      },
    },
  ];
}

/**
 * Passes when the response is a 200 whose JSON body `verify` accepts, and fails the step otherwise.
 *
 * @param {import('node:http').IncomingMessage} response - the response
 * @param {(body: any) => void} verify - throws when the body is wrong
 * @returns {Promise<void>}
 */
function verifyResponse(response, verify) {
  return new Promise((resolve, reject) => {
    if (response.statusCode !== 200) {
      response.resume();
      reject(new Error(`expected 200, got ${String(response.statusCode)}`));
      return;
    }
    let text = '';
    response.setEncoding('utf8');
    response.on('data', (chunk) => {
      text += chunk;
    });
    response.on('error', reject);
    response.on('end', () => {
      try {
        verify(JSON.parse(text));
        resolve();
      } catch (error) {
        reject(error instanceof Error ? error : new Error(String(error)));
      }
    });
  });
}

/** The canary's entry point (`index.handler`). Each check is a step with its own SuccessPercent and Duration. */
async function handler() {
  const synthetics = require('@aws/synthetics-puppeteer');
  synthetics.getConfiguration().setConfig({
    includeRequestHeaders: false,
    includeResponseHeaders: false,
    includeRequestBody: false,
    includeResponseBody: false,
    continueOnHttpStepFailure: true,
  });
  const settings = settingsFrom(process.env);
  // AWS's managed common rule set blocks requests without a User-Agent; this one also names the canary in WAF's log.
  const userAgent = await synthetics.getCanaryUserAgentString();
  for (const check of checks(settings, new Date())) {
    await synthetics.executeHttpStep(
      check.name,
      {
        protocol: settings.baseUrl.protocol,
        hostname: settings.baseUrl.hostname,
        port: settings.baseUrl.port === '' ? 443 : Number(settings.baseUrl.port),
        method: 'GET',
        path: check.path,
        headers: { Accept: 'application/json', 'User-Agent': userAgent },
      },
      (response) => verifyResponse(response, check.verify),
    );
  }
}

module.exports = { handler, checks, settingsFrom, tomorrowIn, verifyResponse };
