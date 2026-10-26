/**
 * The fixtures every BranchBook end-to-end test uses, and the small steps the specs share.
 *
 * - The browser guard fails a test, after it has run, if the page broke the Content Security Policy or wrote an error
 *   to the console that the test did not declare. The app's CSP allows nothing but its own origin, so a violation is
 *   a real defect, and a console error is usually one too.
 * - The booking fixture picks a demo customer and a free time by asking the API, and books again with another when
 *   the API answers that the customer already holds one (BR-10) or is elsewhere at that time (BR-9). The tests share
 *   one database that is never reset between runs, so a fixed number or time would fail the second run.
 *
 * @packageDocumentation
 */
import {
  test as base,
  expect,
  type APIRequestContext,
  type Page,
  type Response,
} from '@playwright/test';

/** The customer and the time one booking attempt uses. Every value is synthetic. */
export interface Candidate {
  /** A demo number, written as a customer types it, for example "082 555 0142". */
  mobile: string;
  firstName: string;
  lastName: string;
  branch: { code: string; name: string };
  service: { code: string; name: string };
  /** The date in SAST, `YYYY-MM-DD`. */
  date: string;
  /** The start in SAST, `HH:MM`, as the time grid shows it. */
  time: string;
}

/** What a successful {@link BookingFixture.book} returns. */
export interface Booked {
  /** The candidate the API accepted. */
  candidate: Candidate;
  /** The appointment's reference, `APT-XXXX-XXXX`. */
  reference: string;
}

/** Options for {@link BookingFixture.book}. */
export interface BookOptions {
  /** Book with this number on every attempt instead of a fresh one, for example the failing 082 555 0000. */
  mobile?: string;
}

/** The booking fixture. */
export interface BookingFixture {
  /**
   * Runs `attempt` with a fresh candidate until the API books it.
   *
   * `attempt` drives the browser from wherever it likes up to Confirm, and returns the response to the booking's
   * POST. On 409 DUPLICATE_APPOINTMENT or CUSTOMER_OVERLAP the attempt is run again with another number and time
   * (the 409 is then declared as an expected console error); any other answer fails the test.
   *
   * @param attempt - books `candidate` through the UI and returns the POST /api/v1/appointments response
   * @param options - a fixed number, when the test needs one
   * @returns the candidate that was booked and its reference
   */
  book(
    attempt: (candidate: Candidate) => Promise<Response>,
    options?: BookOptions,
  ): Promise<Booked>;
}

/**
 * Declares console errors the test expects, so the browser guard lets them pass. Each pattern is matched against
 * the message's text followed by the URL it came from, for example "Failed to load resource: the server responded
 * with a status of 404 () http://localhost:8080/api/v1/appointments/APT-...".
 */
export type ExpectConsoleError = (pattern: RegExp) => void;

interface BrowserProblems {
  csp: string[];
  consoleErrors: string[];
  pageErrors: string[];
  expected: RegExp[];
}

// The demo numbers the tests may mint: 082 555 0100 to 0199. 0000 always fails delivery, and 0101 is the number
// the README's examples use, so a person trying the app by hand is less likely to meet a test's booking.
const MINTED_FROM = 100;
const MINTED_COUNT = 100;
// Enough attempts to cover every service once for a fixed number, plus a few overlaps.
const MAX_ATTEMPTS = 12;
const RETRYABLE = new Set(['DUPLICATE_APPOINTMENT', 'CUSTOMER_OVERLAP']);

/** The path every booking is POSTed to. */
export const BOOKING_PATH = '/api/v1/appointments';

export const test = base.extend<{
  browserProblems: BrowserProblems;
  expectConsoleError: ExpectConsoleError;
  booking: BookingFixture;
}>({
  browserProblems: async ({}, use) => {
    await use({ csp: [], consoleErrors: [], pageErrors: [], expected: [] });
  },

  page: async ({ page, browserProblems: problems }, use) => {
    // A binding survives navigation, unlike an array on window, so no violation is lost when the page changes.
    await page.exposeFunction('__e2eCspViolation', (line: string) => {
      problems.csp.push(line);
    });
    await page.addInitScript(() => {
      document.addEventListener(
        'securitypolicyviolation',
        (event) => {
          const report = (window as unknown as { __e2eCspViolation: (line: string) => void })
            .__e2eCspViolation;
          report(
            `${event.effectiveDirective} blocked ${event.blockedURI || 'inline content'} on ${location.pathname}`,
          );
        },
        { capture: true },
      );
    });
    page.on('console', (message) => {
      if (message.type() === 'error') {
        problems.consoleErrors.push(`${message.text()} ${message.location().url}`);
      }
    });
    page.on('pageerror', (error) => {
      problems.pageErrors.push(error.message);
    });

    await use(page);

    const unexpected = problems.consoleErrors.filter(
      (line) => !problems.expected.some((pattern) => pattern.test(line)),
    );
    expect(problems.csp, 'Content Security Policy violations').toEqual([]);
    expect(problems.pageErrors, 'uncaught errors in the page').toEqual([]);
    expect(unexpected, 'console errors the test did not declare').toEqual([]);
  },

  expectConsoleError: async ({ browserProblems: problems }, use) => {
    await use((pattern) => {
      problems.expected.push(pattern);
    });
  },

  booking: async ({ request, expectConsoleError }, use) => {
    await use({
      async book(attempt, options = {}) {
        const heldServices = new Set<string>();
        const refusals: string[] = [];
        for (let tries = 0; tries < MAX_ATTEMPTS; tries++) {
          const candidate = await mintCandidate(request, {
            mobile: options.mobile ?? mintMobile(),
            skipServices: heldServices,
          });
          const response = await attempt(candidate);
          if (response.status() === 201) {
            const body = (await response.json()) as { reference: string };
            return { candidate, reference: body.reference };
          }
          const code = await problemCode(response);
          if (response.status() !== 409 || !RETRYABLE.has(code)) {
            throw new Error(`The booking was refused: ${String(response.status())} ${code}`);
          }
          // The browser logs every 4xx it fetches; this one is the rule working, not a defect.
          expectConsoleError(/status of 409 .*\/api\/v1\/appointments$/);
          refusals.push(`${candidate.mobile} ${candidate.service.code}: ${code}`);
          if (code === 'DUPLICATE_APPOINTMENT') {
            heldServices.add(candidate.service.code);
          }
        }
        throw new Error(
          `No candidate could be booked in ${String(MAX_ATTEMPTS)} attempts (${refusals.join('; ')}). ` +
            'A fixed number holds one upcoming appointment per service; `make down` resets the database.',
        );
      },
    });
  },
});

export { expect };

/** A random demo number from 082 555 0100 to 082 555 0199. */
export function mintMobile(): string {
  const last4 = String(MINTED_FROM + Math.floor(Math.random() * MINTED_COUNT)).padStart(4, '0');
  return `082 555 ${last4}`;
}

/** The last four digits of a number, as `/manage` asks for them. */
export function last4(mobile: string): string {
  return mobile.replace(/\D/g, '').slice(-4);
}

interface BranchSummary {
  code: string;
}
interface BranchDetail {
  code: string;
  name: string;
  bookableFrom: string;
  bookableTo: string;
  operatingHours: { weekday: number }[];
  services: { code: string; name: string }[];
  closedDates: { date: string }[];
}
interface Availability {
  slots: { startsAt: string; seatsAvailable: number }[];
}

// A random branch, service, open date and free time, read from the API so they exist and are free right now.
async function mintCandidate(
  request: APIRequestContext,
  { mobile, skipServices }: { mobile: string; skipServices: ReadonlySet<string> },
): Promise<Candidate> {
  const { branches } = await getJson<{ branches: BranchSummary[] }>(request, '/api/v1/branches');
  for (const { code } of shuffle(branches)) {
    const branch = await getJson<BranchDetail>(request, `/api/v1/branches/${code}`);
    const services = branch.services.filter((service) => !skipServices.has(service.code));
    const service = pick(services);
    if (service === undefined) {
      continue;
    }
    const closed = new Set(branch.closedDates.map((closedDate) => closedDate.date));
    const trading = new Set(branch.operatingHours.map((hours) => hours.weekday));
    const dates = datesBetween(branch.bookableFrom, branch.bookableTo).filter(
      (date) => !closed.has(date) && trading.has(isoWeekday(date)),
    );
    for (const date of shuffle(dates).slice(0, 5)) {
      const day = await getJson<Availability>(
        request,
        `/api/v1/branches/${code}/availability?service=${service.code}&date=${date}`,
      );
      const slot = pick(day.slots.filter((free) => free.seatsAvailable > 0));
      if (slot !== undefined) {
        return {
          mobile,
          firstName: 'Nomvula',
          lastName: 'Dlamini',
          branch: { code: branch.code, name: branch.name },
          service: { code: service.code, name: service.name },
          date,
          time: sastTime(slot.startsAt),
        };
      }
    }
  }
  throw new Error('The API offers no free time at any branch for the services left to try');
}

async function getJson<T>(request: APIRequestContext, path: string): Promise<T> {
  const response = await request.get(path);
  expect(response.status(), `GET ${path}`).toBe(200);
  return (await response.json()) as T;
}

async function problemCode(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { code?: unknown };
    return typeof body.code === 'string' ? body.code : 'no code';
  } catch {
    return 'no problem body';
  }
}

function pick<T>(items: readonly T[]): T | undefined {
  return items[Math.floor(Math.random() * items.length)];
}

function shuffle<T>(items: readonly T[]): T[] {
  const copy = [...items];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j] as T, copy[i] as T];
  }
  return copy;
}

function datesBetween(from: string, to: string): string[] {
  const dates: string[] = [];
  for (let day = new Date(`${from}T00:00:00Z`); day <= new Date(`${to}T00:00:00Z`);) {
    dates.push(day.toISOString().slice(0, 10));
    day = new Date(day.getTime() + 86_400_000);
  }
  return dates;
}

// 1 is Monday and 7 is Sunday, as the API's operating hours count them.
function isoWeekday(date: string): number {
  const day = new Date(`${date}T00:00:00Z`).getUTCDay();
  return day === 0 ? 7 : day;
}

function sastTime(instant: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone: 'Africa/Johannesburg',
  }).format(new Date(instant));
}

const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const MONTHS = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
];

/** A date as the date strip names it, "Tuesday 27 October", without the ", today" a date may add. */
export function dateName(date: string): string {
  const day = new Date(`${date}T00:00:00Z`);
  return `${WEEKDAYS[day.getUTCDay()] ?? ''} ${String(day.getUTCDate())} ${MONTHS[day.getUTCMonth()] ?? ''}`;
}

/** The date strip's button for `date`. */
export function dateButton(page: Page, date: string) {
  return page.getByRole('button', { name: new RegExp(`^${dateName(date)}(,|$)`) });
}

/**
 * The tile that holds the radio for `time`. The radio itself is visually hidden inside its label, so a pointer
 * clicks the label, as a customer's finger does.
 */
export function timeTile(page: Page, time: string) {
  return page.locator('label').filter({ has: page.locator(`input[type=radio][value="${time}"]`) });
}

/** Pages the date strip forward until `date` shows, then chooses it. */
export async function chooseDate(page: Page, date: string): Promise<void> {
  const button = dateButton(page, date);
  await page.getByRole('button', { name: 'Next 7 days' }).waitFor();
  for (let pages = 0; pages < 5 && !(await button.isVisible()); pages++) {
    await page.getByRole('button', { name: 'Next 7 days' }).click();
  }
  await button.click();
  await expect(button).toHaveAttribute('aria-pressed', 'true');
}

/**
 * Asserts the page's address holds none of the customer's personal data: not the name, and not the number in any
 * form a form or a router might write it. An address ends up in browser history and access logs, and BR-19 keeps
 * the number out of logs.
 */
export function expectNoPersonalData(url: string, candidate: Candidate): void {
  const decoded = decodeURIComponent(url).replace(/\+/g, ' ').toLowerCase();
  const digits = candidate.mobile.replace(/\D/g, '');
  for (const value of [
    candidate.firstName.toLowerCase(),
    candidate.lastName.toLowerCase(),
    candidate.mobile,
    digits,
    `27${digits.slice(1)}`,
  ]) {
    expect(decoded, `the address ${url}`).not.toContain(value);
  }
}

/** Waits for the response to the booking's POST that `action` sends. */
export async function bookingResponse(page: Page, action: () => Promise<void>): Promise<Response> {
  const [response] = await Promise.all([
    page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' &&
        new URL(candidate.url()).pathname === BOOKING_PATH,
    ),
    action(),
  ]);
  return response;
}
