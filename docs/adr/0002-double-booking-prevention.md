# ADR-0002: Double booking is prevented by exclusion constraints and a fixed lock order

- **Status:** Accepted
- **Date:** 2026-10-22

## Context

Several customers can try to book the last seat of a slot at the same moment. A branch offers each service with a
capacity, the number of consultants who can see customers at once. A customer must also not hold two overlapping
appointments, nor two upcoming appointments for the same service. Clients retry on flaky mobile networks.

## Decision

- **The database holds the invariants.** Two `EXCLUDE USING gist` constraints over the half-open range
  `tstzrange(starts_at, ends_at, '[)')`, partial on active statuses, need the `btree_gist` extension:
  - `appointments_seat_no_overlap`: no two active appointments share a branch, service and seat at overlapping times.
  - `appointments_customer_no_overlap`: no mobile number holds two active appointments at overlapping times.
- **The handler makes the outcome deterministic.** Every booking takes its locks in the same order: the
  idempotency key row, then `pg_advisory_xact_lock` on the customer's mobile, then `SELECT ... FOR UPDATE` on the
  `branch_services` row. It then picks the lowest free seat. With nothing free, the result is
  `409 SLOT_FULL` with up to three alternative times.
- **One upcoming appointment per service** is a pre-check under the mobile lock (`ends_at > :now`). A constraint
  cannot express it, because PostgreSQL index predicates cannot refer to the current time.
- Constraint violations are translated to `409` codes by constraint name, never by parsing message text.

## Alternatives considered

- **Seat number as "count + 1".** After any cancellation it picks a taken seat and returns false `409`s.
- **Read, then insert, without a lock.** Two requests both read "one seat left" and both insert.
- **Optimistic locking (`@Version`) alone.** It protects one row, not the absence of overlapping rows.
- **A unique index on the start time.** It only works if every service shares one slot grid, which is not enforced.
- **SERIALIZABLE isolation everywhere.** It is correct, but it turns contention into retry storms.

## Consequences

- Both invariants hold even for code that bypasses the application. `SchemaConstraintIT` proves this with raw
  JDBC: `overlappingSeatIsRejectedEvenWithoutTheApplication` for the seat, and the `appointments_customer_no_overlap`
  case of `rawInsertViolatingEachNamedConstraintIsRejected` for the customer.
- `ConcurrentBookingIT.twentyRacersForOneSeatProduceExactlyOne201` will prove the race outcome on real PostgreSQL
  (planned for M5, BR-7).
- The schema needs real PostgreSQL with `btree_gist`. Distributed "PostgreSQL-compatible" databases such as Aurora
  DSQL or CockroachDB cannot run it.
- The fixed lock order rules out deadlocks between bookings; a deadlock from any other path is retried once, then
  answered with `503 TRY_AGAIN`.

## Notes

**2026-10-26.** Facts that have changed or need stating more precisely since the record was accepted. The decision
stands.

- **The race is proven.** `ConcurrentBookingIT` (M5) releases twenty bookings at once through the whole application:
  Polokwane's one FICA seat gives exactly one `201` and nineteen `409 SLOT_FULL`, and Sandton's three seats give
  three `201`s on three different seats. The racers go through MockMvc rather than a running server; the
  transactions, locks and connection pool are the real ones, and the HTTP layer is not what is under test. `make
  demo-409` shows the same race against a running stack.
- **A backstop violation gets the same answer as the check.** If a path ever skips the row lock,
  `appointments_seat_no_overlap` and `appointments_customer_no_overlap` are translated by name into `409 SLOT_FULL`
  and `409 CUSTOMER_OVERLAP` (`BookAppointmentIT.constraintViolationIsRaisedAtInsertNotAtCommit`).
- **A lock not granted in 2 seconds is a 503.** Every session sets `lock_timeout` to 2 s and `statement_timeout` to 5
  s, so a booking stuck behind a held seat lock answers `503 TRY_AGAIN` with `Retry-After: 2`
  (`BookAppointmentIT.lockTimeoutReturns503TryAgainWithRetryAfter`).
- **The deadlock re-run is proven by fault injection.** A test-only trigger raises `40P01` on the first insert, and
  the re-run books; raised every time, the booking answers `503 TRY_AGAIN` and writes nothing
  (`BookAppointmentIT.deadlockOnceIsRerunAndBooks`, `deadlockTwiceReturns503TryAgainAndLeavesNothing`). A second
  reference collision in one booking means the random source is broken, and is a `500`.
