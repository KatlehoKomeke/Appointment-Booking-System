# ADR-0001: One deployable and one database, packaged by capability

- **Status:** Accepted
- **Date:** 2026-10-22

## Context

BranchBook has one bounded context, branch appointments, and one team. Its hardest rule, that a seat is never
double-booked, must hold inside a single database transaction. Reviewers must be able to run the whole system with
one command. Splitting the system would add network hops and partial failures without adding a capability.

## Decision

- One Spring Boot jar serves the React app and the REST API, over one PostgreSQL database.
- The code is packaged by capability: `catalogue`, `scheduling`, `booking`, `notification` and `shared`. Each has
  `api`, `application`, `domain` and `infrastructure` layers.
- Allowed dependencies: `scheduling` uses `catalogue`; `booking` uses `catalogue` and `scheduling`; `notification`
  uses `booking` and `catalogue`; everything may use `shared`. `booking` never depends on `notification`: it
  defines the `ConfirmationOutbox` port, and `notification` implements it.
- Four ArchUnit tests enforce the boundaries: no package cycles, no Spring imports in `domain`, controllers depend
  on application code only, and only application code opens transactions.
- The only asynchronous element is the in-process outbox dispatcher ([ADR-0003](0003-transactional-outbox-for-confirmations.md)).

## Alternatives considered

- **Microservices.** A booking and its confirmation would span services, and the seat invariant would need
  distributed coordination. More moving parts for one team and one invariant.
- **Spring Modulith.** A useful library, but a further concept to learn and explain. Four ArchUnit rules give the
  same boundary checks with less machinery.
- **A message broker (Kafka, RabbitMQ).** There is no second consumer yet. The outbox table is the seam where a
  broker would attach later.

## Consequences

- One `docker compose` file runs everything; one CI pipeline tests everything.
- Module seams make extraction possible later. `notification` is the natural first candidate, through an outbox
  relay to Kafka.
- Cross-module calls go through application-level types. This is a convention checked in review, not a test.
