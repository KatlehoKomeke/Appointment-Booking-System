# Architecture

This document explains how BranchBook is built and why. The decisions behind it are recorded one per file in
[docs/adr](adr/README.md); the business rules are in [domain-rules.md](domain-rules.md). The whole design,
including the parts not built yet, is on the [architecture page](https://claude.ai/artifact/X4b1pc9RSCboanuXBSn6oX).

**Status: design complete; milestones M0 to M5 are built.** M0 is the skeleton: one jar that serves the app,
PostgreSQL 17 migrated by Flyway, health probes and security headers. M1 adds the whole schema, the seeded
catalogue, the `catalogue` module with the public-holiday rule, and the shared error and correlation-id handling. M2
adds the `scheduling` module (`SlotCalendar` and the availability API) and the committed OpenAPI contract that the
web app's types are generated from. M3 adds the `booking` module's transaction and lookup, and the part of
`notification` that writes and reads the confirmation SMS. M4 adds the web app's screens: the five-step booking
flow, the confirmation page with its live delivery status, `/manage` and the simulated inbox, built to the
[UI design](../web/README.md#ui-design). M5 adds the dispatcher that delivers the SMS through a simulated gateway,
the simulated inbox's API and the concurrent proofs. Each section opens with its status. Diagrams are Mermaid, or SVG files in
[assets/diagrams](assets/diagrams/README.md); solid parts are built and dashed parts are planned, with their
milestone.

**Contents:** [1. What it does](#1-what-the-system-does) ·
[2. System and containers](#2-system-context-and-containers) ·
[2.1 One request](#21-one-request-through-the-jar) ·
[3. Modules](#3-modules-and-the-dependency-rule) ·
[4. The booking transaction](#4-the-booking-transaction) ·
[5. The confirmation](#5-the-simulated-confirmation) ·
[6. Appointment lifecycle](#6-appointment-lifecycle) ·
[7. Data model](#7-data-model) ·
[8. Time](#8-time) ·
[9. Personal information](#9-personal-information) ·
[10. Deployment](#10-deployment)

## 1. What the system does

*Status: describes the finished v1. Built so far: branches and availability (M1, M2), and booking and lookup with
the confirmation SMS written as PENDING (M3), the booking screens (M4), and the SMS's delivery (M5). Cancellation
is Tier 2.*

A customer of the fictional Fynbos Bank picks a branch, a service, a date and a time slot, enters a name and a
South African mobile number, and gets a booking reference such as `APT-7K3M-9Q2X`. A simulated SMS confirmation
follows within a few seconds, and its delivery status is visible on the confirmation page. The customer can later
look the appointment up, and cancel it, with the reference and the last four digits of their mobile number.

## 2. System context and containers

*Status: Built, except cancellation (Tier 2): the jar, the React app's booking screens over its typed API client
(M4), the branch, availability, booking and lookup API, the confirmation SMS with its dispatcher and simulated SMS
gateway (M5), and PostgreSQL 17.*

![BranchBook at a glance: one Spring Boot jar serves the React app and the REST API over PostgreSQL 17. Built: the booking screens, branches, availability, the OpenAPI contract, booking and lookup with the PENDING confirmation SMS, and the dispatcher that sends it through a simulated SMS gateway. Dashed and planned: cancel (Tier 2). Code ships through GitHub Actions and OpenID Connect to AWS, where the hosted demo at branchbook.click is deployed by hand.](assets/diagrams/system-at-a-glance.svg)

- **One deployable, one database.** The jar serves the single-page app and the REST API under `/api/v1`, so there
  is one origin and no CORS. Locally it is plain HTTP on `127.0.0.1`; in the hosted demo the load balancer ends
  HTTPS ([10.3](#103-in-aws)).
- **Everything on the customer path is synchronous.** Background work runs in the same jar: `HolidayCoverageCheck`
  at start-up and daily at 06:00 SAST (M2), `IdempotencyKeyPurgeJob` every hour (M3) and the dispatcher (M5), which
  delivers the outbox rows the booking transaction writes
  ([ADR-0003](adr/0003-transactional-outbox-for-confirmations.md)). `RetentionPurgeJob` runs every hour only where a
  deployment switches it on, as the hosted demo does, and deletes each booking 48 hours after it was made
  ([runbook](runbook.md#did-the-48-hour-purge-run)).
- **Real gateways plug in later.** The dispatcher sends through a `MessageGateway` port, which the simulated SMS
  gateway implements; a real SMS provider would be one more implementation of it.

### 2.1 One request through the jar

*Status: Built in M2.* This is `GET /api/v1/branches/RUS/availability`, the call behind the slot grid, as the code
runs it today.

```mermaid
%%{init: {"sequence": {"width": 96, "actorMargin": 14, "noteMargin": 6, "boxMargin": 6}}}%%
sequenceDiagram
    autonumber
    participant B as Browser
    participant F as Filters
    participant C as Availability<br/>Controller
    participant Q as Availability<br/>Queries
    participant Cat as catalogue<br/>BranchCalendar<br/>Provider
    participant Bk as booking<br/>JdbcBooked<br/>Intervals
    participant DB as PostgreSQL
    B->>F: GET …/RUS/availability
    Note over F: correlation id,<br/>headers
    F->>C: request
    C->>Q: availability(…)
    Note over Q: read-only<br/>transaction
    Q->>Cat: calendarFor, offerFor
    Cat->>DB: branch, hours,<br/>holidays, offer
    Cat-->>Q: calendar, offer
    Note over Q: window,<br/>closed day
    Q->>Bk: activeOn(…)
    Bk->>DB: active seats
    Bk-->>Q: booked intervals
    Note over Q: SlotCalendar<br/>.slotsOn
    Q-->>C: DayAvailability
    C-->>B: 200 JSON
```

The numbers follow the code:

- **1 to 3.** `GET /api/v1/branches/RUS/availability?service=HOME&date=2026-10-27`. `CorrelationIdFilter` runs first
  and sets `X-Correlation-Id`; the security filters add the Content-Security-Policy and the other headers. The
  controller calls `AvailabilityQueries.availability`, which opens one read-only transaction.
- **4 to 6.** `BranchCalendarProvider.calendarFor` and `offerFor` return the `BranchCalendar` and the
  `ServiceOffer`. `SlotCalendar` then checks the booking window, and the calendar says whether the day is closed.
- **7 to 9.** `BookedIntervalsPort.activeOn`, implemented by `booking`'s `JdbcBookedIntervals`, reads the
  CONFIRMED and CHECKED_IN appointments that day (`AppointmentStatus.ACTIVE_SQL`), and `SlotCalendar.slotsOn`
  counts the free seats in each slot.
- **10 to 11.** The answer is JSON whose times carry the `+02:00` offset.

A refused branch, service or date becomes an RFC 9457 `application/problem+json` body whose `instance` is
`urn:branchbook:request:` followed by the correlation id ([errors.md](errors.md)). `AvailabilityQueries` checks the
branch, then the service, then the booking window, so each request fails with at most one code; on a closed day it
returns no slots without reading the appointments.

## 3. Modules and the dependency rule

*Status: Built. `catalogue`, `scheduling`, `booking` and `shared` are built; `notification` writes and reads the
outbox, delivers it through its dispatcher (M5), and serves the simulated inbox.*

The back end is a modular monolith ([ADR-0001](adr/0001-modular-monolith.md)), packaged by capability. Each arrow
reads "depends on".

```mermaid
flowchart TB
    notification["notification · built, M3 and M5<br/>outbox writer, dispatcher, inbox"]
    booking["booking · built, M3<br/>Appointment, booking, lookup"]
    scheduling["scheduling · built, M2<br/>SlotCalendar, availability"]
    catalogue["catalogue · built, M1<br/>branches, services, hours, holidays"]
    notification -- "implements ConfirmationOutbox" --> booking
    booking -- "SlotCalendar, implements BookedIntervalsPort" --> scheduling
    booking -- "calendars, offers" --> catalogue
    scheduling -- "calendars and service offers" --> catalogue
```

Every module may use `shared` (config, correlation, problem, security, time, web), and `shared` uses no module.
[ADR-0001](adr/0001-modular-monolith.md) also allows `notification` to use `catalogue`, but it does not need to.

| Module | Status | Responsibility |
|---|---|---|
| `catalogue` | Built (M1, M2) | Branches, services, weekday trading hours, capacities and public holidays. Read-only at runtime; seeded by Flyway. |
| `scheduling` | Built (M2) | `SlotCalendar`, a pure function that computes and validates slots in the branch's time zone, and the availability query. It learns which seats are taken through the `BookedIntervalsPort`, which `booking` implements, so it never reads the appointments table itself. |
| `booking` | Built (M3); cancellation Tier 2 | The `Appointment` aggregate, the booking transaction, idempotency and lookup; cancellation in Tier 2. |
| `notification` | Built (M3, M5) | The outbox writer, the message templates, the read of an appointment's messages, the dispatcher with the `MessageGateway` port and its simulated SMS gateway, and the simulated inbox. It reads nothing from `catalogue`: the booking hands it the branch's and the service's short names with the appointment. |
| `shared` | Built | Problem responses, correlation ids, security headers, the clock and configuration. |

Inside every module the layers are `api` (HTTP only), `application` (use cases; the only layer that opens
transactions), `domain` (plain Java with no Spring imports; `Appointment` carries JPA mapping annotations, a
deliberate trade-off) and `infrastructure` (adapters). A module uses another's `application` and `domain` types
directly: `scheduling` builds on `catalogue`'s `BranchCalendar`, and `booking` runs `scheduling`'s `SlotCalendar`.
`booking` never imports `notification`: it defines the `ConfirmationOutbox` port, and `notification` implements it
as `NotificationOutbox`.

Four ArchUnit rules in
[`ArchitectureTest`](../api/src/test/java/za/co/fynbos/branchbook/ArchitectureTest.java) run on every build:

- `modulesAreFreeOfCycles` requires the top-level packages to be free of cycles. The allowed dependencies form a
  chain, so an import against the arrows closes a cycle and fails the build; `booking` importing `notification`
  would.
- `domainHasNoSpringImports` keeps every `domain` package free of Spring.
- `controllersNeverReachInfrastructure` forbids any `api` package from using an `infrastructure` package.
- `onlyApplicationOpensTransactions` lets only `application` code declare a transaction.

## 4. The booking transaction

*Status: Built in M3. The tables, the two exclusion constraints and the indexes it relies on are from M1
([BR-8](domain-rules.md#br-8), proven). The concurrent proofs, twenty requests for one seat and two requests with
one key, are from M5 ([BR-7](domain-rules.md#br-7), [BR-12](domain-rules.md#br-12),
[BR-22](domain-rules.md#br-22), proven).*

[`BookAppointmentHandler.handle`](../api/src/main/java/za/co/fynbos/branchbook/booking/application/BookAppointmentHandler.java)
runs ten steps in one `READ COMMITTED` transaction. The order is the design:

```mermaid
sequenceDiagram
    participant SPA
    participant API as API controller
    participant H as Handler + domain
    participant DB as PostgreSQL
    SPA->>API: POST /api/v1/appointments (Idempotency-Key)
    API->>H: BookAppointmentCommand
    H->>DB: 1. INSERT idempotency_keys ... ON CONFLICT DO UPDATE only if expired
    H->>DB: 2. load branch and branch_services
    H->>H: 3. SlotCalendar.validate(startsAt)
    H->>DB: 4. pg_advisory_xact_lock(mobile)
    H->>DB: 5. duplicate check (ends_at > now), then overlap check
    H->>DB: 6. SELECT branch_services FOR UPDATE
    H->>DB: 7. lowest free seat (generate_series)
    H->>DB: 8. INSERT appointment, then flush
    H->>DB: 9. INSERT notification_messages (SMS, PENDING)
    H->>DB: 10. UPDATE idempotency_keys (appointment_id, 201), COMMIT
    API-->>SPA: 201 Created + reference
```

- **The database decides; the handler makes the outcome deterministic.** Two `EXCLUDE USING gist` constraints
  make an overlapping seat, or an overlapping booking by one mobile, impossible even for code that bypasses the
  application ([ADR-0002](adr/0002-double-booking-prevention.md)).
- **Fixed lock order.** The locks are taken as idempotency key, then the customer's mobile, then the
  `branch_services` row. Every booking takes them in that order, so two bookings cannot deadlock each other.
- **Retries are safe.** The idempotency row is the first statement, so a double-click or a network retry with the
  same key returns the same booking for 24 hours, whether or not the hourly purge has removed the key yet
  ([BR-12](domain-rules.md#br-12)), whether the retries arrive one after another or together.
- **Failures roll back together.** If any step fails, everything rolls back, including the idempotency row and the
  SMS row, so a retry starts clean.
- **One re-run, then an answer.** A reference collision, a deadlock (`40P01`) or a serialisation failure (`40001`)
  runs the whole transaction once more with a new reference. A second conflict is `503 TRY_AGAIN` with
  `Retry-After: 2`; a second collision means the random source is broken, so it is a `500`. Every session gives up
  on a lock after 2 seconds and on a statement after 5, which is also a `503`.
- **Constraints are recognised by name.** `ConstraintViolationTranslator` reads the SQLSTATE and the constraint's
  name from the driver, never the message, and turns the backstops into the same answers as the checks: a
  seat overlap into `409 SLOT_FULL`, a customer overlap into `409 CUSTOMER_OVERLAP`, a taken reference into a
  re-run. The driver is configured to leave
  the conflicting values out of its messages, and Hibernate's own SQL error log is off.
- **Alternatives come after the rollback.** `SLOT_FULL` offers up to three later free slots that day, searched once
  the transaction has rolled back, so the search holds no lock and sees every booking that committed.

### Lookup

*Status: Built in M3 ([BR-13](domain-rules.md#br-13), proven).*

`GET /api/v1/appointments/{reference}` with `X-Mobile-Last4` opens an appointment again
([BR-13](domain-rules.md#br-13)). An unknown reference, a malformed one and a right one with the wrong digits all
get the same `404`, and the digits are compared in constant time. `LookupAttemptLimiter` counts wrong lookups per
reference in a window that opens with the first wrong lookup and lasts an hour; the fifth blocks the
reference, right digits included, until the window closes. A lookup is admitted and counted as in flight in one
atomic step before the database is read, then released if its digits were right or kept as a failure if not. Lookups that arrive together
therefore cannot all pass a check made before any of them was counted. A customer's own lookups have the right digits,
so they never lead to the hour-long block, though a burst of them can be refused for a second while the others are
checked. Blocking logs one warning that names no reference.

The counts live in memory, in a Caffeine cache bounded to 100,000 references. That is a deliberate trade-off for
one instance, with three consequences:

- **They are per instance.** With several instances each would allow five tries; scaling out would move the
  counts to a shared table or cache. The hosted demo runs one task.
- **A restart forgets them.** At worst an attacker gets five more tries per restart, out of 10,000 combinations.
- **The bound could be flooded.** Evicting one reference's window would take failed lookups of about 100,000
  others, and Caffeine prefers to keep entries that are used often. A per-address limit on lookups at the edge
  would close it; the hosted demo's web application firewall limits only booking `POST`s per address today.

Because the window is per reference, someone who knows a reference can block its lookup for an hour. A reference
is printed only in the customer's own SMS and pages, and production would bind appointments to a verified identity
instead ([ADR-0004](adr/0004-restraint-no-login-no-id-number.md)).

## 5. The simulated confirmation

*Status: Built in M5 ([BR-17](domain-rules.md#br-17), proven). Built in M3: every booking writes its SMS as a PENDING
row in the booking transaction ([BR-16](domain-rules.md#br-16)). Built in M1: `notification_messages` and its 11
named constraints, proven by `SchemaConstraintIT`.* Only the retry of a FAILED message, and superseding by a
cancellation, are planned (Tier 2).

```mermaid
stateDiagram-v2
    [*] --> PENDING: written with the booking
    PENDING --> SENT: gateway accepts
    PENDING --> PENDING: failed, retry in 2, 4, 8, 16 s
    PENDING --> FAILED: fifth failure, a failed mark, or cancelled (Tier 2)
    FAILED --> PENDING: retry (Tier 2)
```

The dispatcher runs every two seconds on its own single thread, `outbox-1`, kept apart from Spring's default
scheduler so the hourly and daily jobs never share it. It claims up to 20 due rows per tick, each in its own
transaction with `FOR UPDATE SKIP LOCKED`, so a crash re-sends at most one message and two instances never send the
same message. The simulated gateway takes 150 ms and refuses every number ending in `0000`, such as the demo number
082 555 0000, so a failed delivery can be shown: about 30 seconds of backoff later it is FAILED. A FAILED row records why: `DELIVERY_FAILED` after the fifth failed attempt,
`SUPERSEDED_BY_CANCELLATION` when the appointment is cancelled before the message is sent (Tier 2), or
`DISPATCH_ERROR` when recording the outcome of a send fails, so a poisoned row never loops. The schema and the API
contract also list `RECIPIENT_DAILY_CAP`, which no v1 rule produces. `notification_messages`
already enforces the shape of this state machine with its named constraints: three statuses, at most five attempts,
a failure reason exactly when FAILED and a sent time exactly when SENT. An appointment's status never depends on
delivery: the seat is what the customer booked.

## 6. Appointment lifecycle

*Status: Built in M1 as domain code (`AppointmentStatus`, [BR-15](domain-rules.md#br-15), proven). Booking
creates CONFIRMED appointments from M3; cancellation through the API is Tier 2 ([BR-14](domain-rules.md#br-14)).*

```mermaid
stateDiagram-v2
    [*] --> CONFIRMED: booked
    CONFIRMED --> CANCELLED: customer cancels before the start time (API)
    CONFIRMED --> CHECKED_IN: branch check-in (domain only in v1)
    CHECKED_IN --> COMPLETED: consultation finished (domain only)
    CONFIRMED --> NO_SHOW: 15 minutes after the start, no check-in (domain only)
    CANCELLED --> [*]
    COMPLETED --> [*]
    NO_SHOW --> [*]
```

Cancellation is the only transition the API will offer, in Tier 2. The staff transitions are modelled and tested
so the next feature, branch check-in, has a correct foundation
([ADR-0004](adr/0004-restraint-no-login-no-id-number.md)).

## 7. Data model

*Status: Built in M1.*

![The eight tables grouped by owning module: catalogue owns branches, hours, services, branch_services and public_holidays; booking owns appointments and idempotency_keys; notification owns notification_messages. Appointments reference branch_services by a composite key, and two exclusion constraints reject an overlapping seat or an overlapping booking by one mobile.](assets/diagrams/data-model.svg)

Each table belongs to one module. The one read across a module boundary is `booking`'s `SELECT ... FOR UPDATE`
on the `branch_services` row (step 6 of [the booking transaction](#4-the-booking-transaction)); `scheduling`
learns which seats are held through `booking`'s `JdbcBookedIntervals`, so it never queries `appointments` itself.

| Table | Owner module | Named constraints |
|---|---|---:|
| `branches` | `catalogue` | 6 |
| `branch_operating_hours` | `catalogue` | 4 |
| `services` | `catalogue` | 6 |
| `branch_services` | `catalogue` | 4 |
| `public_holidays` | `catalogue` | 2 |
| `appointments` | `booking` | 15 |
| `idempotency_keys` | `booking` | 6 |
| `notification_messages` | `notification` | 11 |
| **Total** | | **54** |

The 54 are 8 primary keys, 3 unique constraints, 35 checks, 6 foreign keys and 2 exclusion constraints. Six
partial or plain indexes serve the hot queries: `ix_appointments_slot_lookup` (availability and the lowest free
seat), `ix_appointments_customer_active` (the duplicate and overlap checks), `ix_idempotency_keys_expires` (the
hourly purge), and `ix_notification_messages_due`, `_appointment` and `_created` (the dispatcher's claim, an
appointment's messages and the simulated inbox).

The schema is created by four Flyway migrations, never edited once committed:

| Migration | Contents |
|---|---|
| [`V1__enable_btree_gist.sql`](../api/src/main/resources/db/migration/V1__enable_btree_gist.sql) | The extension the exclusion constraints need |
| [`V2__schema.sql`](../api/src/main/resources/db/migration/V2__schema.sql) | The eight tables, with every constraint named and the non-obvious ones commented |
| [`V3__reference_data.sql`](../api/src/main/resources/db/migration/V3__reference_data.sql) | Six branches, their hours, six services and the capacities |
| [`V4__public_holidays_2026_2027.sql`](../api/src/main/resources/db/migration/V4__public_holidays_2026_2027.sql) | The public holidays in law for 2026 and 2027 |

Every constraint is named, because the application maps a violation to an API error by its name.
[`SchemaConstraintIT`](../api/src/test/java/za/co/fynbos/branchbook/SchemaConstraintIT.java) proves each one fires,
using raw SQL that bypasses the application, and fails if a constraint is added without a test.

`public_holidays` has no foreign key. `PublicHolidayCalendar` in the catalogue module reads it and adds the rule that a
Sunday holiday is observed on the Monday (Public Holidays Act 36 of 1994, s2(1)); `BranchCalendar` combines it with a
branch's trading hours, and the slot calendar builds on that. Customer details live on the appointment; there is
no customers table and no login.

## 8. Time

*Status: Built in M2 and M3, and in the web app in M4 ([BR-20](domain-rules.md#br-20)).*

Every instant is stored as `timestamptz` (UTC). Trading hours are wall-clock times converted through the branch's
IANA time zone (`Africa/Johannesburg`), never by adding two hours by hand. A `java.time.Clock` is injected
everywhere, and SQL binds `:now` from it rather than calling `now()`, so tests control time;
`NativeSqlUsesBoundNowTest` fails if a SQL string reads the database's clock. The API returns offsets explicitly,
for example `2026-10-27T09:30:00+02:00`, and the web app formats times in South African Standard Time whatever
the device's time zone: its tests run twice, under `TZ=UTC` and `TZ=America/New_York`. The containers and CI run in UTC, and CI runs the API's unit and API tests again in
`America/New_York` to prove the slots do not depend on the JVM's zone.

## 9. Personal information

*Status: Built for every surface that exists ([BR-19](domain-rules.md#br-19)), the booking screens and the
simulated inbox among them.*

BranchBook stores a first name, a last name and a South African mobile number, nothing more. The full number
appears only to the person typing it, on the details and review steps, before the booking exists. Once booked, the
API and the UI show it only masked: a caller who has just proved the last four digits sees `082 *** 0101`, and
everyone else, including the simulated inbox, sees `082 *** ****`.

Logs, metrics and error bodies carry no personal data. Error bodies never quote the request, and unexpected
exceptions are logged with their types and stack frames but not their messages, which can quote a name or a
number. Log lines name an appointment by its id, never by its reference.
[`LogRedactionIT`](../api/src/test/java/za/co/fynbos/branchbook/LogRedactionIT.java) takes one customer through
every outcome of a booking and a lookup and checks both the application's log and PostgreSQL's for their name,
number and reference. How the data is protected, and what is planned, is in
[SECURITY.md](../SECURITY.md#personal-information-popia).

## 10. Deployment

*Status: Built. The image and the local stack are built, every push to `main` deploys a release record to AWS, and
the hosted demo runs in Cape Town (af-south-1), deployed by the administrator by hand since 2026-10-26.*

### 10.1 Locally: the image and the stack

![From source to a running stack: a three-stage Dockerfile builds the React app and the jar into branchbook:local (user 10001, /readyz health check). make up runs it with PostgreSQL 17 in Docker Compose on 127.0.0.1 and waits until both are healthy. CI's stack job builds and smoke-tests the same image, which the hosted demo runs as one Fargate task.](assets/diagrams/local-runtime.svg)

`make up` runs `docker compose up --build --detach --wait --wait-timeout 300`, and the numbers in the picture
follow its start-up order:

1. Build `branchbook:local` from the three-stage [`Dockerfile`](../Dockerfile): the React app on Node.js 24, the jar
   on the Temurin 25 JDK, then a Temurin 25 JRE runtime that runs as user 10001.
2. The `db` service starts PostgreSQL 17.
3. `pg_isready` succeeds and `db` is healthy; [`compose.yaml`](../compose.yaml) starts `app` only then.
4. The app starts: Flyway applies V1 to V4, and Hibernate validates the schema against the entities.
5. `/readyz` answers UP, and Docker marks the container healthy.
6. `--wait` returns, and `make up` prints the URL.

- **Tests never run in the image.** The image build packages the jar with `-DskipTests`; `make test` and CI run the
  tests.
- **One recipe, two places.** CI's `stack` job builds and starts the image from the same `Dockerfile` and
  `compose.yaml` with the same command, then runs `scripts/smoke.sh` and checks the logs for credentials.
- **Development runs without it.** `make dev-api` starts the API with a throwaway PostgreSQL in Docker, and
  `make dev-web` serves the web app with hot reload on port 5173.

### 10.2 From a commit to AWS

![From a commit to Cape Town: an opt-in pre-commit hook refuses secret files and runs gitleaks, push protection checks the push, and main, which requires no checks, starts ci, secret-scan, infra (on infra changes) and deploy side by side. deploy does not wait for ci. It passes the aws-demo gate, exchanges an OIDC token at STS, deploys BranchbookRelease through the bootstrap roles and verifies the commit; an administrator deploys BranchbookAccount.](assets/diagrams/delivery-pipeline.svg)

Every push to `main` starts `ci`, `secret-scan` and `deploy` side by side, and `infra` joins when `infra/` changes;
`deploy` runs the CDK app's tests, signs in to AWS through OpenID Connect, deploys `BranchbookRelease` and reads
the recorded commit back. `deploy` does not wait for `ci` yet, and `main` requires no passing checks;
[delivery.md](delivery.md) walks through each numbered step, what each workflow proves and what would close the
gaps.

### 10.3 In AWS

![BranchBook in AWS, all built: the administrator's BranchbookAccount in us-east-1 (GitHub OIDC provider, a narrow deploy role, a $90 budget), the pipeline's release record in Cape Town, and the hosted demo there, deployed by hand, where a reviewer's request runs through Route 53, WAF and a load balancer to one Fargate task and RDS PostgreSQL 17.](assets/diagrams/aws-footprint.svg)

The letter and numbers in the picture:

- **A.** An administrator deploys `BranchbookAccount` to us-east-1 by hand with `cdk deploy`. The pipeline never
  deploys this stack.
- **1.** On every push to `main`, the `deploy` job in `deploy.yml` enters the `aws-demo` environment and signs in
  through OpenID Connect and AWS STS as `branchbook-github-deploy`. No access key is stored.
- **2.** That role assumes the CDK bootstrap roles (`cdk-hnb659fds-*`) in af-south-1.
- **3.** CloudFormation updates the `BranchbookRelease` stack.
- **4.** The job reads `/branchbook/release` back and fails unless it equals `GITHUB_SHA`.

Four stacks are built: `BranchbookAccount` in us-east-1 (the GitHub OIDC provider, the deploy role and the
budget), and in Cape Town (`af-south-1`) `BranchbookRelease`, the release record, and the hosted demo's
`BranchbookData` and `BranchbookApp` ([ADR-0006](adr/0006-hosted-demo-on-aws-with-cdk.md)). The pipeline deploys
`BranchbookRelease` alone, with no stored keys ([ADR-0007](adr/0007-continuous-deployment-from-main.md)); the
administrator deploys the other three by hand. There is no DNS stack: the hosted zone came with the domain's Route 53
registration.

A reviewer's request to https://branchbook.click: Route 53 resolves the name to the load balancer; AWS WAF, attached
to the load balancer, blocks `/actuator` and rate-limits bookings, with no sign-in; the load balancer redirects plain
HTTP to HTTPS, ends HTTPS and forwards to one ARM64 Fargate task on port 8080; and the task reaches RDS for
PostgreSQL 17 over TLS, directly. The simulated inbox is off there, so no visitor sees another's booking
([ADR-0006, Notes](adr/0006-hosted-demo-on-aws-with-cdk.md#notes)). The picture draws Route 53, then WAF, then the
load balancer for readability; Route 53 only resolves the name. Resources, the request path step by step,
deploying, teardown, trust and access are in [infra/README.md](../infra/README.md#the-hosted-demo).

Next: [domain-rules.md](domain-rules.md) for the proofs; [delivery.md](delivery.md) for how it ships.
