# BranchBook

Branch appointment booking for a South African bank: customers choose a branch, a service and a time, and receive
an SMS confirmation that cannot be lost: simulated locally and in staging, and a real SMS through AWS in production.

[![ci](https://github.com/KatlehoKomeke/Appointment-Booking-System/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/KatlehoKomeke/Appointment-Booking-System/actions/workflows/ci.yml) [![secret-scan](https://github.com/KatlehoKomeke/Appointment-Booking-System/actions/workflows/secret-scan.yml/badge.svg?branch=main)](https://github.com/KatlehoKomeke/Appointment-Booking-System/actions/workflows/secret-scan.yml)

> **Fynbos Bank is fictional.** This repository is an engineering assessment submitted to Capitec Bank. It is not
> affiliated with Capitec Bank or any other bank. Every branch, person and phone number in it is made up. Run locally
> it sends nothing; the hosted demo texts the number you book with, unless it is a demo number, 082 555 0000 to 0199.

![BranchBook at a glance, all built: a customer's browser reaches one Spring Boot jar that serves the React app and a REST API which books, looks up and cancels over PostgreSQL 17, texting through a simulated gateway or, in production, AWS End User Messaging; code ships through GitHub Actions, which builds the image once and signs in to AWS through OpenID Connect, to Cape Town, where the same image is deployed by digest to staging and then, after approval, to production.](docs/assets/diagrams/system-at-a-glance.svg)

## Status

| Area | Today |
|---|---|
| Stage | Milestones M0 to M7 built: the web app's five-step booking flow, confirmation page, `/manage` and simulated inbox; booking in one transaction that also writes the confirmation SMS; lookup with the reference and the last four digits; a dispatcher that delivers the SMS through a simulated gateway, or through AWS End User Messaging SMS where a deployment asks, with the race proofs; and cancelling and retrying, in the API and the web app, with end-to-end tests in a real browser. M7 is built too: the final documentation pass, a clean-clone rehearsal and the `v1.0.0` release tag, pushed on 2026-10-27. |
| Design | The whole design, including planned work, is on the [architecture page](https://katlehokomeke.github.io/Appointment-Booking-System/); its header names the commit it was last checked against. The repository records what is built and wins where the two disagree. |
| Run locally | `make up`, then open http://localhost:8080 |
| Hosted demo | https://branchbook.click, in AWS Cape Town (af-south-1) since 2026-10-26, open to anyone with no sign-in, and with the simulated inbox off so no visitor sees another's booking. Any South African mobile may book there: a number outside the demo block 082 555 0000 to 0199 is sent a real SMS through AWS End User Messaging SMS, at most five a day to one number (best effort over a shared route, and only to verified numbers while the AWS account is in the SMS sandbox). Each booking is deleted within the hour after its appointment ends, or at once when it is cancelled. It becomes the production environment below when the administrator's steps in [infra/README.md](infra/README.md#hardening-the-account-that-already-runs-production) run ([ADR-0006](docs/adr/0006-hosted-demo-on-aws-with-cdk.md), [infra/README.md](infra/README.md#the-hosted-demo)). |
| Environments | Staging and production, built in code and not yet deployed: one image, built and tested once, promoted by digest from staging to production ([Environments](#environments), [ADR-0008](docs/adr/0008-two-environments-promoted-by-digest.md)). |
| Deployment | Every push to `main` runs `ci`. Once every check passes, `publish` pushes the image the checks tested to ECR and attests it; `deploy-staging` deploys it by digest and runs the smoke and end-to-end tests there; then, once a person approves, `deploy-production` verifies the image's provenance, deploys the same digest and smoke-tests it, and rolls production back to the last release if that fails. Each environment records its release only after its smoke test. GitHub reaches AWS through OpenID Connect with no stored keys, and a red check means no deployment ([docs/delivery.md](docs/delivery.md)). |
| Production readiness | Every control, with the test, CI job, CDK assertion or cdk-nag rule that proves it: [docs/production-readiness.md](docs/production-readiness.md). What can fail and what contains it: [docs/resilience.md](docs/resilience.md). The threats and the risks that remain: [docs/threat-model.md](docs/threat-model.md), [SECURITY.md](SECURITY.md#known-risks). |

### Milestones

| Milestone | Scope | Status |
|---|---|---|
| M0 | Scaffold, `make up`, CI, continuous deployment | Built; since the pipeline took over the hosted demo, `ci` deploys it too |
| M1 | Schema and named constraints, reference data, public holidays, branch API, ArchUnit rules | Built |
| M2 | `SlotCalendar`, the availability API, the committed OpenAPI contract | Built |
| M3 | Booking transaction with idempotency, the PENDING confirmation SMS in the same transaction, lookup with the last four digits and its rate limit | Built |
| M4 | Booking UI: the five-step flow, the confirmation page, read-only `/manage`, the simulated inbox | Built |
| M5 | The dispatcher and simulated SMS gateway, delivery status, the simulated inbox, the race and idempotency proofs | Built |
| M6 | Tier 2 if time allows, UI states and accessibility, web and end-to-end tests, CI completion | Built: cancel, retry, demo data and two metrics in the API and the web app; Playwright in CI at 390 and 1280 px |
| M7 | Final documentation pass, clean-clone rehearsal, release tag | Built: the documentation pass, a rehearsal from a fresh clone, and `v1.0.0` tagged on 2026-10-27 |
| AWS | Hosted demo: the deploy role, budget and release record, and the data and app stacks, first deployed by hand on 2026-10-26 and by the pipeline on every push to `main` since it took over | Built |
| Hardening | Staging and production from validated configuration, the image promoted by digest behind an approval with automatic rollback, private subnets, IAM database authentication with a least-privilege role, a WAF CAPTCHA, the SMS circuit breaker, account guardrails, alarms, a canary and fault-injection experiments ([docs/production-readiness.md](docs/production-readiness.md)) | Built in code; not yet deployed |

### Environments

*Built in code; not yet deployed. The live hosted demo is the single environment that preceded them.* Both run in
Cape Town (af-south-1) from the same image, with the `prod` profile. Every value is in
[infra/config/](infra/config) and every application setting in [docs/configuration.md](docs/configuration.md); the
full comparison is in [infra/README.md](infra/README.md#environments-staging-and-production).

| | Staging | Production |
|---|---|---|
| Address | https://stg.branchbook.click | https://branchbook.click |
| Deployed | On every push to `main` that passes every check | The same image, by digest, once staging passes and a person approves |
| Tasks, database | 1 to 2 tasks; Single-AZ | 2 to 4 tasks over two zones; Multi-AZ, deletion protection |
| SMS | Simulated; the published demo numbers only | Real, through AWS End User Messaging, South Africa only, five a day to a number; the demo numbers stay simulated |
| CAPTCHA | On every booking and cancellation; the pipeline's synthetic traffic may skip it | On every booking and cancellation, with no way past it |

## The full design

<a href="https://katlehokomeke.github.io/Appointment-Booking-System/"><picture><source media="(prefers-color-scheme: dark)" srcset="docs/assets/architecture-page-dark.png"><img src="docs/assets/architecture-page-light.png" width="100%" alt="The BranchBook architecture page: the title, a one-paragraph summary of the design and the brief it answers"></picture></a>

**[BranchBook architecture](https://katlehokomeke.github.io/Appointment-Booking-System/)** holds the whole design, including planned work: each section is marked built, partly built or planned, with every departure from the plan.

## Reviewing this repository

**Ten minutes:** [docs/tour.md](docs/tour.md): what it is, how to run it, the six decisions that shape it and what each
costs, and where to look in the code. **Then:** [docs/architecture.md](docs/architecture.md) walks one request through
the code and the schema, [docs/delivery.md](docs/delivery.md) follows a commit to AWS, and
[infra/README.md](infra/README.md) lists what exists in AWS and who can change it.

Every document, with its status: [docs/README.md](docs/README.md). Decisions: [docs/adr](docs/adr/README.md). Policies: [SECURITY.md](SECURITY.md) and [CONTRIBUTING.md](CONTRIBUTING.md). Production readiness, control by control: [docs/production-readiness.md](docs/production-readiness.md), with [docs/resilience.md](docs/resilience.md) and [docs/threat-model.md](docs/threat-model.md). The UI design: [web/README.md](web/README.md#ui-design). To run it: [Getting started](#getting-started).

## The brief

> "Create a system (front end and back end) for customers to schedule branch appointments and communicates a
> simulated confirmation of the appointment."

## How the brief maps to the system

| Brief | What BranchBook does, and the tests that prove it |
|---|---|
| for customers | No login. A customer opens an appointment with its reference and the last four digits of the mobile number used to book: the read-only `/manage` page, over `GET /api/v1/appointments/{reference}`.<br>**Proof:** [`AppointmentApiIT.getWithWrongLast4Returns404NotForbidden`](api/src/test/java/za/co/fynbos/branchbook/booking/api/AppointmentApiIT.java) (proven), [`AppointmentApiIT.sixthWrongLast4WithinHourReturns429`](api/src/test/java/za/co/fynbos/branchbook/booking/api/AppointmentApiIT.java) (proven) |
| to schedule | The web app's five-step flow (branch, service, date and time, your details, review) books through `POST /api/v1/appointments`. Slots respect trading hours, public holidays, a two-hour lead time, a 30-day horizon and each service's capacity.<br>**Proof:** [`ConcurrentBookingIT.twentyRacersForOneSeatProduceExactlyOne201`](api/src/test/java/za/co/fynbos/branchbook/booking/application/ConcurrentBookingIT.java) (proven), [`SlotCalendarTest.excludesSlotsStartingWithinTwoHoursOfNow`](api/src/test/java/za/co/fynbos/branchbook/scheduling/domain/SlotCalendarTest.java) (proven), [`AppointmentApiIT.postWhenSlotFullReturns409WithThreeAlternatives`](api/src/test/java/za/co/fynbos/branchbook/booking/api/AppointmentApiIT.java) (proven) |
| branch appointments | Six fictional branches in six provinces, each with its own trading hours and per-service capacity, seeded by Flyway ([Reference data](#reference-data)).<br>**Proof:** [`SlotCalendarTest.saturdayFortyFiveMinuteLastStartIs1145`](api/src/test/java/za/co/fynbos/branchbook/scheduling/domain/SlotCalendarTest.java) (proven), [`PublicHolidayCalendarTest.sundayHoliday9Aug2026IsObservedOnMonday10Aug`](api/src/test/java/za/co/fynbos/branchbook/catalogue/domain/PublicHolidayCalendarTest.java) (proven) |
| communicates a simulated confirmation | The booking transaction writes the SMS to an outbox table, so it commits with the booking or not at all. A dispatcher delivers it through a simulated gateway, retrying after about 2, 4, 8 and 16 seconds a failure that sending again may cure, or failing at once one it cannot, and the confirmation page shows its status live (M4). Where `BRANCHBOOK_SMS_GATEWAY=aws`, as in production, every number outside the published demo block is sent a real SMS through AWS End User Messaging SMS in af-south-1; the block stays simulated.<br>**Proof:** [`ConfirmationOutboxIT.bookingAndPendingMessagesCommitAtomically`](api/src/test/java/za/co/fynbos/branchbook/notification/application/ConfirmationOutboxIT.java) (proven), [`NotificationDispatcherIT.retriesWithBackoffThenMarksFailedAfterFiveAttempts`](api/src/test/java/za/co/fynbos/branchbook/notification/application/NotificationDispatcherIT.java) (proven), [`BookAppointmentIT.failureAfterTheSmsIsWrittenRollsTheSmsBackWithTheBooking`](api/src/test/java/za/co/fynbos/branchbook/booking/application/BookAppointmentIT.java) (proven), [`RoutingSmsGatewayTest.theDemoBlockIsAlwaysSimulated`](api/src/test/java/za/co/fynbos/branchbook/notification/infrastructure/RoutingSmsGatewayTest.java) (proven) |
| front end and back end | A React 19 app served by one Spring Boot jar, over PostgreSQL 17; `make up` starts both.<br>**Proof:** `book-appointment.spec.ts` (proven), which CI's `stack` job runs in a real browser against the image and PostgreSQL, between two runs of [smoke.sh](scripts/smoke.sh) |

## What makes it correct

- **Double booking is impossible.** *Built.* Exclusion constraints reject an overlapping seat from any code path
  (BR-8), and booking takes the lowest free seat under a row lock in a fixed lock order (BR-7, BR-22): twenty racers
  for one seat give exactly one booking, and `make demo-409` shows it ([ADR-0002](docs/adr/0002-double-booking-prevention.md)).
- **Retries are safe.** *Built (BR-12).* An `Idempotency-Key` replays the same booking for 24 hours, even for two
  retries that arrive together, and the same key is refused with other details or after its booking is cancelled.
- **The confirmation cannot be lost.** *Built.* The SMS commits with the booking or not at all (BR-16), and a
  dispatcher delivers it with retries, one transaction per message; a circuit breaker stops a provider's outage from
  using up a message's attempts (BR-17, [ADR-0003](docs/adr/0003-transactional-outbox-for-confirmations.md),
  [docs/resilience.md](docs/resilience.md)).
- **A reference cannot be guessed or fished for.** *Built (BR-11, BR-13).* Eight random characters without
  look-alikes, plus the mobile number's last four digits; every mismatch is the same 404, and five wrong tries block
  the reference until an hour after the first.
- **It is built for South Africa.** *Built.* Public holidays with the Sunday-to-Monday rule (BR-3), normalised mobile
  numbers (BR-19), and times stored in UTC, sent with +02:00 and shown in SAST on any device (BR-20).
- **It collects as little as possible.** *Built.* A name and a mobile number, kept out of the booking's URL,
  and a test checks that neither reaches the application's log or PostgreSQL's (BR-19, [SECURITY.md](SECURITY.md)).
  Every rule, with its status and the test that proves it, is in [docs/domain-rules.md](docs/domain-rules.md).

## How it is built

One Spring Boot jar serves the React app and the REST API, over PostgreSQL 17. It is packaged by capability
(`catalogue`, `scheduling`, `booking` and `notification`), with up to four layers each (`api`,
`application`, `domain`, `infrastructure`). ArchUnit fails the build on a cycle between modules, a controller that
reaches a database adapter, or a transaction opened outside the application layer ([docs/architecture.md](docs/architecture.md)).

## How it is tested and delivered

![From a commit to Cape Town, all built: an opt-in pre-commit hook runs gitleaks (1), main's ruleset requires every check (2), ci builds the ARM64 image once and tests that build (3), publish pushes it to ECR and attests it (4), deploy-staging deploys its digest to staging and tests it (5), and, after a person's approval, deploy-production verifies its provenance and deploys the same digest to production (6), with rollback-production if it fails; every delivery job signs in through OpenID Connect, and the administrator deploys BranchbookAccount and BranchbookGuardrails by hand (A).](docs/assets/diagrams/delivery-pipeline.svg)

- **Before a commit:** an opt-in pre-commit hook (`make hooks`) refuses secret-bearing files and runs gitleaks.
- **On every push to `main` and every pull request:** `ci` runs `api`, `web`, `infra` (the CDK app's tests, cdk-nag
  and a synth of both environments), `workflow-lint` (actionlint and zizmor) and `image`, which builds the ARM64
  image once, scans it with Trivy and writes its SBOM; then `stack` runs that image under Docker Compose,
  smoke-tested, with Playwright in a real browser, a short k6 load test and an OWASP ZAP scan. `codeql`,
  `dependency-review` and `secret-scan` run beside it. `main` requires every one of these checks before a push or
  merge.
- **Deploying:** on a push to `main`, once every check passes, the same image is pushed to ECR and attested, deployed
  by digest to staging and tested there, then, after a person approves, verified and deployed to production, which
  rolls back by itself if its smoke test fails. Each step: [docs/delivery.md](docs/delivery.md#at-a-glance).

## Technology

| Layer | Choice |
|---|---|
| Back end | Java 25 LTS, Spring Boot 4.1, Spring Data JPA, Flyway, Resilience4j |
| Database | PostgreSQL 17 with `btree_gist` |
| Front end | React 19, TypeScript (strict), Vite; React Router, TanStack Query, react-hook-form with zod, CSS Modules |
| API contract | OpenAPI 3.1, generated from the controllers by springdoc and committed; the web app's types are generated from it ([ADR-0005](docs/adr/0005-committed-openapi-contract.md)) |
| Tests | JUnit, Testcontainers on real PostgreSQL, ArchUnit, Vitest, Playwright with axe at 390 and 1280 px ([e2e/](e2e/README.md)), and k6 for load ([load/](load/README.md)) |
| Delivery | Docker Compose locally, GitHub Actions, AWS CDK (staging and production, the image registry, the deploy roles and the account's guardrails) |

### Versions

Current at M7, with the production hardening's additions. `api/pom.xml`, the lockfiles, `Dockerfile` and `compose.yaml` are authoritative; Dependabot proposes updates weekly.

<details><summary>Every pinned version</summary>

| Component | Version | Pinned in |
|---|---|---|
| Java | 25 LTS; Eclipse Temurin 25.0.4.1 in the image | `api/pom.xml`, `Dockerfile` |
| Spring Boot | 4.1.1, with Spring Framework 7.0.9 and Spring Security 7.1.1 | `api/pom.xml` |
| Hibernate ORM, Flyway | 7.4.5, 12.4.0 | managed by Spring Boot |
| libphonenumber, AWS SDK for Java v2 | 9.0.41, to tell a mobile number from a landline; 2.55.15, the `software.amazon.awssdk` BOM, for the real SMS | `api/pom.xml` |
| AWS Advanced JDBC Wrapper | 4.5.0, for IAM database authentication | `api/pom.xml` |
| Resilience4j | 2.4.0, the SMS circuit breaker and its meters | `api/pom.xml` |
| Tomcat, Jackson | 11.0.25; 3.1.7 and 2.21.7: security fixes pinned ahead of the versions Spring Boot 4.1.1 manages, until it manages these or later | `api/pom.xml` |
| PostgreSQL | 17.11; JDBC driver 42.7.13 | `compose.yaml`, `TestcontainersConfiguration` |
| JUnit, Testcontainers | 6.0.3, 2.0.5 | managed by Spring Boot |
| ArchUnit | 1.5.1 | `api/pom.xml` |
| springdoc-openapi | 3.2.0 | `api/pom.xml` |
| Maven | 3.10.0, checksum-verified by the wrapper | `api/.mvn/wrapper/maven-wrapper.properties` |
| Node.js | 24; 24.22.1 in the build image | `.nvmrc`, `Dockerfile` |
| React, React Router | 19.3.1, 8.4.0 | `web/package-lock.json` |
| TanStack Query, react-hook-form, zod | 5.104.2, 7.90.0, 4.6.6 | `web/package-lock.json` |
| TypeScript | 6.0.3 | `web/` and `infra/` lockfiles |
| Vite, Vitest; Playwright, axe | 8.3.5, 5.0.5; 1.64.0, 4.13.0 | `web/` and `e2e/` lockfiles |
| openapi-typescript, openapi-fetch | 7.13.0, 0.17.0 | `web/package-lock.json` |
| ESLint | 10.13.0 | `web/` and `infra/` lockfiles |
| AWS CDK | `aws-cdk-lib` 2.274.0, CLI 2.1146.1 | `infra/package.json` |

</details>

## Scope

The plan is about 35 focused hours, split into tiers so that a short week still ships something complete.

| Tier | Contents |
|---|---|
| Tier 1: never cut | Booking with every proof, lookup and the confirmation page, the read-only `/manage` lookup, the SMS outbox and dispatcher, the simulated inbox, the lookup rate limit, race tests, smoke scripts, the main end-to-end test |
| Tier 2: if time allows | Cancellation, retrying a failed message from the inbox, demo data, custom metrics. Built in M6, in the API and the web app |
| Tier 3: cut by default | The email channel and a README animation. Built after all: a per-recipient SMS cap (five a day in production, off by default) and more end-to-end tests |

Deliberately out of scope, each with its reason in the ADRs: customer login, staff screens, rescheduling, reminders,
an email provider, a message broker, microservices and Kubernetes manifests. A real SMS provider is now in (ADR-0003).

## Getting started

### Run it

Docker is the only prerequisite.

```bash
git clone https://github.com/KatlehoKomeke/Appointment-Booking-System.git
cd Appointment-Booking-System
make up       # build the image, start the app and PostgreSQL, wait until both are healthy
make smoke    # check the running app over HTTP
make down     # stop everything and delete the local database
```

`make up` prints the address: http://127.0.0.1:8080 unless you change it. If port 8080 is taken, choose another
with `APP_PORT=18080 make up`, or in a `.env` file copied from [.env.example](.env.example). No database password is
committed: the first `make up` writes a random one to the git-ignored `.env`, and a copy started before that needs
`make down` once. Without make, set `DB_PASSWORD` and run `docker compose up --build --wait`.

### Try the API

Swagger UI at http://localhost:8080/swagger-ui.html lists and calls every operation. From a terminal: take the last
bookable date from the server, so the commands never go out of date; list home loan slots at Rustenburg; book 09:30 at
Sandton, which also trades on Sundays, with a new `Idempotency-Key` and a published demo number; and open the booking:

```bash
date=$(curl -s http://localhost:8080/api/v1/branches/SAN | grep -o '"bookableTo":"[0-9-]*"' | cut -d'"' -f4)
curl "http://localhost:8080/api/v1/branches/RUS/availability?service=HOME&date=$date"
curl -X POST http://localhost:8080/api/v1/appointments \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"branchCode": "SAN", "serviceCode": "FICA", "startsAt": "'"$date"'T09:30:00+02:00",
       "customer": {"firstName": "Nomvula", "lastName": "Dlamini", "mobile": "082 555 0101"}}'
curl http://localhost:8080/api/v1/appointments/APT-VY4A-WNB7 -H 'X-Mobile-Last4: 0101'   # your reference
```

Availability lists each slot's `startsAt`, `endsAt` and `seatsAvailable` at the branch's offset, `+02:00`; a date
outside the next 30 days is `422 DATE_OUTSIDE_BOOKING_WINDOW`. On a public holiday the branch is closed (Rustenburg
also on Sundays), so pick a date that `closedDates` does not list. An earlier run, booking 27 October 2026, returned
`201 Created`, with `Location: /api/v1/appointments/APT-VY4A-WNB7` and, shortened:

```json
{ "reference": "APT-VY4A-WNB7", "status": "CONFIRMED",
  "startsAt": "2026-10-27T09:30:00+02:00", "endsAt": "2026-10-27T09:45:00+02:00",
  "customer": { "firstName": "Nomvula", "lastName": "Dlamini", "mobileMasked": "082 *** 0101" },
  "notifications": [{ "channel": "SMS", "kind": "CONFIRMATION", "status": "PENDING",
    "recipientMasked": "082 *** 0101", "body": "Appt confirmed: FICA update, Sandton Summit, …" }] }
```

The SMS, written in the same transaction as the booking, fits one segment: `Appt confirmed: FICA update, Sandton
Summit, Tue 27 Oct 09:30 SAST. Ref APT-VY4A-WNB7. Bring ID + docs. Manage: http://localhost:8080/manage`. The lookup
returns the same body; other digits get the same `404` as an unknown reference. Every endpoint and error:
[api/README.md](api/README.md), [docs/errors.md](docs/errors.md).

### Develop it

Development also needs JDK 25 and Node 24.

```bash
make hooks     # once per clone: secret scan per commit; architecture-page reminder after a major one
make dev-api   # the API on port 8080, with a throwaway PostgreSQL in Docker
make dev-web   # the web app on port 5173 with hot reload, calling the API
make test      # the API, web and docs checks CI runs; then `make up`, `make smoke` and `make e2e` for the stack
make help      # every other task
```

Each part has its own guide: [api/README.md](api/README.md), [web/README.md](web/README.md), [infra/README.md](infra/README.md).

## Repository layout

```text
api/                     the Spring Boot application: the API, and serving the web app
web/, e2e/               the React app, and its end-to-end tests in a real browser
infra/                   the AWS CDK app: staging and production, the image registry, deploy roles, guardrails, budget
load/                    the k6 load test
docs/                    architecture, delivery, production readiness, resilience, threat model, configuration,
                         decision records, domain rules, error codes, the API contract
docs/assets/diagrams/    the SVG diagrams, their key and their rules
scripts/                 smoke.sh, demo-409.sh, check-test-refs.sh, major-commits.sh
.github/                 the workflows ci (checks, then delivery), codeql, dependency-review, secret-scan and
                         aws-connection-check; dependabot.yml
.githooks/ .gitleaks.toml the hooks (make hooks) and the secret-scanning rules they share with CI
Dockerfile, compose.yaml the image (web build, API build, runtime) and the local stack behind make up
Makefile                 one entry point for every task
```

## Assumptions

- **Branch trading hours are representative,** not any real bank's official hours: weekdays 08:00 to 17:00,
  Saturdays 08:00 to 13:00, Sundays closed unless a branch opts in. They are data, so they can change per branch.
- **Each service has a fixed duration** of 15, 30 or 45 minutes, and a capacity per branch: the number of
  consultants who can see customers at the same time.
- **Bookings close two hours before a slot,** and open 30 days ahead.
- **Public holidays are seeded for 2026 and 2027.** See [Reference data](#reference-data).
- **A customer is identified by their mobile number.** There are no accounts in this version.

## Reference data

Flyway seeds the catalogue of the fictional Fynbos Bank
([V3__reference_data.sql](api/src/main/resources/db/migration/V3__reference_data.sql)). The names and street
addresses are made up; no migration contains personal data.

| Branch | Code | Province | Sundays | Why it is there |
|---|---|---|---|---|
| Bellville Kingsway | `BEL` | Western Cape | Closed | |
| Gqeberha Baywater | `GQE` | Eastern Cape | Closed | Offers no home loans |
| Polokwane Savannah | `POL` | Limpopo | Closed | One seat per service, so a race for the last seat is easy to show |
| Rustenburg Bojanala | `RUS` | North West | Closed | Closes at 12:00 on Saturdays |
| Sandton Summit Mall | `SAN` | Gauteng | 09:00 to 13:00 | Three seats for FICA and credit consultations |
| Umhlanga Ridge | `UMH` | KwaZulu-Natal | 09:00 to 13:00 | |

Every branch keeps the hours in [Assumptions](#assumptions), with two seats per service, unless the table says
otherwise. The six services, each with the documents to bring: a FICA update and a card replacement (15 minutes),
opening an account, a credit consultation and deceased-estate help (30 minutes), and a home loan consultation (45).

**Public holidays.** Every branch closes on the public holidays in law for 2026 and 2027: Schedule 1 of the Public
Holidays Act, and 4 November 2026, declared for the local government elections
([V4__public_holidays_2026_2027.sql](api/src/main/resources/db/migration/V4__public_holidays_2026_2027.sql)). A Sunday
holiday also closes the Monday after it (section 2(1)), derived in code: 10 August 2026, 22 March 2027 and 27 December
2027. A Saturday holiday moves nowhere. Coverage ends on 31 December 2027; 2028 is one new migration.

## Security and privacy

No secret is stored here. A pre-commit hook scans each commit, push protection each push, and a workflow the full
history after every push. GitHub Actions reaches AWS through OpenID Connect, so no AWS key exists. In AWS the service
signs in to the database with IAM and holds no database password, and its database role can only read and write rows.
Local data is synthetic; production takes real names and numbers, asks for a CAPTCHA before a booking, texts South
Africa only and deletes each booking once it is over. Details: [SECURITY.md](SECURITY.md),
[docs/production-readiness.md](docs/production-readiness.md) and [docs/threat-model.md](docs/threat-model.md).

## AI assistance

I built BranchBook with an AI coding assistant, Claude (Anthropic), working in Claude Code. It drafted most of the
code, tests and documentation to my direction; I set the scope, made the design decisions, recorded with their
reasoning in [docs/adr](docs/adr/README.md), and reviewed every change before it merged. Every change passes the same
gates, whoever wrote it: tests in two time zones, a real browser under the strict CSP, and the secret scan.

- **Accepted:** registering a short domain, `branchbook.click`, rather than loosening the rule that keeps the
  confirmation SMS to one segment (BR-18) to fit a longer CloudFront address.
- **Rejected:** deleting each hosted booking 48 hours after it was made, whether or not its appointment had passed. A
  booking is now kept until its appointment is over, then deleted within the hour, and a cancelled one is deleted at
  once. Also rejected: a sign-in in front of the hosted demo, which would have stopped every visitor at a login screen.

The gates catch what neither of us saw. The first real-browser pass logged `CSP-VIOLATION script-src eval` on every
page with a form: zod 4 probing for `new Function` under the strict policy. Parsing without compiling fixed it
(3985f40).

## Licence

[MIT](LICENSE)
