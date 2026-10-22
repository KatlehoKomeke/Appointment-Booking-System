# BranchBook

Branch appointment booking for a South African bank: customers choose a branch, a service and a time, and receive
a simulated SMS confirmation that cannot be lost.

> **Fynbos Bank is fictional.** This repository is an engineering assessment submitted to Capitec Bank. It is not
> affiliated with Capitec Bank or any other bank. Every branch, person and phone number in it is made up, and no
> real message is ever sent.

## Status

| | |
|---|---|
| Stage | Design complete. Implementation starts with milestone M0. |
| Run locally | `make up` (arrives in M0) |
| Hosted demo | Optional, in AWS Cape Town, defined in AWS CDK ([ADR-0006](docs/adr/0006-hosted-demo-on-aws-with-cdk.md)) |
| AWS connection | GitHub Actions reaches AWS through OpenID Connect, with no stored keys ([infra/README.md](infra/README.md)) |

This README grows with each milestone. Sections marked *planned* describe what is coming, not what exists yet.

## The brief

> "Create a system (front end and back end) for customers to schedule branch appointments and communicates a
> simulated confirmation of the appointment."

## How the brief maps to the system

| Brief | What BranchBook does | Where | Proven by (*planned*) |
|---|---|---|---|
| for customers | No login. A customer opens an appointment with its reference and the last four digits of the mobile number used to book. | `/manage`, `GET /api/v1/appointments/{reference}` | `AppointmentApiIT.getWithWrongLast4Returns404NotForbidden` |
| to schedule | A four-step booking flow: branch, service, date, slot. Slots respect trading hours, public holidays, a two-hour lead time, a 30-day horizon and each service's capacity. | `/book`, `POST /api/v1/appointments` | `SlotCalendarTest.excludesSlotsStartingWithinTwoHoursOfNow`, `ConcurrentBookingIT.twentyRacersForOneSeatProduceExactlyOne201` |
| branch appointments | Six fictional branches in six provinces, each with its own trading hours and per-service capacity. | Flyway reference data | `SlotCalendarTest.saturdayFortyFiveMinuteLastStartIs1145`, `PublicHolidayCalendarTest.sundayHoliday9Aug2026IsObservedOnMonday10Aug` |
| communicates a simulated confirmation | The booking transaction writes the SMS to an outbox table. A dispatcher delivers it through a simulated gateway with retries, and the confirmation page shows its status live. | `/appointments/:reference` | `ConfirmationOutboxIT.bookingAndPendingMessagesCommitAtomically` |
| front end and back end | A React 19 app served by one Spring Boot jar, over PostgreSQL 17. | `make up` | `book-appointment.spec.ts` |

## What makes it correct

- **Double booking is impossible.** PostgreSQL exclusion constraints reject an overlapping seat, even from code
  that bypasses the application. A fixed lock order makes the race outcome deterministic, and a 20-thread test
  proves exactly one booking wins ([ADR-0002](docs/adr/0002-double-booking-prevention.md)).
- **Retries are safe.** An `Idempotency-Key` is the first thing the booking transaction records, so a double-click
  or a mobile-network retry returns the same booking.
- **The confirmation cannot be lost.** It commits with the booking and is delivered by a background dispatcher
  with retries. A cancellation supersedes a confirmation that has not been sent
  ([ADR-0003](docs/adr/0003-transactional-outbox-for-confirmations.md)).
- **It is built for South Africa.** It knows public holidays, including the rule that a Sunday holiday moves to
  Monday. Times are stored in UTC and shown in South African Standard Time, and mobile numbers are normalised.
- **It collects as little as possible.** A name and a mobile number. Logs and error bodies contain no personal
  data ([SECURITY.md](SECURITY.md)).

Every rule, with the test that proves it, is in [docs/domain-rules.md](docs/domain-rules.md).

## Architecture at a glance

```mermaid
flowchart LR
    customer([Customer]) -- uses --> spa[React app]
    spa -- JSON /api/v1 --> api[Spring Boot API]
    api -- "booking + outbox row,<br/>one transaction" --> db[(PostgreSQL 17)]
    dispatcher[Outbox dispatcher<br/>every 2 s] -- claims due rows --> db
    dispatcher -- send --> sms[Simulated SMS gateway]
```

One deployable and one database, packaged by capability (`catalogue`, `scheduling`, `booking`, `notification`)
with boundaries enforced by architecture tests. The full picture is in [docs/architecture.md](docs/architecture.md).

## Technology

| Layer | Choice |
|---|---|
| Back end | Java 25 LTS (21 as a fallback), Spring Boot 4.1, Spring Data JPA, Flyway |
| Database | PostgreSQL 17 with `btree_gist` |
| Front end | React 19, TypeScript (strict), Vite, TanStack Query, react-hook-form with zod |
| Tests | JUnit, Testcontainers on real PostgreSQL, ArchUnit, Vitest, Playwright |
| Delivery | Docker Compose locally, GitHub Actions, AWS CDK for the optional hosted demo |

Exact versions are recorded in a Versions table on the day the project is scaffolded (*planned*, M0).

## Scope

The plan is about 35 focused hours, split into tiers so that a short week still ships something complete.

| Tier | Contents |
|---|---|
| Tier 1: never cut | Booking with every proof, lookup and the confirmation page, the read-only `/manage` lookup, the SMS outbox and dispatcher, the simulated inbox, the lookup rate limit, race tests, smoke scripts, the main end-to-end test |
| Tier 2: if time allows | Cancellation, retrying a failed message from the inbox, demo data, custom metrics |
| Tier 3: cut by default | The email channel, a per-recipient SMS cap, more end-to-end tests, a README animation |

Deliberately out of scope, each with its reason in the ADRs: customer login, staff screens, rescheduling, reminders,
real SMS or email providers, a message broker, microservices and Kubernetes manifests.

## Getting started

Today the repository holds the plan and its documentation. Code arrives milestone by milestone.

```bash
git clone https://github.com/KatlehoKomeke/Appointment-Booking-System.git
cd Appointment-Booking-System
make hooks        # scan every commit for secrets before it is created
make help         # list the available tasks
```

*Planned (M0):* `make up` builds and starts the application and its database with Docker, and prints the URL.
Docker is the only prerequisite.

## Assumptions

- **Branch trading hours are representative,** not Capitec's official hours: weekdays 08:00 to 17:00, Saturdays
  08:00 to 13:00, Sundays closed unless a branch opts in. They are data, so they can change per branch.
- **Each service has a fixed duration** of 15, 30 or 45 minutes, and a capacity per branch: the number of
  consultants who can see customers at the same time.
- **Bookings close two hours before a slot,** and open 30 days ahead.
- **Public holidays are seeded for 2026 and 2027** from the official Schedule 1 list.
- **A customer is identified by their mobile number.** There are no accounts in this version.

## Documentation

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | How it is built: containers, modules, the booking transaction, the confirmation, the data model |
| [docs/adr](docs/adr/README.md) | Architecture decision records |
| [docs/domain-rules.md](docs/domain-rules.md) | Every business rule, where it is enforced and the test that proves it |
| [docs/errors.md](docs/errors.md) | Every API error code, when it happens and what the customer sees |
| [infra/README.md](infra/README.md) | The AWS CDK app: what exists in AWS, how GitHub connects without keys, first-time setup |
| [SECURITY.md](SECURITY.md) | Secrets, personal information and vulnerability reporting |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Workflow, commit conventions and the documentation standard |

## Security and privacy

No secret is ever stored in this repository. Every commit is scanned for secrets by a pre-commit hook, and every
push by a CI workflow. Hosted environments receive credentials only at runtime from AWS Secrets Manager. All data
is synthetic. The details are in [SECURITY.md](SECURITY.md).

## AI assistance

I used an AI assistant (Claude, by Anthropic) for research, for design review and for drafting documentation. Every
decision is recorded with its reasoning in [docs/adr](docs/adr/README.md). Nothing is committed that I have not
reviewed and cannot explain. The final version of this section will give concrete examples of suggestions I
accepted and rejected.

## Licence

[MIT](LICENSE)
