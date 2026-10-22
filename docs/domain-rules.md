# Domain rules

The single register of BranchBook's business rules. Every rule has an identifier, the layer that enforces it,
and the test that proves it. Code comments and pull requests refer to rules by identifier (for example `BR-7`).

**Status: planned.** The tests named here are written as the milestones land. From M1, `scripts/check-test-refs.sh`
fails CI if any test cited in this file or in the README does not exist, so this register cannot drift from the code.

How to read a rule:

- **Rule**: the behaviour, stated so a reviewer can check it without running the code.
- **Enforced in**: where the rule lives. When a database constraint is listed, the rule holds even for code that bypasses the application.
- **Proven by**: the test class and method. Names read as requirements.

## Contents

- [BR-1: Slots follow the service grid](#br-1)
- [BR-2: Slots fit inside trading hours](#br-2)
- [BR-3: Closed days and public holidays have no slots](#br-3)
- [BR-4: Two-hour lead time](#br-4)
- [BR-5: Thirty-day booking horizon](#br-5)
- [BR-6: Only services the branch offers](#br-6)
- [BR-7: Capacity: lowest free seat under a row lock](#br-7)
- [BR-8: The database rejects a double booking from any code path](#br-8)
- [BR-9: One person, one place at a time](#br-9)
- [BR-10: One upcoming appointment per service](#br-10)
- [BR-11: Unambiguous, unguessable references](#br-11)
- [BR-12: Idempotent booking](#br-12)
- [BR-13: Lookup needs the reference and the last four digits](#br-13)
- [BR-14: Cancellation rules](#br-14)
- [BR-15: Appointment lifecycle](#br-15)
- [BR-16: The confirmation is committed with the booking](#br-16)
- [BR-17: Delivery with retries and a final FAILED state](#br-17)
- [BR-18: The SMS fits one segment](#br-18)
- [BR-19: South African mobile numbers, never logged](#br-19)
- [BR-20: Time is stored in UTC and shown in SAST](#br-20)
- [BR-21: One validation code, fixed precedence](#br-21)
- [BR-22: Bookings by one mobile are serialised](#br-22)
- [BR-23: Error bodies never reveal another booking](#br-23)
- [BR-24: No confirmation after a cancellation](#br-24)
- [BR-25: Past appointments never block a new booking](#br-25)

<a id="br-1"></a>
## BR-1: Slots follow the service grid

**Rule.** Slots start at opens_at + k x service.duration_minutes (15/30/45) in Branch.timezone; a startsAt off that grid is rejected with 422 SLOT_OFF_GRID.

**Enforced in.** domain (SlotCalendar.validate; reused by availability and booking)

**Proven by.** `SlotCalendarTest.slotsStartAtOpeningAndStepByServiceDuration`

<a id="br-2"></a>
## BR-2: Slots fit inside trading hours

**Rule.** A slot is offered only if startsAt >= opens_at and startsAt + duration <= closes_at. Worked values: weekday 30-minute last start 16:30 and 45-minute 16:15; Saturday (08:00-13:00) 45-minute last start 11:45; Rustenburg Saturday (08:00-12:00) 45-minute last start 11:00 and 30-minute 11:30. On-grid starts that end after closing are 422 SLOT_OUTSIDE_HOURS: 17:00 on a 08:00-17:00 day, 12:30 for a 45-minute service on a 13:00 Saturday, 11:45 for a 45-minute service at RUS. ends_at is always computed server-side.

**Enforced in.** domain (SlotCalendar.validate) + database constraint (appointments_ends_after_starts)

**Proven by.** `SlotCalendarTest.saturdayFortyFiveMinuteLastStartIs1145`, `SlotCalendarTest.rustenburgSaturdayFortyFiveMinuteLastStartIs1100`, `SlotCalendarTest.onGridStartEndingAfterCloseIsOutsideHours`

<a id="br-3"></a>
## BR-3: Closed days and public holidays have no slots

**Rule.** No slots on a weekday without an operating-hours row, nor on a public holiday (Schedule 1 or proclaimed date, or the Monday derived after a Sunday holiday; Saturdays get no substitute) unless branch.open_on_public_holidays; a POST on such a day is 422 SLOT_ON_CLOSED_DAY and availability returns closedReason CLOSED_DAY | PUBLIC_HOLIDAY. Holiday coverage ends 31 Dec of the last seeded year and is reported (start-up WARN, health detail) before the window crosses it.

**Enforced in.** domain (SlotCalendar + PublicHolidayCalendar pure function)

**Proven by.** `PublicHolidayCalendarTest.sundayHoliday9Aug2026IsObservedOnMonday10Aug`

<a id="br-4"></a>
## BR-4: Two-hour lead time

**Rule.** Lead time: startsAt >= clock.now() + PT2H; earlier slots are absent from availability and a stale POST is 422 SLOT_WITHIN_LEAD_TIME.

**Enforced in.** domain (SlotCalendar with injected Clock)

**Proven by.** `SlotCalendarTest.excludesSlotsStartingWithinTwoHoursOfNow`

<a id="br-5"></a>
## BR-5: Thirty-day booking horizon

**Rule.** Horizon: the slot date must lie in the half-open window [today, today + P30D) in Branch.timezone, i.e. exactly 30 bookable dates (today through today + 29); availability dates or POST starts outside it return 422 DATE_OUTSIDE_BOOKING_WINDOW. The server publishes the window as bookableFrom/bookableTo on GET /branches/{code}, so the date strip never derives 'today' from the device.

**Enforced in.** domain (SlotCalendar, BookingWindow) + application service (AvailabilityController date check)

**Proven by.** `AvailabilityApiIT.dateOutsideWindowReturns422`, `SlotCalendarTest.horizonHasExactlyThirtyBookableDates`

<a id="br-6"></a>
## BR-6: Only services the branch offers

**Rule.** A branch offers a service only if a branch_services row exists; otherwise availability is empty and booking returns 422 SERVICE_NOT_OFFERED_AT_BRANCH; the composite FK makes it a schema fact.

**Enforced in.** database constraint (appointments_branch_service_fkey) + application service (BookAppointmentHandler step 2)

**Proven by.** `AvailabilityApiIT.serviceNotOfferedAtBranchReturns422`

<a id="br-7"></a>
## BR-7: Capacity: lowest free seat under a row lock

**Rule.** At most branch_services.capacity active appointments of a service may overlap at any instant; seats are allocated as the lowest free seat under the branch_services row lock; a full slot returns 409 SLOT_FULL with up to three alternatives; a race for the last seat yields exactly one 201 (the 20 racers use 20 distinct synthetic mobiles so only the seat rule is under test).

**Enforced in.** database constraint (appointments_seat_no_overlap) + application service (BookAppointmentHandler FOR UPDATE + generate_series seat query)

**Proven by.** `ConcurrentBookingIT.twentyRacersForOneSeatProduceExactlyOne201`

<a id="br-8"></a>
## BR-8: The database rejects a double booking from any code path

**Rule.** The seat invariant holds against any code path: a raw-JDBC insert on a taken seat is rejected by the exclusion constraint even with no lock held.

**Enforced in.** database constraint (appointments_seat_no_overlap, btree_gist)

**Proven by.** `DatabaseBackstopIT.directInsertOnTakenSeatIsRejectedByExclusionConstraint`

<a id="br-9"></a>
## BR-9: One person, one place at a time

**Rule.** One person, one place: a customer_mobile cannot hold two active appointments whose [starts_at, ends_at) overlap, at any branch or service; violation returns 409 CUSTOMER_OVERLAP. Decided by a pre-check under the per-mobile advisory lock; appointments_customer_no_overlap is the backstop for any path that skips the lock.

**Enforced in.** application service (BookAppointmentHandler steps 4-5) + database constraint (appointments_customer_no_overlap) mapped by ConstraintViolationTranslator

**Proven by.** `BookAppointmentIT.overlappingAppointmentAtAnotherBranchReturns409CustomerOverlap`

<a id="br-10"></a>
## BR-10: One upcoming appointment per service

**Rule.** At most one upcoming active appointment per (customer_mobile, service): a second booking while an earlier one is CONFIRMED or CHECKED_IN and ends_at > now returns 409 DUPLICATE_APPOINTMENT; cancelled, completed, no-show and past rows never count. Checked under the per-mobile advisory lock (a partial unique index cannot express 'upcoming' because index predicates must be immutable). The problem body carries no reference, branch or time.

**Enforced in.** application service (BookAppointmentHandler step 5, under the step 4 pg_advisory_xact_lock)

**Proven by.** `BookAppointmentIT.sameMobileSameServiceSecondBookingReturns409WithoutRevealingReference`

<a id="br-11"></a>
## BR-11: Unambiguous, unguessable references

**Rule.** Every appointment gets APT-XXXX-XXXX from the 31-character alphabet via SecureRandom, stored uppercase and unique; lookup input is normalised; a collision re-runs the booking transaction once.

**Enforced in.** domain (AppointmentReference) + database constraint (appointments_reference_key)

**Proven by.** `AppointmentReferenceTest.usesOnlyUnambiguousAlphabet`

<a id="br-12"></a>
## BR-12: Idempotent booking

**Rule.** POST /api/v1/appointments requires Idempotency-Key (UUID); the key row is the first statement of the transaction; same key + same hash replays 201 with Idempotent-Replayed: true and a body re-rendered from the current appointment; same key + different hash is 422 IDEMPOTENCY_KEY_REUSED; a failed booking leaves no key row; keys are honoured for 24 h and until the hourly purge (at most 25 h).

**Enforced in.** database constraint (idempotency_keys_pkey via ON CONFLICT DO NOTHING) + application service (BookAppointmentHandler step 1)

**Proven by.** `IdempotencyIT.concurrentSameKey_bookOnceAndBothReceiveSameReference`

<a id="br-13"></a>
## BR-13: Lookup needs the reference and the last four digits

**Rule.** Viewing or cancelling requires the reference plus X-Mobile-Last4; any mismatch, including an unknown reference, is 404 APPOINTMENT_NOT_FOUND (never 403); a missing or malformed header is 400 VALIDATION_FAILED and not counted; five failures per reference per hour give 429 LOOKUP_RATE_LIMITED with Retry-After.

**Enforced in.** application service (LookupAppointmentHandler + LookupAttemptLimiter, Caffeine, bounded)

**Proven by.** `AppointmentApiIT.getWithWrongLast4Returns404NotForbidden`

<a id="br-14"></a>
## BR-14: Cancellation rules

**Rule.** Cancellation: CONFIRMED -> CANCELLED only while now < starts_at (else 409 TOO_LATE_TO_CANCEL); a repeat cancel is a 200 no-op that queues nothing; the seat is released immediately and a CANCELLATION message is queued in the same transaction.

**Enforced in.** domain (Appointment.cancel guard, @Version) + application service (CancelAppointmentHandler)

**Proven by.** `CancelAppointmentIT.cancelFreesSeatForNewBooking`

<a id="br-15"></a>
## BR-15: Appointment lifecycle

**Rule.** AppointmentStatus has exactly five states with four legal edges (CONFIRMED->CANCELLED|CHECKED_IN|NO_SHOW, CHECKED_IN->COMPLETED); terminal states reject every transition; only CONFIRMED->CANCELLED is reachable through the API; the Java ACTIVE set equals the schema predicate and ACTIVE_SQL is its only spelling in native SQL.

**Enforced in.** domain (AppointmentStatus.canTransitionTo) + database constraint (status CHECK, timestamp-consistency CHECKs, partial predicates)

**Proven by.** `AppointmentStatusTest.terminalStatesRejectEveryTransition`

<a id="br-16"></a>
## BR-16: The confirmation is committed with the booking

**Rule.** Every booking writes its PENDING CONFIRMATION rows (SMS always; EMAIL only if the Tier 3 email channel ships and an address was given) and every cancellation its CANCELLATION rows by direct call inside the same transaction; a rolled-back booking leaves no message; appointment status never depends on delivery status.

**Enforced in.** application service (BookAppointmentHandler step 9 -> NotificationOutbox.enqueueConfirmation)

**Proven by.** `ConfirmationOutboxIT.bookingAndPendingMessagesCommitAtomically`

<a id="br-17"></a>
## BR-17: Delivery with retries and a final FAILED state

**Rule.** Delivery: NotificationDispatcher runs every 2 s on its own single-thread outboxScheduler (isolation from the default scheduler, not throughput) and processes up to 20 due PENDING rows per tick, one transaction per row: claim with FOR UPDATE SKIP LOCKED LIMIT 1, send, mark with an explicit UPDATE, commit. Failures retry after 2, 4, 8 and 16 s (bound from the Clock); the fifth failed attempt marks FAILED with failure_reason DELIVERY_FAILED (30 s of backoff, about 35 s end to end with the 2 s ticks). A row whose mark fails is set FAILED (failure_reason DISPATCH_ERROR, last_error 'MARK_FAILED') in a separate transaction, so a poisoned row is sent at most once more and never loops. FAILED is replayable via POST /api/v1/messages/{id}/retry (Tier 2) or the guarded runbook SQL.

**Enforced in.** application service (DispatchDueMessages) + database (partial index ix_notification_messages_due, row locks)

**Proven by.** `NotificationDispatcherIT.retriesWithBackoffThenMarksFailedAfterFiveAttempts`

<a id="br-18"></a>
## BR-18: The SMS fits one segment

**Rule.** The confirmation SMS fits one GSM-7 segment (<= 160 septets, GSM-7 charset only) for every seeded branch/service combination and for the configured public base URL (worst case from the column bounds: 87 fixed characters + 18 + 20 + the /manage URL, so the base URL may be at most 28 characters; longer values fail start-up), states service, branch, SAST time, reference and the /manage link, formats day and month names with Locale.ENGLISH, and never contains the full mobile, its last four digits or an ID number. A template regression can never fail a booking: the database bound is a coarse 320-character guard.

**Enforced in.** domain (NotificationTemplates, Gsm7) + start-up check (NotificationProperties calls SmsBudget for the configured base URL) + database constraint (services.sms_name <= 18, branches.sms_name <= 20, GSM-7-safe sms_name CHECKs, notification_messages_sms_len)

**Proven by.** `NotificationTemplatesTest.confirmationFitsOneGsm7SegmentForLongestSeededNames`, `NotificationPropertiesTest.rejectsBaseUrlThatOverflowsSms`, `NotificationTemplatesTest.rendersEnglishDayNamesUnderAnyDefaultLocale`

<a id="br-19"></a>
## BR-19: South African mobile numbers, never logged

**Rule.** A South African mobile number is mandatory, accepted in the six documented formats, parsed only when libphonenumber reports MOBILE and the E.164 form matches the schema CHECK pattern, stored only as E.164, displayed 082 123 4567, masked 082 *** 4567 only to a caller who proved the number, redacted 082 *** **** in the inbox and every other surface; logs, MDC, the PostgreSQL log and exception messages carry no name, email, number or masked last four digits.

**Enforced in.** domain (MobileNumber, CustomerName) + database constraint (customer_mobile CHECK; log_error_verbosity=terse) + driver (pgjdbc logServerErrorDetail=false) + application service (ProblemDetailHandler never echoes input; log events carry messageId, reference, channel only)

**Proven by.** `MobileNumberTest.sixInputFormatsNormaliseToPlus27821234567`, `LogRedactionTest.bookingLogsContainNoCustomerNameOrMobile`

<a id="br-20"></a>
## BR-20: Time is stored in UTC and shown in SAST

**Rule.** Time: every instant is stored as timestamptz (UTC), slots are computed in Branch.timezone through an injected Clock, application SQL never calls now() (every time-dependent statement binds :now from the Clock; DEFAULT now() is a raw-insert convenience only), the API emits ISO-8601 with the branch offset (+02:00) through one explicit formatter, the UI formats with Intl timeZone Africa/Johannesburg and prints SAST, and JVM/containers/CI run TZ=UTC so slot output is identical under any default zone.

**Enforced in.** domain (SlotCalendar) + application service (explicit ISO_OFFSET_DATE_TIME rendering at Branch.timezone, independent of Jackson 2/3 time-zone features; :now bound in every native statement) + UI (lib/formatSast)

**Proven by.** `AvailabilityApiIT.rendersStartsAtWithJohannesburgOffset`, `NativeSqlUsesBoundNowTest.noJavaSqlLiteralCallsNow`

<a id="br-21"></a>
## BR-21: One validation code, fixed precedence

**Rule.** SlotCalendar.validate returns exactly one code when several apply, by fixed precedence: DATE_OUTSIDE_BOOKING_WINDOW, SLOT_ON_CLOSED_DAY, SLOT_OUTSIDE_HOURS, SLOT_OFF_GRID, SLOT_WITHIN_LEAD_TIME (the window and the day decide whether a grid exists at all; lead time is last because it is the only one that changes as a page goes stale).

**Enforced in.** domain (SlotCalendar.validate)

**Proven by.** `SlotCalendarTest.validatePrecedence`

<a id="br-22"></a>
## BR-22: Bookings by one mobile are serialised

**Rule.** Bookings by one mobile are serialised by pg_advisory_xact_lock(hashtextextended(mobileE164, 0)), taken after the idempotency row and before the branch_services row (fixed lock order: idempotency key, mobile, branch_services), and conflicts are reported in a fixed order: DUPLICATE_APPOINTMENT, then CUSTOMER_OVERLAP, then SLOT_FULL. Two concurrent bookings by one mobile at different branches therefore give one 201 and one 409, never a deadlock; 40P01 and 40001 from any path that bypasses the order re-run the transaction once, then 503 TRY_AGAIN.

**Enforced in.** application service (BookAppointmentHandler steps 4-5) + database (advisory lock, row locks)

**Proven by.** `ConcurrentBookingIT.sameMobileTwoBranchesOverlappingConcurrentlyGivesOne201AndOne409CustomerOverlap`

<a id="br-23"></a>
## BR-23: Error bodies never reveal another booking

**Rule.** No problem body reveals another appointment: 409 DUPLICATE_APPOINTMENT and 409 CUSTOMER_OVERLAP carry neither reference nor branch nor time, so a caller who knows only a cellphone number learns no credential. The residual signal (an appointment exists for that number) is an accepted demo risk closed in production by OTP-verified identity (ADR-0004).

**Enforced in.** application service (ProblemDetailHandler, ConstraintViolationTranslator)

**Proven by.** `AppointmentApiIT.noProblemBodyEverContainsAnAppointmentReference`

<a id="br-24"></a>
## BR-24: No confirmation after a cancellation

**Rule.** A confirmation is never delivered after its appointment's cancellation: enqueueCancellation sets the appointment's PENDING or FAILED CONFIRMATION rows to FAILED with failure_reason SUPERSEDED_BY_CANCELLATION in the cancel transaction (waiting at most one in-flight send for the row lock), and retry refuses CONFIRMATION rows of a cancelled or started appointment (200 no-op returning the row unchanged).

**Enforced in.** application service (NotificationOutbox.enqueueCancellation, RetryMessageHandler guard)

**Proven by.** `NotificationDispatcherIT.pendingConfirmationIsSupersededByCancellation`

<a id="br-25"></a>
## BR-25: Past appointments never block a new booking

**Rule.** Past appointments never trap the customer: a CONFIRMED appointment whose time has passed stays CONFIRMED in v1 (no staff actor), is not cancellable (canCancel false, 409 TOO_LATE_TO_CANCEL), and does not block a new booking of the same service, because every customer rule compares against the Clock rather than stored state.

**Enforced in.** application service (BookAppointmentHandler step 5: ends_at > :now) + domain (Appointment.canCancel(now))

**Proven by.** `BookAppointmentIT.pastConfirmedAppointmentDoesNotBlockSameServiceBooking`
