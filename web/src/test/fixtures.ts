/**
 * The seeded reference data as the API serves it, for tests: the six branches, their hours and services from
 * `V3__reference_data.sql`, the public holidays from `V4__public_holidays_2026_2027.sql`, and the slot rules the API
 * enforces (BR-1 to BR-5): slots start at opening and step by the service's duration, end by closing, and start at
 * least two hours after {@link NOW}. The booking window is 30 days from today, so with {@link NOW} on Monday
 * 26 October 2026 it runs to Tuesday 24 November 2026, and Wednesday 4 November is the Local Government Elections.
 *
 * Every person and number is synthetic. The demo booking is the one the mockups show: a FICA update at Sandton
 * Summit Mall on Tuesday 27 October 2026 at 09:30 SAST, booked by Nomvula Dlamini with 082 555 0101.
 *
 * @packageDocumentation
 */
import type { InboxMessage } from '../api/inbox';
import type {
  Appointment,
  Availability,
  BranchDetail,
  BranchService,
  BranchSummary,
  Notification,
  Slot,
} from '../api/queries';
import type { Problem } from '../api/client';
import type { components } from '../api/schema';
import { addDays, datesBetween, weekdayNumber } from '../lib/time';

type OperatingHours = components['schemas']['OperatingHours'];
type ClosedDate = components['schemas']['ClosedDate'];

/** The fixed "now" of every test: Monday 26 October 2026, 08:00 SAST. */
export const NOW = '2026-10-26T08:00:00+02:00';

/** Today in SAST at {@link NOW}: the first bookable date. */
export const TODAY = '2026-10-26';

/** The last bookable date at {@link NOW}: 30 days counting today. */
export const LAST_BOOKABLE_DATE = addDays(TODAY, 29);

/** The demo booking's date, Tuesday 27 October 2026. */
export const DEMO_DATE = '2026-10-27';

/** The demo cellphone number. */
export const DEMO_MOBILE = '082 555 0101';

/** The Local Government Elections, a public holiday inside the window. */
export const ELECTION_DAY = '2026-11-04';

/** The seeded services, in display order. */
export const services = {
  FICA: {
    code: 'FICA',
    name: 'Update personal details (FICA)',
    description:
      'Update your address, contact details or identity document, as the Financial Intelligence Centre Act requires.',
    durationMinutes: 15,
    documentsNeeded: [
      'Valid SA ID or passport with permit',
      'Supporting document for the change, if any',
    ],
  },
  CARD: {
    code: 'CARD',
    name: 'Card replacement or collection',
    description: 'Replace a lost, stolen or damaged card, or collect a card you ordered.',
    durationMinutes: 15,
    documentsNeeded: ['Valid SA ID or passport', 'Your old card if you still have it'],
  },
  OPEN: {
    code: 'OPEN',
    name: 'Open a personal account',
    description: 'Open a transactional or savings account with a consultant.',
    durationMinutes: 30,
    documentsNeeded: [
      'SA Smart ID or green ID book, or passport with a valid visa or permit',
      'Cellphone registered in your name',
    ],
  },
  CREDIT: {
    code: 'CREDIT',
    name: 'Credit or personal loan consultation',
    description: 'Talk through a personal loan or credit facility, and what you can afford.',
    durationMinutes: 30,
    documentsNeeded: [
      'Valid SA ID',
      'Latest payslip',
      'Latest 3 months of bank statements if your salary is not paid into Fynbos Bank',
    ],
  },
  ESTATE: {
    code: 'ESTATE',
    name: 'Deceased estate assistance',
    description: 'Get help with the accounts of someone who has passed away.',
    durationMinutes: 30,
    documentsNeeded: [
      'Certified copy of the death certificate',
      "Certified copy of the deceased's ID",
      'Your own ID',
      'Letter of executorship, if issued',
    ],
  },
  HOME: {
    code: 'HOME',
    name: 'Home loan consultation',
    description: 'Talk through a home loan application with a consultant.',
    durationMinutes: 45,
    documentsNeeded: [
      'Valid SA ID',
      'Latest 3 months of payslips',
      'Latest 3 months of bank statements',
      'Offer to purchase, if you have one',
    ],
  },
} satisfies Record<string, BranchService>;

/** A seeded service code. */
export type ServiceCode = keyof typeof services;

/** The short names the confirmation SMS uses. */
const SERVICE_SMS_NAME: Record<ServiceCode, string> = {
  FICA: 'FICA update',
  CARD: 'Card replacement',
  OPEN: 'Account opening',
  CREDIT: 'Credit consult',
  ESTATE: 'Deceased estate',
  HOME: 'Home loan consult',
};

interface SeedBranch {
  code: string;
  name: string;
  smsName: string;
  address: BranchDetail['address'];
}

/** The six branches, by name as the API orders them. */
const SEED_BRANCHES: SeedBranch[] = [
  {
    code: 'BEL',
    name: 'Bellville Kingsway',
    smsName: 'Bellville Kingsway',
    address: {
      line1: '12 Fynbos Street',
      suburb: 'Bellville',
      city: 'Cape Town',
      province: 'Western Cape',
      postalCode: '7530',
    },
  },
  {
    code: 'GQE',
    name: 'Gqeberha Baywater',
    smsName: 'Gqeberha Baywater',
    address: {
      line1: '8 Aloe Crescent',
      suburb: 'Mill Park',
      city: 'Gqeberha',
      province: 'Eastern Cape',
      postalCode: '6001',
    },
  },
  {
    code: 'POL',
    name: 'Polokwane Savannah',
    smsName: 'Polokwane Savannah',
    address: {
      line1: '5 Marula Road',
      suburb: 'Polokwane Central',
      city: 'Polokwane',
      province: 'Limpopo',
      postalCode: '0699',
    },
  },
  {
    code: 'RUS',
    name: 'Rustenburg Bojanala',
    smsName: 'Rustenburg Bojanala',
    address: {
      line1: '9 Baobab Avenue',
      suburb: 'Rustenburg Central',
      city: 'Rustenburg',
      province: 'North West',
      postalCode: '0299',
    },
  },
  {
    code: 'SAN',
    name: 'Sandton Summit Mall',
    smsName: 'Sandton Summit',
    address: {
      line1: 'Shop 4, 1 Protea Lane',
      suburb: 'Sandton',
      city: 'Johannesburg',
      province: 'Gauteng',
      postalCode: '2196',
    },
  },
  {
    code: 'UMH',
    name: 'Umhlanga Ridge',
    smsName: 'Umhlanga Ridge',
    address: {
      line1: '3 Strelitzia Walk',
      suburb: 'Umhlanga',
      city: 'Durban',
      province: 'KwaZulu-Natal',
      postalCode: '4319',
    },
  },
];

/** The branches open on Sundays: the two mall branches. */
const OPENS_SUNDAY = new Set(['SAN', 'UMH']);

/** The seeded public holidays that fall in or near the window at {@link NOW}. */
export const publicHolidays: Readonly<Record<string, string>> = {
  '2026-11-04': 'Local Government Elections',
  '2026-12-16': 'Day of Reconciliation',
  '2026-12-25': 'Christmas Day',
  '2026-12-26': 'Day of Goodwill',
};

/** Every seeded branch code. */
export const branchCodes = SEED_BRANCHES.map((b) => b.code);

/** The branch list, `GET /api/v1/branches`, ordered by name. */
export const branches: BranchSummary[] = SEED_BRANCHES.map((b) => ({
  code: b.code,
  name: b.name,
  suburb: b.address.suburb,
  city: b.address.city,
  province: b.address.province,
  opensSunday: OPENS_SUNDAY.has(b.code),
}));

function seedBranch(code: string): SeedBranch {
  const branch = SEED_BRANCHES.find((b) => b.code === code);
  if (branch === undefined) {
    throw new Error(`No seeded branch ${code}`);
  }
  return branch;
}

/**
 * A branch's weekly hours, Monday (1) first; a missing weekday is a closed day. Weekdays 08:00 to 17:00; Saturday
 * 08:00 to 13:00, except Rustenburg, which closes at 12:00; Sunday 09:00 to 13:00 at the two mall branches only.
 *
 * @param code - the branch code
 */
export function operatingHours(code: string): OperatingHours[] {
  const hours: OperatingHours[] = [1, 2, 3, 4, 5].map((weekday) => ({
    weekday,
    opensAt: '08:00',
    closesAt: '17:00',
  }));
  hours.push({ weekday: 6, opensAt: '08:00', closesAt: code === 'RUS' ? '12:00' : '13:00' });
  if (OPENS_SUNDAY.has(code)) {
    hours.push({ weekday: 7, opensAt: '09:00', closesAt: '13:00' });
  }
  return hours;
}

/**
 * The services a branch offers, in display order: all six, except that Gqeberha offers no home loans.
 *
 * @param code - the branch code
 */
export function servicesAt(code: string): BranchService[] {
  return Object.values(services).filter((s) => !(code === 'GQE' && s.code === 'HOME'));
}

/**
 * The seats each slot of a service has at a branch: one at Polokwane, three for FICA and credit at Sandton, and two
 * everywhere else.
 *
 * @param code - the branch code
 * @param service - the service code
 */
export function capacity(code: string, service: string): number {
  if (code === 'POL') {
    return 1;
  }
  if (code === 'SAN' && (service === 'FICA' || service === 'CREDIT')) {
    return 3;
  }
  return 2;
}

/**
 * Why a branch is closed on a date, or `undefined` if it trades.
 *
 * @param code - the branch code
 * @param date - `YYYY-MM-DD`
 */
export function closedReason(code: string, date: string): ClosedDate | undefined {
  const holiday = publicHolidays[date];
  if (holiday !== undefined) {
    return { date, reason: 'PUBLIC_HOLIDAY', name: holiday };
  }
  const trades = operatingHours(code).some((h) => h.weekday === weekdayNumber(date));
  return trades ? undefined : { date, reason: 'CLOSED_DAY', name: null };
}

/**
 * A branch in full, `GET /api/v1/branches/{code}`, as the API answers at {@link NOW}.
 *
 * @param code - a seeded branch code
 * @param overrides - fields to replace
 */
export function branchDetail(code: string, overrides: Partial<BranchDetail> = {}): BranchDetail {
  const branch = seedBranch(code);
  return {
    code: branch.code,
    name: branch.name,
    smsName: branch.smsName,
    address: branch.address,
    timezone: 'Africa/Johannesburg',
    bookableFrom: TODAY,
    bookableTo: LAST_BOOKABLE_DATE,
    operatingHours: operatingHours(code),
    services: servicesAt(code),
    closedDates: datesBetween(TODAY, LAST_BOOKABLE_DATE).flatMap((date) => {
      const closed = closedReason(code, date);
      return closed === undefined ? [] : [closed];
    }),
    ...overrides,
  };
}

/**
 * The seats left on the demo day, Tuesday 27 October 2026, for FICA at Sandton, as the date-and-time mockup draws
 * them: five full times and six with one seat left. Every other slot has all three seats.
 */
const DEMO_DAY_SEATS: Readonly<Record<string, number>> = {
  '08:30': 0,
  '08:45': 1,
  '09:00': 0,
  '09:15': 1,
  '09:30': 2,
  '09:45': 1,
  '10:30': 1,
  '12:00': 0,
  '12:15': 0,
  '12:30': 1,
  '13:00': 0,
  '16:30': 1,
};

/** Options for {@link availability}. */
export interface AvailabilityOverrides extends Partial<Availability> {
  /** Seats left by start time (`HH:MM`), replacing the defaults; 0 makes a slot full. */
  seats?: Record<string, number>;
}

function minutes(time: string): number {
  const [h = '0', m = '0'] = time.split(':');
  return Number(h) * 60 + Number(m);
}

function clock(totalMinutes: number): string {
  return `${String(Math.floor(totalMinutes / 60)).padStart(2, '0')}:${String(totalMinutes % 60).padStart(2, '0')}`;
}

/**
 * One day's slots, `GET /api/v1/branches/{code}/availability`, as the API answers at {@link NOW}: every slot from
 * opening to closing on the service's grid, except those within two hours of {@link NOW}, with full ones listed at
 * 0 seats. A closed day has no slots and says why. FICA at Sandton on {@link DEMO_DATE} has the mockup's full and
 * nearly full times.
 *
 * @param code - a seeded branch code
 * @param service - a service code the branch offers
 * @param date - `YYYY-MM-DD`, inside the window
 * @param overrides - seats by time, or fields to replace
 */
export function availability(
  code: string,
  service: string,
  date: string,
  overrides: AvailabilityOverrides = {},
): Availability {
  const { seats: seatOverrides, ...fields } = overrides;
  const base = {
    branchCode: code,
    serviceCode: service,
    date,
    timezone: 'Africa/Johannesburg',
    generatedAt: NOW,
    closedReason: null,
    holidayName: null,
    slots: [],
  } satisfies Availability;
  const closed = closedReason(code, date);
  if (closed !== undefined) {
    return { ...base, closedReason: closed.reason, holidayName: closed.name, ...fields };
  }
  const hours = operatingHours(code).find((h) => h.weekday === weekdayNumber(date));
  const duration = services[service as ServiceCode].durationMinutes;
  const demo = code === 'SAN' && service === 'FICA' && date === DEMO_DATE ? DEMO_DAY_SEATS : {};
  const seats = { ...demo, ...seatOverrides };
  // Slots start at least two hours from now (BR-4); only today is affected.
  const earliest = date === TODAY ? minutes(NOW.slice(11, 16)) + 120 : 0;
  const slots: Slot[] = [];
  if (hours !== undefined) {
    for (
      let start = minutes(hours.opensAt);
      start + duration <= minutes(hours.closesAt);
      start += duration
    ) {
      if (start >= earliest) {
        slots.push({
          startsAt: `${date}T${clock(start)}:00+02:00`,
          endsAt: `${date}T${clock(start + duration)}:00+02:00`,
          seatsAvailable: seats[clock(start)] ?? capacity(code, service),
        });
      }
    }
  }
  return { ...base, slots, ...fields };
}

/**
 * The confirmation SMS's text, as the API's template renders it.
 *
 * @param service - the service code
 * @param branch - the branch code
 * @param when - the start, as "Tue 27 Oct 09:30"
 * @param reference - the appointment's reference
 */
export function confirmationSms(
  service: string,
  branch: string,
  when: string,
  reference: string,
): string {
  const serviceName = SERVICE_SMS_NAME[service as ServiceCode];
  const branchName = seedBranch(branch).smsName;
  return `Appt confirmed: ${serviceName}, ${branchName}, ${when} SAST. Ref ${reference}. Bring ID + docs. Manage: http://localhost:8080/manage`;
}

/** The demo appointment's reference. */
export const DEMO_REFERENCE = 'APT-7K3M-9Q2X';

/**
 * A message about an appointment; by default the demo booking's confirmation SMS, still PENDING.
 *
 * @param overrides - fields to replace, such as `{ status: 'SENT', attempts: 1, sentAt: ... }`
 */
export function notification(overrides: Partial<Notification> = {}): Notification {
  return {
    id: '30000000-0000-4000-8000-000000000001',
    kind: 'CONFIRMATION',
    channel: 'SMS',
    recipientMasked: '082 *** 0101',
    body: confirmationSms('FICA', 'SAN', 'Tue 27 Oct 09:30', DEMO_REFERENCE),
    status: 'PENDING',
    attempts: 0,
    failureReason: null,
    sentAt: null,
    ...overrides,
  };
}

/** The demo SMS once sent: one attempt, at 10:42 SAST on the day it was booked. */
export const SENT_NOTIFICATION = notification({
  status: 'SENT',
  attempts: 1,
  sentAt: '2026-10-26T10:42:00+02:00',
});

/** A confirmation SMS the gateway gave up on, to a number ending 0000: five attempts. */
export const FAILED_NOTIFICATION = notification({
  status: 'FAILED',
  attempts: 5,
  failureReason: 'DELIVERY_FAILED',
  recipientMasked: '082 *** 0000',
});

/**
 * An appointment, `GET /api/v1/appointments/{reference}`; by default the demo booking, confirmed, with its SMS
 * still PENDING.
 *
 * @param overrides - fields to replace
 */
export function appointment(overrides: Partial<Appointment> = {}): Appointment {
  const branch = seedBranch('SAN');
  const { code, name, durationMinutes, documentsNeeded } = services.FICA;
  return {
    reference: DEMO_REFERENCE,
    status: 'CONFIRMED',
    canCancel: true,
    cancelledAt: null,
    startsAt: '2026-10-27T09:30:00+02:00',
    endsAt: '2026-10-27T09:45:00+02:00',
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
    service: { code, name, durationMinutes, documentsNeeded },
    customer: { firstName: 'Nomvula', lastName: 'Dlamini', mobileMasked: '082 *** 0101' },
    notifications: [notification()],
    ...overrides,
  };
}

/**
 * A message in the simulated inbox.
 *
 * @param overrides - fields to replace
 */
export function inboxMessage(overrides: Partial<InboxMessage> = {}): InboxMessage {
  return {
    id: '40000000-0000-4000-8000-000000000002',
    reference: DEMO_REFERENCE,
    kind: 'CONFIRMATION',
    channel: 'SMS',
    recipientMasked: '082 *** ****',
    subject: null,
    body: confirmationSms('FICA', 'SAN', 'Tue 27 Oct 09:30', DEMO_REFERENCE),
    status: 'SENT',
    failureReason: null,
    retryable: false,
    attempts: 1,
    nextAttemptAt: null,
    lastError: null,
    createdAt: '2026-10-26T10:42:00+02:00',
    sentAt: '2026-10-26T10:42:00+02:00',
    ...overrides,
  };
}

/**
 * The simulated inbox as the inbox mockup draws it, newest first: a home loan at Umhlanga Ridge still PENDING, the
 * demo booking SENT, and a FICA update at 10:00 to a number ending 0000 that FAILED after five attempts.
 */
export const inboxMessages: InboxMessage[] = [
  inboxMessage({
    id: '40000000-0000-4000-8000-000000000003',
    reference: 'APT-QW7P-3KDM',
    body: confirmationSms('HOME', 'UMH', 'Thu 29 Oct 11:00', 'APT-QW7P-3KDM'),
    status: 'PENDING',
    attempts: 0,
    nextAttemptAt: '2026-10-26T10:44:02+02:00',
    createdAt: '2026-10-26T10:44:00+02:00',
    sentAt: null,
  }),
  inboxMessage(),
  inboxMessage({
    id: '40000000-0000-4000-8000-000000000001',
    reference: 'APT-H4WQ-8RZD',
    body: confirmationSms('FICA', 'SAN', 'Tue 27 Oct 10:00', 'APT-H4WQ-8RZD'),
    status: 'FAILED',
    failureReason: 'DELIVERY_FAILED',
    retryable: true,
    attempts: 5,
    lastError: 'SIMULATED_OUTAGE',
    createdAt: '2026-10-26T10:40:00+02:00',
    sentAt: null,
  }),
];

/** The HTTP status of each code, from docs/errors.md. */
const STATUS: Record<NonNullable<Problem['code']>, number> = {
  VALIDATION_FAILED: 400,
  IDEMPOTENCY_KEY_REQUIRED: 400,
  BRANCH_NOT_FOUND: 404,
  APPOINTMENT_NOT_FOUND: 404,
  MESSAGE_NOT_FOUND: 404,
  SERVICE_NOT_OFFERED_AT_BRANCH: 422,
  DATE_OUTSIDE_BOOKING_WINDOW: 422,
  SLOT_ON_CLOSED_DAY: 422,
  SLOT_OUTSIDE_HOURS: 422,
  SLOT_OFF_GRID: 422,
  SLOT_WITHIN_LEAD_TIME: 422,
  MOBILE_NUMBER_REQUIRED: 422,
  IDEMPOTENCY_KEY_REUSED: 422,
  DUPLICATE_APPOINTMENT: 409,
  CUSTOMER_OVERLAP: 409,
  SLOT_FULL: 409,
  TOO_LATE_TO_CANCEL: 409,
  LOOKUP_RATE_LIMITED: 429,
  INTERNAL_ERROR: 500,
  TRY_AGAIN: 503,
};

/**
 * A problem body for a code, with the status docs/errors.md gives it.
 *
 * @example `problem('SLOT_FULL', { alternatives: [...] })`
 * @param code - the problem's code
 * @param overrides - fields to add or replace, such as `errors` or `alternatives`
 */
export function problem(
  code: NonNullable<Problem['code']>,
  overrides: Partial<Problem> = {},
): Problem {
  const correlationId = overrides.correlationId ?? `test-${code.toLowerCase()}`;
  return {
    type: `urn:branchbook:problem:${code}`,
    title: code,
    status: STATUS[code],
    instance: `urn:branchbook:request:${correlationId}`,
    code,
    correlationId,
    ...overrides,
  };
}
