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
