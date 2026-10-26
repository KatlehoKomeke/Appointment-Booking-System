import { describe, expect, it } from 'vitest';

import { api, unwrap } from '../api/client';
import { branchDetail, branches, ELECTION_DAY, TODAY } from './fixtures';

// The fake API is what every screen's tests stand on, so these check it against the seed data and the API's rules.
describe('the fake API', () => {
  it('serves every seeded branch in full, with its own hours, services and closed days', async () => {
    for (const { code } of branches) {
      const detail = await unwrap(
        api.GET('/api/v1/branches/{code}', { params: { path: { code } } }),
      );
      expect(detail).toEqual(branchDetail(code));
      expect(detail.closedDates).toContainEqual({
        date: ELECTION_DAY,
        reason: 'PUBLIC_HOLIDAY',
        name: 'Local Government Elections',
      });
    }
    const gqe = branchDetail('GQE');
    expect(gqe.services.map((s) => s.code)).not.toContain('HOME');
    expect(branchDetail('RUS').operatingHours).toContainEqual({
      weekday: 6,
      opensAt: '08:00',
      closesAt: '12:00',
    });
    // Sunday 1 November: closed at Bellville, open at Sandton.
    expect(branchDetail('BEL').closedDates).toContainEqual({
      date: '2026-11-01',
      reason: 'CLOSED_DAY',
      name: null,
    });
    expect(branchDetail('SAN').closedDates.map((d) => d.date)).toEqual([ELECTION_DAY]);
    expect(branchDetail('SAN')).toMatchObject({ bookableFrom: TODAY, bookableTo: '2026-11-24' });
  });

  it('answers availability as the API does: lead time, closed days and refusals', async () => {
    const day = (code: string, service: string, date: string) =>
      api.GET('/api/v1/branches/{code}/availability', {
        params: { path: { code }, query: { service, date } },
      });

    const today = await unwrap(day('SAN', 'FICA', TODAY));
    expect(today.slots[0]?.startsAt).toBe('2026-10-26T10:00:00+02:00');

    const holiday = await unwrap(day('SAN', 'FICA', ELECTION_DAY));
    expect(holiday).toMatchObject({
      closedReason: 'PUBLIC_HOLIDAY',
      holidayName: 'Local Government Elections',
      slots: [],
    });

    const saturday = await unwrap(day('RUS', 'HOME', '2026-10-31'));
    expect(saturday.slots.map((s) => s.startsAt.slice(11, 16))).toEqual([
      '08:00',
      '08:45',
      '09:30',
      '10:15',
      '11:00',
    ]);

    await expect(unwrap(day('GQE', 'HOME', '2026-10-27'))).rejects.toMatchObject({
      code: 'SERVICE_NOT_OFFERED_AT_BRANCH',
    });
    await expect(unwrap(day('SAN', 'FICA', '2026-11-25'))).rejects.toMatchObject({
      code: 'DATE_OUTSIDE_BOOKING_WINDOW',
    });
    await expect(unwrap(day('XYZ', 'FICA', '2026-10-27'))).rejects.toMatchObject({
      code: 'BRANCH_NOT_FOUND',
    });
  });

  it('refuses a landline, then books and opens a real number', async () => {
    const post = (mobile: string, key: string) =>
      unwrap(
        api.POST('/api/v1/appointments', {
          params: { header: { 'Idempotency-Key': key } },
          body: {
            branchCode: 'SAN',
            serviceCode: 'FICA',
            startsAt: '2026-10-27T09:30:00+02:00',
            customer: { firstName: 'Nomvula', lastName: 'Dlamini', mobile },
          },
        }),
      );

    await expect(post('021 555 0101', crypto.randomUUID())).rejects.toMatchObject({
      status: 422,
      code: 'MOBILE_NUMBER_REQUIRED',
    });
    const booked = await post('082 555 0101', crypto.randomUUID());
    expect(booked).toMatchObject({
      status: 'CONFIRMED',
      endsAt: '2026-10-27T09:45:00+02:00',
      customer: { mobileMasked: '082 *** 0101' },
      notifications: [{ status: 'PENDING', recipientMasked: '082 *** 0101' }],
    });
    expect(booked.notifications[0]?.body).toContain(
      `Appt confirmed: FICA update, Sandton Summit, Tue 27 Oct 09:30 SAST. Ref ${booked.reference}.`,
    );

    const opened = await unwrap(
      api.GET('/api/v1/appointments/{reference}', {
        params: {
          path: { reference: booked.reference.toLowerCase() },
          header: { 'X-Mobile-Last4': '0101' },
        },
      }),
    );
    expect(opened.reference).toBe(booked.reference);
  });
});
