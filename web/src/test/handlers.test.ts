import { describe, expect, it } from 'vitest';

import { api, unwrap } from '../api/client';
import { branchDetail, branches, DEMO_REFERENCE, ELECTION_DAY, TODAY } from './fixtures';

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

  it('cancels with the right digits, then deletes the appointment, as the API does', async () => {
    const cancel = (reference: string, digits: string) =>
      unwrap(
        api.POST('/api/v1/appointments/{reference}/cancel', {
          params: { path: { reference }, header: { 'X-Mobile-Last4': digits } },
        }),
      );
    const open = (reference: string, digits: string) =>
      unwrap(
        api.GET('/api/v1/appointments/{reference}', {
          params: { path: { reference }, header: { 'X-Mobile-Last4': digits } },
        }),
      );

    await expect(cancel(DEMO_REFERENCE, '12')).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
    await expect(cancel(DEMO_REFERENCE, '9999')).rejects.toMatchObject({
      code: 'APPOINTMENT_NOT_FOUND',
    });
    const cancelled = await cancel('apt7k3m9q2x', '0101');
    expect(cancelled).toMatchObject({
      reference: DEMO_REFERENCE,
      status: 'CANCELLED',
      canCancel: false,
    });
    expect(cancelled.notifications.find((n) => n.kind === 'CANCELLATION')).toMatchObject({
      status: 'PENDING',
      recipientMasked: '082 *** 0101',
    });

    // That answer was the last word: with the right digits, it is not found, and cannot be cancelled again.
    await expect(open(DEMO_REFERENCE, '0101')).rejects.toMatchObject({
      status: 404,
      code: 'APPOINTMENT_NOT_FOUND',
    });
    await expect(cancel(DEMO_REFERENCE, '0101')).rejects.toMatchObject({
      status: 404,
      code: 'APPOINTMENT_NOT_FOUND',
    });

    // A booking made in the test goes the same way.
    const booked = await unwrap(
      api.POST('/api/v1/appointments', {
        params: { header: { 'Idempotency-Key': crypto.randomUUID() } },
        body: {
          branchCode: 'SAN',
          serviceCode: 'FICA',
          startsAt: '2026-10-27T10:00:00+02:00',
          customer: { firstName: 'Nomvula', lastName: 'Dlamini', mobile: '082 555 0142' },
        },
      }),
    );
    expect(await open(booked.reference, '0142')).toEqual(booked);
    expect(await cancel(booked.reference, '0142')).toMatchObject({ status: 'CANCELLED' });
    await expect(open(booked.reference, '0142')).rejects.toMatchObject({
      code: 'APPOINTMENT_NOT_FOUND',
    });
  });

  it('retries only a retryable message, and lists it as PENDING from then on', async () => {
    const retry = (id: string) =>
      unwrap(api.POST('/api/v1/messages/{id}/retry', { params: { path: { id } } }));

    await expect(retry('not-a-uuid')).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
    await expect(retry('40000000-0000-4000-8000-0000000000ff')).rejects.toMatchObject({
      code: 'MESSAGE_NOT_FOUND',
    });
    expect(await retry('40000000-0000-4000-8000-000000000002')).toMatchObject({
      status: 'SENT',
      retryable: false,
    });
    expect(await retry('40000000-0000-4000-8000-000000000001')).toMatchObject({
      status: 'PENDING',
      attempts: 0,
      retryable: false,
    });
    const { messages } = await unwrap(api.GET('/api/v1/messages'));
    expect(messages.find((m) => m.id === '40000000-0000-4000-8000-000000000001')?.status).toBe(
      'PENDING',
    );
  });
});
