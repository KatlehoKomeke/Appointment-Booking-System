// load/booking.js: a k6 load test of BranchBook's two busiest paths, availability reads and bookings.
//
// Two scenarios run side by side at a fixed arrival rate. "browse" reads one day's slots for a random branch,
// service and bookable date. "book" reads a day's slots the same way, picks a free one and books it for a random
// published demo number (082 555 0001 to 0199) with a fresh Idempotency-Key, as the web app would. Every customer
// detail is synthetic. The run fails if a threshold below is crossed: the 95th-percentile latency of either path,
// the share of unexpected answers, or any 5xx at all.
//
// Usage (load/README.md has more):
//   docker run --rm --network bb-res_default -v "$PWD/load:/load:ro" -e BASE_URL=http://app:8080 \
//     grafana/k6:1.8.1@sha256:23f2279054c3e01535455c4d92914a625df12aeb631ea0e3ea7a43f96bcbc843 run /load/booking.js
//
// Settings, all from the environment:
//   BASE_URL         where the app is, such as http://localhost:8080 (required)
//   SYNTHETIC_TOKEN  sent as x-branchbook-synthetic on every request, so staging's CAPTCHA rule lets the bookings
//                    through; never set against production, which has no bypass
//   READ_RATE        availability reads started per second by "browse" (default 20)
//   BOOK_RATE        bookings started per second by "book" (default 5; 0 runs reads only)
//   DURATION         how long both scenarios run (default 1m)
//   P95_READ_MS      the availability threshold, in milliseconds (default 500)
//   P95_BOOK_MS      the booking threshold, in milliseconds (default 1000)
//
// It books real appointments in the target's database, so run it against a local stack or staging, never production.

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/+$/, '');
const SYNTHETIC_TOKEN = __ENV.SYNTHETIC_TOKEN || '';
const READ_RATE = Number(__ENV.READ_RATE || 20);
const BOOK_RATE = Number(__ENV.BOOK_RATE || 5);
const DURATION = __ENV.DURATION || '1m';
const P95_READ_MS = Number(__ENV.P95_READ_MS || 500);
const P95_BOOK_MS = Number(__ENV.P95_BOOK_MS || 1000);

// Answers the API gives by design: 409 for a full slot, a duplicate or an overlap, and 422 for a slot that has
// become too soon or a refused rule. They are outcomes, not failures, so http_req_failed leaves them out.
http.setResponseCallback(http.expectedStatuses(200, 201, 409, 422));

/** Any 5xx: the run fails on the first. */
const serverErrors = new Counter('server_errors');
/** Bookings the API confirmed with 201. */
const bookingsConfirmed = new Counter('bookings_confirmed');
/** Bookings the API refused by design, with 409 or 422. */
const bookingsRefused = new Counter('bookings_refused');
/** The share of answers that were neither a success nor a refusal by design. */
const unexpected = new Rate('unexpected_responses');

const scenarios = {
  browse: {
    executor: 'constant-arrival-rate',
    exec: 'browse',
    rate: READ_RATE,
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: Math.max(5, READ_RATE),
    maxVUs: Math.max(20, READ_RATE * 4),
  },
};
if (BOOK_RATE > 0) {
  scenarios.book = {
    executor: 'constant-arrival-rate',
    exec: 'book',
    rate: BOOK_RATE,
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: Math.max(5, BOOK_RATE * 2),
    maxVUs: Math.max(20, BOOK_RATE * 8),
  };
}

export const options = {
  scenarios,
  thresholds: {
    'http_req_duration{kind:availability}': [`p(95)<${P95_READ_MS}`],
    ...(BOOK_RATE > 0 ? { 'http_req_duration{kind:booking}': [`p(95)<${P95_BOOK_MS}`] } : {}),
    http_req_failed: ['rate<0.01'],
    unexpected_responses: ['rate<0.01'],
    server_errors: ['count<1'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

/** Headers every request carries: JSON, and the synthetic-traffic token where one is set. */
function headers(extra) {
  const all = { Accept: 'application/json', ...extra };
  if (SYNTHETIC_TOKEN) {
    all['x-branchbook-synthetic'] = SYNTHETIC_TOKEN;
  }
  return all;
}

/** Records an answer's class in the run's own metrics. */
function record(response, expected) {
  if (response.status >= 500) {
    serverErrors.add(1);
  }
  unexpected.add(!expected.includes(response.status));
}

/** A random element of a non-empty array. */
function pick(items) {
  return items[Math.floor(Math.random() * items.length)];
}

/** A version-4 UUID for an Idempotency-Key, from Math.random: unique enough for a test, not for secrets. */
function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

/** Every date from one ISO date to another, inclusive. */
function datesBetween(from, to) {
  const dates = [];
  for (let day = new Date(`${from}T00:00:00Z`); day <= new Date(`${to}T00:00:00Z`); day.setUTCDate(day.getUTCDate() + 1)) {
    dates.push(day.toISOString().slice(0, 10));
  }
  return dates;
}

/** Reads every branch, its services and its bookable dates once, before the scenarios start. */
export function setup() {
  if (!BASE_URL) {
    fail('Set BASE_URL, for example http://localhost:8080');
  }
  const list = http.get(`${BASE_URL}/api/v1/branches`, { headers: headers(), tags: { kind: 'setup' } });
  if (list.status !== 200) {
    fail(`GET /api/v1/branches answered ${list.status}`);
  }
  const targets = [];
  for (const branch of list.json('branches')) {
    const detail = http.get(`${BASE_URL}/api/v1/branches/${branch.code}`, { headers: headers(), tags: { kind: 'setup' } });
    if (detail.status !== 200) {
      fail(`GET /api/v1/branches/${branch.code} answered ${detail.status}`);
    }
    const dates = datesBetween(detail.json('bookableFrom'), detail.json('bookableTo'));
    for (const service of detail.json('services')) {
      targets.push({ branch: branch.code, service: service.code, dates });
    }
  }
  return { targets };
}

/** Reads one day's slots for a random branch, service and date; returns the slots, or none on any failure. */
function availability(targets) {
  const target = pick(targets);
  const date = pick(target.dates);
  const response = http.get(
    `${BASE_URL}/api/v1/branches/${target.branch}/availability?service=${target.service}&date=${date}`,
    { headers: headers(), tags: { kind: 'availability', name: 'GET /api/v1/branches/{code}/availability' } },
  );
  record(response, [200]);
  const ok = check(response, { 'availability answers 200': (r) => r.status === 200 });
  return { target, slots: ok ? response.json('slots') || [] : [] };
}

/** The "browse" scenario: one availability read. */
export function browse(data) {
  availability(data.targets);
}

/** The "book" scenario: a read, then a booking of a free slot for a random demo number. */
export function book(data) {
  const { target, slots } = availability(data.targets);
  const free = slots.filter((slot) => slot.seatsAvailable > 0);
  if (free.length === 0) {
    return;
  }
  const suffix = String(1 + Math.floor(Math.random() * 199)).padStart(4, '0');
  const body = JSON.stringify({
    branchCode: target.branch,
    serviceCode: target.service,
    startsAt: pick(free).startsAt,
    customer: { firstName: 'Load', lastName: 'Tester', mobile: `082 555 ${suffix}` },
  });
  const response = http.post(`${BASE_URL}/api/v1/appointments`, body, {
    headers: headers({ 'Content-Type': 'application/json', 'Idempotency-Key': uuid() }),
    tags: { kind: 'booking', name: 'POST /api/v1/appointments' },
  });
  record(response, [201, 409, 422]);
  if (response.status === 201) {
    bookingsConfirmed.add(1);
  } else if (response.status === 409 || response.status === 422) {
    bookingsRefused.add(1);
  }
  check(response, {
    'a booking is confirmed or refused by design': (r) => [201, 409, 422].includes(r.status),
    'a booking never answers 5xx': (r) => r.status < 500,
  });
}
