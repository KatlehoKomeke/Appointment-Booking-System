/**
 * A fake of the API for tests, with MSW: every operation in docs/openapi.yaml, serving the seeded data in
 * `fixtures.ts` and answering the way the API does, problems included.
 *
 * Bookings are kept per test (reset by `resetFakeApi`, which `setup.ts` calls after each test): a booking can then
 * be opened with its reference and digits, and a repeat with the same `Idempotency-Key` replays it, as on the
 * server. A test that needs another answer overrides one handler:
 *
 * ```ts
 * server.use(http.post('/api/v1/appointments', () => problemResponse('SLOT_FULL')));
 * ```
 *
 * @packageDocumentation
 */
import { http, HttpResponse } from 'msw';

import type { Problem } from '../api/client';
import type { Appointment, BookingRequest } from '../api/queries';
import { last4, parseSaMobile } from '../lib/mobile';
import { formatDateShort, formatTime, sastDate } from '../lib/time';
import {
  appointment,
  availability,
  branchCodes,
  branchDetail,
  branches,
  confirmationSms,
  inboxMessages,
  LAST_BOOKABLE_DATE,
  notification,
  problem,
  services,
  servicesAt,
  TODAY,
} from './fixtures';

/**
 * Matches the API on any origin, so these handlers work whatever base URL the client resolved. An override may use
 * a plain path such as `/api/v1/branches`, which MSW resolves against the test page's origin, as the client does.
 */
const API = '*/api/v1';

/**
 * An error response with a problem body, as the API sends it.
 *
 * @param code - the problem's code; its status comes from docs/errors.md
 * @param overrides - fields to add or replace, such as `errors`
 * @param headers - response headers, such as `{ 'Retry-After': '2' }`
 */
export function problemResponse(
  code: NonNullable<Problem['code']>,
  overrides: Partial<Problem> = {},
  headers: Record<string, string> = {},
) {
  const body = problem(code, overrides);
  return HttpResponse.json(body, {
    status: body.status,
    headers: {
      'Content-Type': 'application/problem+json',
      'X-Correlation-Id': body.correlationId,
      ...headers,
    },
  });
}

/** A 404 for a path the API does not serve: no code, as when the inbox is switched off. */
export function notFoundResponse() {
  return HttpResponse.json(
    {
      title: 'Not Found',
      status: 404,
      instance: 'urn:branchbook:request:test-not-found',
      correlationId: 'test-not-found',
    } satisfies Problem,
    { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

interface Booking {
  fingerprint: string;
  appointment: Appointment;
}

/** Bookings made in this test, by idempotency key. */
const bookings = new Map<string, Booking>();

/** Forgets the bookings made in a test. `setup.ts` calls it after each one. */
export function resetFakeApi(): void {
  bookings.clear();
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const NAME = /^\p{L}[\p{L} '-]{0,59}$/u;

function normalise(reference: string): string {
  return reference.replace(/-/g, '').toUpperCase();
}

// A reference for the n-th booking of a test, from the API's 31-character alphabet.
function referenceFor(n: number): string {
  const alphabet = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';
  const digits = Array.from({ length: 8 }, (_, i) => alphabet[(n * 7 + i * 13) % alphabet.length]);
  return `APT-${digits.slice(0, 4).join('')}-${digits.slice(4).join('')}`;
}

function isBookingRequest(body: unknown): body is BookingRequest {
  if (typeof body !== 'object' || body === null) {
    return false;
  }
  const b = body as Partial<BookingRequest>;
  return (
    typeof b.branchCode === 'string' &&
    typeof b.serviceCode === 'string' &&
    typeof b.startsAt === 'string' &&
    typeof b.customer?.firstName === 'string' &&
    typeof b.customer.lastName === 'string' &&
    typeof b.customer.mobile === 'string'
  );
}

// Checks a requested start against the seeded day, in the API's order (BR-21).
function slotProblem(request: BookingRequest): NonNullable<Problem['code']> | undefined {
  const date = sastDate(request.startsAt);
  if (date < TODAY || date > LAST_BOOKABLE_DATE) {
    return 'DATE_OUTSIDE_BOOKING_WINDOW';
  }
  const day = availability(request.branchCode, request.serviceCode, date);
  if (day.closedReason !== null) {
    return 'SLOT_ON_CLOSED_DAY';
  }
  const slot = day.slots.find((s) => Date.parse(s.startsAt) === Date.parse(request.startsAt));
  if (slot === undefined) {
    return 'SLOT_OFF_GRID';
  }
  return slot.seatsAvailable === 0 ? 'SLOT_FULL' : undefined;
}

function book(request: BookingRequest, mobile: string, key: string): Appointment {
  const reference = referenceFor(bookings.size + 1);
  const service = services[request.serviceCode as keyof typeof services];
  const branch = branchDetail(request.branchCode);
  const endsAt = new Date(Date.parse(request.startsAt) + service.durationMinutes * 60_000);
  const startsAt = `${sastDate(request.startsAt)}T${formatTime(request.startsAt)}:00+02:00`;
  const masked = `${mobile.slice(0, 3)} *** ${last4(mobile)}`;
  const booked = appointment({
    reference,
    startsAt,
    endsAt: `${sastDate(endsAt.toISOString())}T${formatTime(endsAt.toISOString())}:00+02:00`,
    branch: {
      code: branch.code,
      name: branch.name,
      address: [
        branch.address.line1,
        branch.address.suburb,
        branch.address.city,
        branch.address.postalCode,
      ].join(', '),
    },
    service: {
      code: service.code,
      name: service.name,
      durationMinutes: service.durationMinutes,
      documentsNeeded: service.documentsNeeded,
    },
    customer: {
      firstName: request.customer.firstName.trim(),
      lastName: request.customer.lastName.trim(),
      mobileMasked: masked,
    },
    notifications: [
      notification({
        id: `30000000-0000-4000-8000-${String(bookings.size + 2).padStart(12, '0')}`,
        recipientMasked: masked,
        body: confirmationSms(
          service.code,
          branch.code,
          `${formatDateShort(request.startsAt)} ${formatTime(request.startsAt)}`,
          reference,
        ),
      }),
    ],
  });
  bookings.set(key, { fingerprint: JSON.stringify(request), appointment: booked });
  return booked;
}

/** The handlers for every operation, serving the fixtures. */
export const handlers = [
  http.get(`${API}/branches`, () => HttpResponse.json({ branches })),

  http.get(`${API}/branches/:code`, ({ params }) => {
    const code = String(params.code);
    if (!/^[A-Za-z]{2,8}$/.test(code)) {
      return problemResponse('VALIDATION_FAILED', {
        errors: [{ field: 'code', code: 'PATTERN', message: 'must be 2 to 8 letters' }],
      });
    }
    return branchCodes.includes(code)
      ? HttpResponse.json(branchDetail(code))
      : problemResponse('BRANCH_NOT_FOUND');
  }),

  http.get(`${API}/branches/:code/availability`, ({ params, request }) => {
    const code = String(params.code);
    const url = new URL(request.url);
    const service = url.searchParams.get('service') ?? '';
    const date = url.searchParams.get('date') ?? '';
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || service === '') {
      return problemResponse('VALIDATION_FAILED', {
        errors: [
          date === ''
            ? { field: 'date', code: 'REQUIRED', message: 'is required' }
            : { field: 'date', code: 'TYPE_MISMATCH', message: 'must be a date' },
        ],
      });
    }
    if (!branchCodes.includes(code)) {
      return problemResponse('BRANCH_NOT_FOUND');
    }
    if (!servicesAt(code).some((s) => s.code === service)) {
      return problemResponse('SERVICE_NOT_OFFERED_AT_BRANCH');
    }
    if (date < TODAY || date > LAST_BOOKABLE_DATE) {
      return problemResponse('DATE_OUTSIDE_BOOKING_WINDOW');
    }
    return HttpResponse.json(availability(code, service, date));
  }),

  http.post(`${API}/appointments`, async ({ request }) => {
    const body: unknown = await request.json().catch(() => undefined);
    if (!isBookingRequest(body)) {
      return problemResponse('VALIDATION_FAILED', {
        errors: [{ field: '(body)', code: 'NOT_NULL', message: 'must be a booking' }],
      });
    }
    const nameErrors = (['firstName', 'lastName'] as const).flatMap((field) =>
      NAME.test(body.customer[field].trim())
        ? []
        : [{ field: `customer.${field}`, code: 'NAME_INVALID', message: 'must be a name' }],
    );
    if (nameErrors.length > 0) {
      return problemResponse('VALIDATION_FAILED', { errors: nameErrors });
    }
    const key = request.headers.get('Idempotency-Key') ?? '';
    if (!UUID.test(key)) {
      return problemResponse('IDEMPOTENCY_KEY_REQUIRED');
    }
    const mobile = parseSaMobile(body.customer.mobile);
    if (!mobile.ok) {
      return problemResponse('MOBILE_NUMBER_REQUIRED', {
        errors: [
          {
            field: 'customer.mobile',
            code: 'MOBILE_NUMBER_REQUIRED',
            message: 'must be a South African mobile number',
          },
        ],
      });
    }
    const earlier = bookings.get(key);
    if (earlier !== undefined) {
      return earlier.fingerprint === JSON.stringify(body)
        ? HttpResponse.json(earlier.appointment, {
            status: 201,
            headers: {
              Location: `/api/v1/appointments/${earlier.appointment.reference}`,
              'Idempotent-Replayed': 'true',
            },
          })
        : problemResponse('IDEMPOTENCY_KEY_REUSED');
    }
    if (!branchCodes.includes(body.branchCode)) {
      return problemResponse('BRANCH_NOT_FOUND');
    }
    if (!servicesAt(body.branchCode).some((s) => s.code === body.serviceCode)) {
      return problemResponse('SERVICE_NOT_OFFERED_AT_BRANCH');
    }
    const refused = slotProblem(body);
    if (refused !== undefined) {
      return problemResponse(
        refused,
        refused === 'SLOT_OFF_GRID' || refused === 'SLOT_ON_CLOSED_DAY'
          ? { errors: [{ field: 'startsAt', code: refused, message: 'is not a slot' }] }
          : {},
      );
    }
    const booked = book(body, mobile.display, key);
    return HttpResponse.json(booked, {
      status: 201,
      headers: { Location: `/api/v1/appointments/${booked.reference}` },
    });
  }),

  http.get(`${API}/appointments/:reference`, ({ params, request }) => {
    const digits = request.headers.get('X-Mobile-Last4') ?? '';
    if (!/^\d{4}$/.test(digits)) {
      return problemResponse('VALIDATION_FAILED', {
        errors: [{ field: 'X-Mobile-Last4', code: 'PATTERN', message: 'must be 4 digits' }],
      });
    }
    const reference = normalise(String(params.reference));
    const known = [appointment(), ...[...bookings.values()].map((b) => b.appointment)];
    const found = known.find(
      (a) => normalise(a.reference) === reference && a.customer.mobileMasked.endsWith(digits),
    );
    return found === undefined
      ? problemResponse('APPOINTMENT_NOT_FOUND')
      : HttpResponse.json(found);
  }),

  http.get(`${API}/messages`, ({ request }) => {
    const limitText = new URL(request.url).searchParams.get('limit');
    const limit = limitText === null ? 50 : Number(limitText);
    if (!Number.isInteger(limit) || limit < 1 || limit > 200) {
      return problemResponse('VALIDATION_FAILED', {
        errors: [{ field: 'limit', code: limit < 1 ? 'MIN' : 'MAX', message: 'must be 1 to 200' }],
      });
    }
    return HttpResponse.json({ messages: inboxMessages.slice(0, limit) });
  }),
];
