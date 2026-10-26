# Domain rules

The single register of BranchBook's business rules. Every rule has an identifier, the layer that enforces it,
and the test that proves it. Code comments and pull requests refer to rules by identifier (for example `BR-7`).

Each rule has a status. **Proven** means every test it cites exists and passes in CI. **Planned** names the milestone
that will prove it; parts already built are noted beside it. `scripts/check-test-refs.sh` runs in CI. It fails if a
rule has no status or no proof, if a proven rule cites a test that does not exist, or if the README does, so a rule
cannot be marked proven before its tests are written. Whether the tests cover every clause of a rule is a matter for
review.

How to read a rule:

- **Rule**: the behaviour, stated so a reviewer can check it without running the code.
- **Enforced in**: where the rule lives. A database constraint listed here enforces, for every code path including
  ones that bypass the application, the part of the rule it names, and only that part.
- **Proven by**: the test class and method. Names read as requirements.
- **Status**: proven, or planned for a named milestone.

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

**Enforced in.** domain (SlotCalendar.slotsOn lists the grid for availability; SlotCalendar.validate checks a requested start for booking; SlotCalendarTest proves the two agree)

**Proven by.** `SlotCalendarTest.slotsStartAtOpeningAndStepByServiceDuration`, `SlotCalendarTest.validateAgreesWithGeneratedSlotsForEverySeededHoursPatternAndDuration`, `AvailabilityApiIT.weekdayHasThirtySixFifteenMinuteSlotsWithTheBranchsCapacity`, `AppointmentApiIT.postOffGridStartReturns422SlotOffGrid`

**Status.** Proven. The grid and availability from M2; booking refuses an off-grid start from M3.

<a id="br-2"></a>
## BR-2: Slots fit inside trading hours

**Rule.** A slot is offered only if startsAt >= opens_at and startsAt + duration <= closes_at. Worked values: weekday 30-minute last start 16:30 and 45-minute 16:15; Saturday (08:00-13:00) 45-minute last start 11:45; Rustenburg Saturday (08:00-12:00) 45-minute last start 11:00 and 30-minute 11:30. On-grid starts that end after closing are 422 SLOT_OUTSIDE_HOURS: 17:00 on a 08:00-17:00 day, 12:30 for a 45-minute service on a 13:00 Saturday, 11:45 for a 45-minute service at RUS. ends_at is always computed server-side.

**Enforced in.** domain (SlotCalendar.validate) + database constraint (appointments_ends_after_starts: an appointment ends after it starts; trading hours are not known to the constraint)

**Proven by.** `SlotCalendarTest.saturdayFortyFiveMinuteLastStartIs1145`, `SlotCalendarTest.rustenburgSaturdayFortyFiveMinuteLastStartIs1100`, `SlotCalendarTest.onGridStartEndingAfterCloseIsOutsideHours`, `AvailabilityApiIT.rendersStartsAtWithJohannesburgOffset`, `AppointmentApiIT.postEndingAfterCloseReturns422SlotOutsideHours`, `AppointmentTest.endsAtIsStartPlusServiceDuration`, `AppointmentApiIT.postWithUnknownJsonFieldReturns400ValidationFailed`

**Status.** Proven. The hours and availability from M2; from M3, booking refuses a start that ends after closing, computes `ends_at` from the service's duration, and refuses a body that sends one.

<a id="br-3"></a>
## BR-3: Closed days and public holidays have no slots

**Rule.** No slots on a weekday without an operating-hours row, nor on a public holiday (Schedule 1 or proclaimed date, or the Monday derived after a Sunday holiday; Saturdays get no substitute) unless branch.open_on_public_holidays; a POST on such a day is 422 SLOT_ON_CLOSED_DAY and availability returns closedReason CLOSED_DAY | PUBLIC_HOLIDAY. Holiday coverage ends 31 Dec of the last seeded year. A WARN, logged at start-up and every day at 06:00 SAST, reports it from 60 days before the window crosses it, and again once the window has.

**Enforced in.** domain (SlotCalendar + PublicHolidayCalendar pure function)

**Proven by.** `PublicHolidayCalendarTest.sundayHoliday9Aug2026IsObservedOnMonday10Aug`, `SlotCalendarTest.publicHolidayHasNoSlots`, `SlotCalendarTest.mondayAfterASundayHolidayHasNoSlots`, `SlotCalendarTest.dayOfTheWeekWithoutHoursHasNoSlots`, `SlotCalendarTest.branchOpenOnPublicHolidaysHasItsUsualSlotsOnAHoliday`, `AvailabilityApiIT.publicHolidayReturnsTheHolidaysNameAndNoSlots`, `AvailabilityApiIT.dayWithoutTradingHoursReturnsClosedDayAndNoSlots`, `BranchApiIT.detailListsClosedDatesWithHolidayNames`, `BranchApiIT.dayWithoutTradingHoursIsListedAsAClosedDay`, `HolidayCoverageCheckTest.warnsSixtyDaysBeforeTheWindowPassesTheEndOfTheData`, `HolidayCoverageCheckTest.warnsOnceTheBookingWindowPassesTheLastSeededYear`, `ApplicationStartsIT.holidayCoverageIsCheckedEveryMorning`, `AppointmentApiIT.postForPublicHolidayReturns422SlotOnClosedDay`

**Status.** Proven. The holiday calendar and the closed days in `GET /api/v1/branches/{code}` from M1; `SlotCalendar`, availability and the holiday-coverage warning from M2; booking refuses a closed day from M3.

<a id="br-4"></a>
## BR-4: Two-hour lead time

**Rule.** Lead time: startsAt >= clock.now() + PT2H; earlier slots are absent from availability and a stale POST is 422 SLOT_WITHIN_LEAD_TIME.

**Enforced in.** domain (SlotCalendar with injected Clock)

**Proven by.** `SlotCalendarTest.excludesSlotsStartingWithinTwoHoursOfNow`, `SlotCalendarTest.slotStartingExactlyTwoHoursFromNowIsOffered`, `AvailabilityApiIT.todaysSlotsStartAfterTheLeadTime`, `AppointmentApiIT.postWithinLeadTimeReturns422SlotWithinLeadTime`

**Status.** Proven. The lead time in `SlotCalendar` and availability from M2; booking refuses a stale start from M3.

<a id="br-5"></a>
## BR-5: Thirty-day booking horizon

**Rule.** Horizon: the slot date must lie in the half-open window [today, today + P30D) in Branch.timezone, i.e. exactly 30 bookable dates (today through today + 29); availability dates or POST starts outside it return 422 DATE_OUTSIDE_BOOKING_WINDOW. The server publishes the window as bookableFrom/bookableTo on GET /branches/{code}, so the date strip never derives 'today' from the device.

**Enforced in.** domain (SlotCalendar, BookingWindow) + application service (AvailabilityQueries date check; BookAppointmentHandler step 3)

**Proven by.** `BookingWindowTest.thirtyDayHorizonGivesExactlyThirtyBookableDates`, `SlotCalendarTest.onlyTheThirtyDatesFromTodayHaveSlots`, `BranchApiIT.detailCarriesServerBookableWindow`, `BranchApiIT.windowStartsOnTheBranchsDateNotTheUtcDate`, `AvailabilityApiIT.dateOutsideWindowReturns422`, `AppointmentApiIT.postOutsideWindowReturns422DateOutsideBookingWindow`, `SlotCalendarTest.startOutsideTheWindowIsRefusedRightUpToTheLimitsOfTime`, `AppointmentApiIT.postAtTheLimitOfTimeReturns422DateOutsideBookingWindow`

**Status.** Proven. The window in `SlotCalendar`, published by `GET /api/v1/branches/{code}` and enforced by availability from M2; booking refuses a start outside it from M3.

<a id="br-6"></a>
## BR-6: Only services the branch offers

**Rule.** A branch offers a service only if a branch_services row exists; otherwise availability and booking both return 422 SERVICE_NOT_OFFERED_AT_BRANCH, as they do for an unknown or inactive service code; the composite FK makes it a schema fact.

**Enforced in.** database constraint (appointments_branch_service_fkey) + application service (BranchCalendarProvider.offerFor, used by AvailabilityQueries from M2 and by BookAppointmentHandler step 2 from M3)

**Proven by.** `AvailabilityApiIT.serviceNotOfferedAtBranchReturns422`, `AppointmentApiIT.postForServiceNotOfferedReturns422`, `SchemaConstraintIT.rawInsertViolatingEachNamedConstraintIsRejected`

**Status.** Proven. The composite foreign key from M1; availability refuses the pair from M2; from M3 booking refuses it at step 2, just after claiming the idempotency key, and the refusal rolls the whole transaction back, the key's row included, so nothing is left behind ([errors.md](errors.md#precedence)).

<a id="br-7"></a>
## BR-7: Capacity: lowest free seat under a row lock

**Rule.** At most branch_services.capacity active appointments of a service may overlap at any instant; seats are allocated as the lowest free seat under the branch_services row lock; a full slot returns 409 SLOT_FULL with up to three alternatives; a race for the last seat yields exactly one 201 (the 20 racers use 20 distinct synthetic mobiles so only the seat rule is under test).

**Enforced in.** application service (BookAppointmentHandler: the branch_services row lock, then the lowest free seat from generate_series(1, capacity)) + database constraint (appointments_seat_no_overlap: a seat is never used twice at once; the database does not cap seat_no at the capacity, only at 20)

**Proven by.** `ConcurrentBookingIT.twentyRacersForOneSeatProduceExactlyOne201`, `ConcurrentBookingIT.twentyRacersForThreeSeatsProduceExactlyThree201s`, `BookAppointmentIT.lowestFreeSeatIsTakenFirst`, `AppointmentApiIT.postWhenSlotFullReturns409WithThreeAlternatives`, `BookAppointmentIT.lockTimeoutReturns503TryAgainWithRetryAfter`

**Status.** Proven. The exclusion constraint from M1 (BR-8); the row lock, the lowest free seat and the alternatives from M3; the twenty-racer proofs, and the 503 when the lock is not granted in 2 seconds, from M5. `make demo-409` shows the race against a running stack.

<a id="br-8"></a>
## BR-8: The database rejects a double booking from any code path

**Rule.** The seat invariant holds against any code path: a raw-JDBC insert on a taken seat is rejected by the exclusion constraint even with no lock held.

**Enforced in.** database constraint (appointments_seat_no_overlap, btree_gist)

**Proven by.** `SchemaConstraintIT.overlappingSeatIsRejectedEvenWithoutTheApplication`

**Status.** Proven.

<a id="br-9"></a>
## BR-9: One person, one place at a time

**Rule.** One person, one place: a customer_mobile cannot hold two active appointments whose [starts_at, ends_at) overlap, at any branch or service; violation returns 409 CUSTOMER_OVERLAP. Decided by a pre-check under the per-mobile advisory lock; appointments_customer_no_overlap is the backstop for any path that skips the lock.

**Enforced in.** application service (BookAppointmentHandler steps 4-5) + database constraint (appointments_customer_no_overlap) mapped by ConstraintViolationTranslator

**Proven by.** `BookAppointmentIT.overlappingAppointmentAtAnotherBranchReturns409CustomerOverlap`, `BookAppointmentIT.cancelledCompletedOrNoShowAppointmentBlocksNothing`, `SchemaConstraintIT.rawInsertViolatingEachNamedConstraintIsRejected`, `ConstraintNamesIT.translatorReadsConstraintNameWithServerDetailLoggingOff`

**Status.** Proven. The backstop from M1; the check and the mapping of the backstop to 409 from M3. That the lock makes two simultaneous requests queue is BR-22.

<a id="br-10"></a>
## BR-10: One upcoming appointment per service

**Rule.** At most one upcoming active appointment per (customer_mobile, service): a second booking while an earlier one is CONFIRMED or CHECKED_IN and ends_at > now returns 409 DUPLICATE_APPOINTMENT; cancelled, completed, no-show and past rows never count. Checked under the per-mobile advisory lock (a partial unique index cannot express 'upcoming' because index predicates must be immutable). The problem body carries no reference, branch or time.

**Enforced in.** application service (BookAppointmentHandler step 5, under the step 4 pg_advisory_xact_lock)

**Proven by.** `BookAppointmentIT.sameMobileSameServiceSecondBookingReturns409WithoutRevealingReference`, `BookAppointmentIT.cancelledCompletedOrNoShowAppointmentBlocksNothing`, `BookAppointmentIT.pastConfirmedAppointmentDoesNotBlockSameServiceBooking`

**Status.** Proven.

<a id="br-11"></a>
## BR-11: Unambiguous, unguessable references

**Rule.** Every appointment gets APT-XXXX-XXXX from the 31-character alphabet via SecureRandom, stored uppercase and unique; lookup input is normalised; a collision re-runs the booking transaction once.

**Enforced in.** domain (AppointmentReference, SecureReferenceGenerator) + database constraint (appointments_reference_format, appointments_reference_key) + application service (BookAppointmentHandler re-run)

**Proven by.** `AppointmentReferenceTest.usesOnlyUnambiguousAlphabet`, `AppointmentReferenceTest.normalisesLowercaseAndMissingHyphensOnLookup`, `SchemaConstraintIT.referenceCheckMatchesAppointmentReferencePattern`, `BookAppointmentIT.referenceCollisionRerunsTransactionOnce`, `BookAppointmentIT.secondReferenceCollisionReturns500AndLeavesNothing`

**Status.** Proven. A second collision in one booking means the random source is broken, so it is a 500 that writes nothing rather than another re-run.

<a id="br-12"></a>
## BR-12: Idempotent booking

**Rule.** POST /api/v1/appointments requires Idempotency-Key (UUID); the key row is the first statement of the transaction; same key + same hash replays 201 with Idempotent-Replayed: true and a body re-rendered from the current appointment; same key + different hash is 422 IDEMPOTENCY_KEY_REUSED; a failed booking leaves no key row; a key is honoured for exactly 24 h, after which it books anew whether or not the hourly purge has removed it.

**Enforced in.** database constraint (idempotency_keys_pkey, claimed with INSERT ... ON CONFLICT DO UPDATE, which takes over only a key whose 24 hours have run out) + application service (BookAppointmentHandler step 1)

**Proven by.** `IdempotencyIT.concurrentSameKey_bookOnceAndBothReceiveSameReference`, `AppointmentApiIT.postWithoutIdempotencyKeyReturns400IdempotencyKeyRequired`, `IdempotencyIT.sameKeySamePayloadReplaysOriginal201`, `IdempotencyIT.sameKeyWithTheNumberAndTimeTypedAnotherWayReplays`, `IdempotencyIT.sameKeyDifferentPayloadReturns422`, `IdempotencyIT.failedBookingLeavesNoKeyRow`, `IdempotencyKeyPurgeJobIT.keyIsHonouredForTwentyFourHoursThenBooksAnewEvenBeforeThePurge`, `IdempotencyKeyPurgeJobIT.purgeRemovesOnlyKeysThatHaveExpired`

**Status.** Proven. Requests that arrive one after another from M3; two that arrive together from M5, where the first is held inside its transaction until the second is seen waiting on the key's row. The hash is over the parsed request, so the same details typed another way are the same request.

<a id="br-13"></a>
## BR-13: Lookup needs the reference and the last four digits

**Rule.** Viewing or cancelling requires the reference plus X-Mobile-Last4; the reference may be typed in lowercase and without hyphens; any mismatch, including an unknown or malformed reference, is 404 APPOINTMENT_NOT_FOUND (never 403), and the digits are compared in constant time; a missing or malformed header is 400 VALIDATION_FAILED and not counted; five wrong lookups of a reference block it, even for the right digits, with 429 LOOKUP_RATE_LIMITED and Retry-After until an hour after the first. The window opens with the first wrong lookup and does not slide. A lookup counts as a try from before the database is read until its digits are compared, and is released if they were right, so lookups that arrive together cannot pass the limit, and a customer's own lookups never lead to the hour-long block (a burst of them may be refused for a second, with Retry-After: 1, while the others are checked).

**Enforced in.** application service (AppointmentAccess, the one check that LookupAppointmentHandler and CancelAppointmentHandler share, + LookupAttemptLimiter: in memory, per instance, bounded to 100,000 references; docs/architecture.md, section 4)

**Proven by.** `AppointmentApiIT.getWithWrongLast4Returns404NotForbidden`, `AppointmentApiIT.getReturnsTheAppointmentWithItsPendingConfirmation`, `AppointmentApiIT.malformedLast4Returns400AndIsNotCounted`, `AppointmentApiIT.sixthWrongLast4WithinHourReturns429`, `LookupAttemptLimiterTest.fifthFailureBlocksTheReferenceUntilAnHourAfterTheFirst`, `LookupAttemptLimiterTest.windowOpensAtTheFirstFailureAndDoesNotSlide`, `LookupAttemptLimiterTest.otherReferencesAreNotAffected`, `LookupAttemptLimiterTest.lookupsArrivingTogetherAdmitOnlyAsManyAsTheLimitLeaves`, `LookupAttemptLimiterTest.lookupsInFlightCountTowardsTheLimit`, `LookupAttemptLimiterTest.lookupsWithTheRightDigitsAreNeverCounted`, `LookupAttemptLimiterTest.staleLookupDoesNotFreeAPlaceInTheNextWindow`, `LookupAttemptLimiterTest.staleLookupDoesNotLoseAFailureInTheNextWindow`, `LookupAppointmentHandlerTest.lookupWhoseReadFailsIsNeitherCountedNorLeftInFlight`, `CancelAppointmentIT.wrongDigitsReturn404AreCountedAndFiveBlockTheReferenceEvenForTheRightDigits`, `CancelAppointmentIT.malformedOrMissingLast4Returns400AndIsNotCounted`

**Status.** Proven for viewing, from M3, and for cancelling, from M6 (Tier 2). Wrong cancels and wrong lookups of a reference count towards the same five, and a cancel with the right digits is released like a lookup.

<a id="br-14"></a>
## BR-14: Cancellation rules

**Rule.** Cancellation: CONFIRMED -> CANCELLED only while now < starts_at (else 409 TOO_LATE_TO_CANCEL, and the appointment is left as it was); a repeat cancel is a 200 no-op that queues nothing, even after the start, because an already CANCELLED appointment is recognised first; the seat is released immediately and a CANCELLATION message is queued in the same transaction. Two concurrent cancels both answer 200 and write once: the loser's version check fails at the flush, and it re-reads once in a new transaction and finds the appointment CANCELLED. Cancellation is the only status change the API offers.

**Enforced in.** domain (Appointment.cancel guard, @Version) + application service (CancelAppointmentHandler)

**Proven by.** `CancelAppointmentIT.cancelFreesSeatForNewBooking`, `CancelAppointmentIT.cancelReturnsTheCancelledAppointmentWithItsSupersededConfirmationAndPendingCancellation`, `CancelAppointmentIT.repeatCancelIsA200NoOpThatQueuesNothing`, `CancelAppointmentIT.cancelAtOrAfterTheStartReturns409TooLateToCancelAndChangesNothing`, `CancelAppointmentIT.twoConcurrentCancelsBothReturn200AndQueueOneCancellation`, `AppointmentTest.cancelBeforeTheStartCancelsAndRecordsWhen`, `AppointmentTest.cancelAtOrAfterTheStartIsTooLateAndChangesNothing`, `AppointmentTest.repeatCancelIsANoOpThatKeepsTheFirstCancellation`, `CancelAppointmentHandlerTest.secondVersionConflictReturns503TryAgain`

**Status.** Proven, from M6 (Tier 2). The concurrent cancels are forced, not hoped for: a test-only trigger holds the first at its UPDATE, with the row locked, until the second is seen waiting for that lock.

<a id="br-15"></a>
## BR-15: Appointment lifecycle

**Rule.** AppointmentStatus has exactly five states with four legal edges (CONFIRMED->CANCELLED|CHECKED_IN|NO_SHOW, CHECKED_IN->COMPLETED); terminal states reject every transition; only CONFIRMED and CHECKED_IN hold a seat, and the Java ACTIVE set equals the schema's predicates; each lifecycle timestamp is set exactly when its status says the event happened.

**Enforced in.** domain (AppointmentStatus.canTransitionTo) + database constraint (status CHECK, timestamp-consistency CHECKs, partial predicates)

**Proven by.** `AppointmentStatusTest.hasExactlyTheFiveStatusesOfTheLifecycle`, `SchemaConstraintIT.statusCheckAllowsExactlyTheJavaStatuses`, `AppointmentStatusTest.terminalStatesRejectEveryTransition`, `AppointmentStatusTest.exactlyFourTransitionsAreLegal`, `AppointmentStatusTest.onlyConfirmedAndCheckedInHoldASeat`, `SchemaConstraintIT.activeStatusesMatchSchemaPredicate`, `SchemaConstraintIT.rawInsertViolatingEachNamedConstraintIsRejected`

**Status.** Proven. Which transitions the API offers is BR-14. Native SQL, from M2, spells the active statuses only through `AppointmentStatus.ACTIVE_SQL`.

<a id="br-16"></a>
## BR-16: The confirmation is committed with the booking

**Rule.** Every booking writes its PENDING CONFIRMATION rows (SMS always; EMAIL only if the Tier 3 email channel ships and an address was given) and every cancellation its CANCELLATION rows by direct call inside the same transaction; a rolled-back booking leaves no message; appointment status never depends on delivery status.

**Enforced in.** application service (BookAppointmentHandler step 9 -> NotificationOutbox.enqueueConfirmation; CancelAppointmentHandler step 6 -> NotificationOutbox.enqueueCancellation)

**Proven by.** `ConfirmationOutboxIT.bookingAndPendingMessagesCommitAtomically`, `NotificationOutboxIT.refusesToWriteOutsideATransaction`, `NotificationOutboxIT.refusesToWriteTheCancellationOutsideATransaction`, `AppointmentApiIT.postReturns201WithLocationReferenceAndPendingNotifications`, `BookAppointmentIT.failureAfterTheSmsIsWrittenRollsTheSmsBackWithTheBooking`, `NotificationDispatcherIT.retriesWithBackoffThenMarksFailedAfterFiveAttempts`, `CancelAppointmentIT.statusChangeAndCancellationCommitTogether`

**Status.** Proven. A booking held just before its commit shows neither row from outside, and both once committed (M5); a failed delivery leaves the appointment CONFIRMED (M5). A cancel held as it inserts its CANCELLATION row shows neither the status change, nor the superseded confirmation, nor the new row from outside, and all three, written by one transaction, once committed (M6).

<a id="br-17"></a>
## BR-17: Delivery with retries and a final FAILED state

**Rule.** Delivery: NotificationDispatcher runs every 2 s on its own single thread, outbox-1 (isolation from the default scheduler, not throughput) and processes up to 20 due PENDING rows per tick, one transaction per row: claim with FOR UPDATE SKIP LOCKED LIMIT 1, send, mark with an explicit UPDATE, commit. Failures retry after 2, 4, 8 and 16 s (bound from the Clock); the fifth failed attempt marks FAILED with failure_reason DELIVERY_FAILED (30 s of backoff, about 35 s end to end with the 2 s ticks). A row whose mark fails is set FAILED (failure_reason DISPATCH_ERROR, last_error 'MARK_FAILED') in a separate transaction, so a poisoned row is sent at most once more and never loops. FAILED is replayable via POST /api/v1/messages/{id}/retry (Tier 2) or the guarded runbook SQL.

**Enforced in.** application service (NotificationDispatcher.runOnce, driven by notification.infrastructure.DispatcherLoop; the MessageGateway port, implemented by SimulatedSmsGateway) + database (partial index ix_notification_messages_due, row locks)

**Proven by.** `NotificationDispatcherIT.retriesWithBackoffThenMarksFailedAfterFiveAttempts`, `NotificationDispatcherIT.marksSentWithProviderMessageId`, `NotificationDispatcherIT.twoDispatchersNeverSendTheSameMessageTwice`, `NotificationDispatcherIT.poisonedRowDoesNotBlockTheBatch`, `NotificationDispatcherIT.oneTickHandlesAtMostTwentyMessages`, `NotificationDispatcherIT.gatewayThatThrowsIsTriedAgain`, `NotificationDispatcherIT.gatewayErrorMarksTheMessageAndTheBatchCarriesOn`, `MessageGatewayTest.providerIdMustFitItsColumn`, `MessageGatewayTest.longErrorCodeIsCutToItsColumn`, `DispatcherLoopIT.theLoopDeliversOnItsOwnThreadAndLeavesBootsSchedulerInPlace`, `NotificationOutboxIT.writesOnePendingSmsWithTheBoundInstantAndCorrelationId`, `NotificationDispatcherIT.confirmationWhoseCancellationCheckFailsStaysPendingForTheNextTick`

**Status.** Proven, from M5. The dispatcher's tests drive runOnce with the clock's instant, so none sleeps; only DispatcherLoopIT waits, up to 5 seconds, for the real loop on its own thread. The retry endpoint is Tier 2; until it ships, docs/runbook.md holds the guarded SQL that replays a FAILED message.

<a id="br-18"></a>
## BR-18: The SMS fits one segment

**Rule.** The confirmation SMS fits one GSM-7 segment (<= 160 septets, GSM-7 charset only) for every seeded branch/service combination and for the configured public base URL (worst case from the column bounds: 87 fixed characters + 18 + 20 + the /manage URL, so the base URL may be at most 28 characters; longer values fail start-up), states service, branch, SAST time, reference and the /manage link, formats day and month names with Locale.ENGLISH, and never contains the full mobile, its last four digits or an ID number. A template regression can never fail a booking: the database bound is a coarse 320-character guard.

**Enforced in.** domain (NotificationTemplates, Gsm7) + start-up check (NotificationProperties computes NotificationTemplates.longestConfirmationSeptets for the configured base URL) + database constraint (services.sms_name <= 18, branches.sms_name <= 20, GSM-7-safe sms_name CHECKs, notification_messages_sms_len)

**Proven by.** `NotificationTemplatesTest.confirmationFitsOneGsm7SegmentForLongestSeededNames`, `NotificationPropertiesTest.rejectsBaseUrlThatOverflowsSms`, `NotificationTemplatesTest.rendersEnglishDayNamesUnderAnyDefaultLocale`, `NotificationTemplatesTest.rendersThePublishedExampleExactly`, `NotificationTemplatesTest.longestCombinationWithTheLongestAllowedBaseUrlFitsOneSegment`, `AppointmentApiIT.postReturns201WithLocationReferenceAndPendingNotifications`

**Status.** Proven. The GSM-7 checks on short names and the coarse length guard from M1; the template and the start-up check from M3.

<a id="br-19"></a>
## BR-19: South African mobile numbers, never logged

**Rule.** A South African mobile number is mandatory, accepted in the six documented formats, parsed only when libphonenumber reports MOBILE and the E.164 form matches the schema CHECK pattern, stored only as E.164, displayed 082 555 0101, masked 082 *** 0101 only to a caller who proved the number, redacted 082 *** **** in the inbox and every other surface; logs, MDC, the PostgreSQL log and exception messages carry no name, email, number or masked last four digits, and log lines name an appointment by its id, never its reference.

**Enforced in.** domain (MobileNumber, CustomerName, AppointmentReference: each toString is redacted) + database constraint (customer_mobile CHECK; log_error_verbosity=terse, log_min_error_statement=panic) + driver (pgjdbc logServerErrorDetail=false) + configuration (Hibernate's SQL error log off) + application service (ProblemDetailHandler never echoes input and logs failures without their messages; log lines carry appointment ids, codes and constraint names only)

**Proven by.** `MobileNumberTest.sixInputFormatsNormaliseToPlus27825550101`, `MobileNumberTest.rejectsLandline021Voip087AndTollFree080`, `MobileNumberTest.rendersDisplayMaskedRedactedAndLastFour`, `MobileNumberTest.toStringNeverShowsTheDigits`, `SchemaConstraintIT.mobileCheckMatchesMobileNumberPattern`, `AppointmentApiIT.postWithLandlineReturns422MobileNumberRequiredFieldError`, `AppointmentApiIT.responsesNeverContainUnmaskedMobile`, `NotificationOutboxIT.summariesMaskTheRecipient`, `AppointmentReferenceTest.toStringNeverShowsTheReference`, `LookupAttemptLimiterTest.attemptNeverShowsTheReference`, `ResponseToStringTest.appointmentResponseShowsNeitherTheReferenceNorTheCustomerNorTheMessages`, `ResponseToStringTest.notificationSummaryShowsNeitherTheRecipientNorTheBody`, `InboxApiIT.listsTheNewestMessagesWithTheirReferencesAndRedactedRecipients`, `LogRedactionIT.bookingLogsContainNoCustomerNameOrMobile`, `LogRedactionIT.cancelLogsContainNoCustomerNameMobileOrReference`

**Status.** Proven for every surface that exists. The mobile number CHECK and the database and driver settings from M1; parsing, masking and the log proof from M3; the simulated inbox, which shows only the redacted form, from M4; cancel, whose log lines name the appointment by id, from M6.

<a id="br-20"></a>
## BR-20: Time is stored in UTC and shown in SAST

**Rule.** Time: every instant is stored as timestamptz (UTC), slots are computed in Branch.timezone through an injected Clock, application SQL never calls now() (every time-dependent statement binds :now from the Clock; DEFAULT now() is a raw-insert convenience only), the API emits ISO-8601 with the branch offset (+02:00) through one explicit formatter, the UI formats with Intl timeZone Africa/Johannesburg and prints SAST, and JVM/containers/CI run TZ=UTC so slot output is identical under any default zone.

**Enforced in.** domain (SlotCalendar) + application service (explicit ISO_OFFSET_DATE_TIME rendering at Branch.timezone, independent of Jackson 2/3 time-zone features; :now bound in every native statement) + UI (web/src/lib/time.ts, Intl with Africa/Johannesburg)

**Proven by.** `AvailabilityApiIT.rendersStartsAtWithJohannesburgOffset`, `SlotCalendarTest.producesSameSlotsUnderAnyJvmDefaultZone`, `NativeSqlUsesBoundNowTest.noJavaSqlLiteralCallsNow`, `time.test.ts`

**Status.** Proven. Built in M2 and proven by the tests above: slots computed in the branch's zone from the injected clock, every native statement binding its instants, and the API writing every instant with the +02:00 offset. Built in M3: booking and lookup write `startsAt` and `endsAt` with the offset (`AppointmentApiIT.postReturns201WithLocationReferenceAndPendingNotifications`), and the SMS states the time in SAST. CI also runs the API's unit and API tests under `TZ=America/New_York`. Built in M4: the web app formats every time in SAST whatever the device's zone (`time.test.ts`), and CI runs the whole web suite under `TZ=UTC` and `TZ=America/New_York`.

<a id="br-21"></a>
## BR-21: One validation code, fixed precedence

**Rule.** SlotCalendar.validate returns exactly one code when several apply, by fixed precedence: DATE_OUTSIDE_BOOKING_WINDOW, SLOT_ON_CLOSED_DAY, SLOT_OUTSIDE_HOURS, SLOT_OFF_GRID, SLOT_WITHIN_LEAD_TIME (the window and the day decide whether a grid exists at all; lead time is last because it is the only one that changes as a page goes stale).

**Enforced in.** domain (SlotCalendar.validate)

**Proven by.** `SlotCalendarTest.validatePrecedence`

**Status.** Proven. From M3, booking reports that one code as the problem's `code` and on `startsAt` in `errors[]`.

<a id="br-22"></a>
## BR-22: Bookings by one mobile are serialised

**Rule.** Bookings by one mobile are serialised by pg_advisory_xact_lock(hashtextextended(mobileE164, 0)), taken after the idempotency row and before the branch_services row (fixed lock order: idempotency key, mobile, branch_services), and conflicts are reported in a fixed order: DUPLICATE_APPOINTMENT, then CUSTOMER_OVERLAP, then SLOT_FULL. Two concurrent bookings by one mobile at different branches therefore give one 201 and one 409, never a deadlock; 40P01 and 40001 from any path that bypasses the order re-run the transaction once, then 503 TRY_AGAIN.

**Enforced in.** application service (BookAppointmentHandler steps 4-5) + database (advisory lock, row locks)

**Proven by.** `ConcurrentBookingIT.sameMobileTwoBranchesOverlappingConcurrentlyGivesOne201AndOne409CustomerOverlap`, `BookAppointmentIT.duplicateIsReportedBeforeOverlap`, `BookAppointmentIT.overlapIsReportedBeforeSlotFull`, `BookAppointmentIT.deadlockOnceIsRerunAndBooks`, `BookAppointmentIT.deadlockTwiceReturns503TryAgainAndLeavesNothing`, `ProblemDetailHandlerTest.transientDatabaseFailureBecomes503WithRetryAfterTwo`

**Status.** Proven. The lock, the order and the re-run from M3; the concurrent proof, ten rounds of one customer at two branches at once, and the deadlock re-run, forced by a test-only trigger, from M5.

<a id="br-23"></a>
## BR-23: Error bodies never reveal another booking

**Rule.** No problem body reveals another appointment: 409 DUPLICATE_APPOINTMENT and 409 CUSTOMER_OVERLAP carry neither reference nor branch nor time, so a caller who knows only a cellphone number learns no credential. The residual signal (an appointment exists for that number) is an accepted demo risk closed in production by OTP-verified identity (ADR-0004).

**Enforced in.** application service (each refusal's fixed detail; ProblemDetailHandler, ConstraintViolationTranslator)

**Proven by.** `AppointmentApiIT.noProblemBodyEverContainsAnAppointmentReference`, `AppointmentApiIT.conflict409BodiesNeverContainReferenceOrStartsAt`, `BookAppointmentIT.sameMobileSameServiceSecondBookingReturns409WithoutRevealingReference`, `CancelAppointmentIT.cancelAtOrAfterTheStartReturns409TooLateToCancelAndChangesNothing`

**Status.** Proven, for booking, lookup and cancel; a cancel's 409 TOO_LATE_TO_CANCEL names no reference (M6).

<a id="br-24"></a>
## BR-24: No confirmation after a cancellation

**Rule.** A confirmation is never delivered after its appointment's cancellation: enqueueCancellation sets the appointment's PENDING or FAILED CONFIRMATION rows to FAILED with failure_reason SUPERSEDED_BY_CANCELLATION in the cancel transaction (waiting at most one in-flight send for the row lock), and retry refuses CONFIRMATION rows of a cancelled or started appointment (200 no-op returning the row unchanged).

**Enforced in.** application service (NotificationOutbox.enqueueCancellation; NotificationDispatcher, which asks booking's AppointmentDirectory before it sends a confirmation and supersedes one whose appointment is cancelled; RetryMessageHandler guard, with the retry endpoint) + database (row locks: the supersede waits for the dispatcher's lock on a message in flight)

**Proven by.** `NotificationDispatcherIT.pendingConfirmationIsSupersededByCancellation`, `NotificationDispatcherIT.confirmationInFlightWhenTheCancelArrivesIsSentBeforeTheCancellation`, `NotificationDispatcherIT.confirmationOfACancelledAppointmentResetByHandIsSupersededAtTheClaimNotSent`, `CancelAppointmentIT.cancellationSupersedesPendingAndFailedConfirmationsAndLeavesASentOneAlone`, `NotificationDispatcherIT.confirmationWhoseCancellationCheckFailsStaysPendingForTheNextTick`

**Status.** Proven, from M6 (Tier 2). A confirmation is delivered before its cancellation or never: one already in the gateway when the cancel arrives is marked SENT first, and the cancel then leaves it alone and queues the cancellation behind it. The dispatcher never locks the appointment, so a cancel (appointment, then message) and a send (message only) cannot deadlock. A dispatcher that cannot ask whether the appointment is cancelled sends nothing and marks nothing: the message stays PENDING for the next tick. The retry's refusal arrives with the retry endpoint (FR-18).

<a id="br-25"></a>
## BR-25: Past appointments never block a new booking

**Rule.** Past appointments never trap the customer: a CONFIRMED appointment whose time has passed stays CONFIRMED in v1 (no staff actor), is not cancellable (canCancel false, 409 TOO_LATE_TO_CANCEL), and does not block a new booking of the same service, because every customer rule compares against the Clock rather than stored state.

**Enforced in.** application service (BookAppointmentHandler step 5: ends_at > :now) + domain (Appointment.canCancel(now))

**Proven by.** `BookAppointmentIT.pastConfirmedAppointmentDoesNotBlockSameServiceBooking`, `AppointmentTest.canCancelOnlyBeforeTheStart`, `AppointmentTest.cancelAtOrAfterTheStartIsTooLateAndChangesNothing`, `CancelAppointmentIT.cancelAtOrAfterTheStartReturns409TooLateToCancelAndChangesNothing`

**Status.** Proven. The response's `canCancel` comes from `Appointment.canCancel(now)`, and `Appointment.cancel(now)` applies the same rule: from M6, a cancel at or after the start is 409 TOO_LATE_TO_CANCEL and leaves the appointment CONFIRMED.
