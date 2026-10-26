# BranchBook API

The Spring Boot application at the heart of BranchBook. It serves the booking API and the React app from
[web/](../web/README.md) on the same origin, and will run the confirmation dispatcher (M5). How it is structured and
why is in [docs/architecture.md](../docs/architecture.md).

**Status (M3):** the schema, the seeded catalogue, the branch API, availability, booking and lookup. The
application starts, migrates PostgreSQL, answers its health probes, serves the app, lists branches with their
bookable dates and each day's slots with the seats left, books an appointment with its confirmation SMS in one
transaction, and opens it again with its reference and the last four digits. It publishes its OpenAPI contract. The
dispatcher that delivers the SMS arrives in M5.

## Prerequisites

- **JDK 25.** The build refuses an older JDK with a clear message. [Eclipse Temurin](https://adoptium.net/) is
  what CI and the Docker image use.
- **Docker,** for the integration tests and for `make dev-api`. They start a real PostgreSQL 17 through
  Testcontainers. Unit tests need no Docker.

Maven needs no installation: `./mvnw` downloads the pinned version and checks it against its published checksum.

## Commands

Run these from `api/`, or use the `make` targets in the repository root:

| Command | What it does |
|---|---|
| `./mvnw test` | Compiles with every lint on, then runs the unit tests. No Docker needed. |
| `./mvnw verify` | Adds the integration tests on PostgreSQL 17, the formatting check and the coverage report (`target/site/jacoco/index.html`). This is what CI runs; it then runs the unit and API tests again under `TZ=America/New_York`, as `make test-api` does. |
| `./mvnw spotless:apply` | Formats every Java file with google-java-format. |
| `./mvnw spring-boot:test-run` | Runs the API on port 8080 against a throwaway PostgreSQL in Docker (`make dev-api`). |
| `make demo-409` (from the root) | After `make up`: races 20 bookings for Polokwane's last FICA seat and exits 1 unless exactly one wins. It books one appointment each run, so it is for a local stack. |
| `make contract` (from the root) | After an intended API change: rewrites [docs/openapi.yaml](../docs/openapi.yaml) from the running API, then the web app's types from it. |

## What the build enforces

| Check | Where | Fails the build when |
|---|---|---|
| Compiler lint | `-Xlint:all -Werror` in [pom.xml](pom.xml) | Any compiler warning appears in production code. |
| Javadoc lint | `-Xdoclint:all/protected` | A public or protected type or member has no Javadoc, or its Javadoc is broken. |
| Package documentation | `PackageDocumentationTest` | A package has no `package-info.java`. |
| Docker-free unit run | `SurefireSuiteIsDockerFreeTest` (ArchUnit) | A `*Test` starts the application or uses Testcontainers, or a test that starts the application is not named `*IT`, so `./mvnw test` would need Docker. |
| Architecture | `ArchitectureTest` (ArchUnit) | A module cycle appears, a domain class imports Spring, a controller reaches the infrastructure layer, or a class outside the application layer declares a transaction. |
| Error catalogue | `ProblemCodeCatalogueTest` | `ProblemCode` and [docs/errors.md](../docs/errors.md) disagree. |
| API contract | `OpenApiContractIT` | The OpenAPI document the API generates differs from [docs/openapi.yaml](../docs/openapi.yaml), or a parameter pattern is not anchored. |
| Log hygiene | `LogRedactionIT`, `LogHygieneIT` | A customer's name, number or reference reaches the application's log or PostgreSQL's, in any form (BR-19). |
| Database clock | `NativeSqlUsesBoundNowTest` | A SQL string in the main code reads the database's clock (`now()`, `CURRENT_TIMESTAMP` and the like) instead of binding the instant from the injected `Clock` (BR-20). |
| Formatting | Spotless with google-java-format 1.36.0 | A file is not formatted. Version 1.37.0 fails inside Spotless 3.10.4, so 1.36.0 is pinned. |
| JDK version | Maven Enforcer | The JDK is older than 25. |

Two build settings are worth knowing. [.mvn/maven.config](.mvn/maven.config) blocks two repositories that Flyway's
parent POM declares, so every dependency comes from Maven Central. The surefire and failsafe plugins load Mockito as
a Java agent at startup, as Mockito recommends on Java 21 and later.

## Tests

| Kind | Naming | Runs in | Needs |
|---|---|---|---|
| Unit | `*Test` | `./mvnw test` (surefire) | Nothing |
| Integration | `*IT` | `./mvnw verify` (failsafe) | Docker |

Integration tests are annotated `@IntegrationTest` (in `support/`). It starts the whole application on
`postgres:17.11-alpine`, the image and logging flags `compose.yaml` uses, and replaces the clock with a
`MutableClock` that tests set, so rules about dates and times are tested at fixed moments. Every integration test
uses the same configuration, so Spring builds the context once and one container serves the whole run.

Tests that book through the API add `@ExtendWith(DatabaseReset.class)`, which empties the booking tables before
and after each test and forgets the lookup counts. `Bookings` builds request bodies and sends bookings and lookups,
and `ScriptedReferenceGenerator`, which replaces the random generator in every integration test, hands out the
references a test scripts first, so a test can force a reference collision. The dispatcher's own loop is off in the
tests: a test that delivers calls `NotificationDispatcher.runOnce` with the instant it wants, so no test sleeps.
`BookingPause` holds a booking at step 10, after it has written its appointment and SMS and before it commits, so a
test can look from outside the transaction or send a second request into it at a moment it chooses.

The schema is proven directly: `SchemaConstraintIT` runs raw SQL that bypasses the application and shows that each
named constraint rejects what it should, and fails if a constraint is added without a case. It also shows that rows
the rules allow together, such as back-to-back appointments on one seat, are accepted. The constraints and their
totals are listed in [docs/architecture.md, section 7](../docs/architecture.md#7-data-model).

## Configuration

Every property is in [application.yml](src/main/resources/application.yml), with the reason for its value. The
environment supplies the rest:

| Variable | Purpose | Set by |
|---|---|---|
| `DB_URL` | JDBC URL of the PostgreSQL database | `compose.yaml` locally; planned for the hosted demo (after M6): the ECS task definition |
| `DB_USER`, `DB_PASSWORD` | Database credentials | `compose.yaml` locally; planned for the hosted demo (after M6): AWS Secrets Manager |
| `PORT` | HTTP port, 8080 by default | Optional |
| `BRANCHBOOK_DISPATCHER_ENABLED` | Runs the dispatcher that delivers PENDING messages every 2 seconds on its own thread. `true` by default; its interval and the simulated gateway's latency are in application.yml. | Optional; the tests switch it off and drive it themselves |
| `BRANCHBOOK_INBOX_ENABLED` | Switches on the simulated inbox, `GET /api/v1/messages`, an operator view of every booking's messages. `false` by default. | `compose.yaml` and `make dev-api` set it to `true`, and so do the tests |
| `PUBLIC_BASE_URL` | The address customers reach the app at, which ends the confirmation SMS's `/manage` link. At most 28 characters with no trailing slash, so the longest SMS fits one segment; a longer value stops start-up. `http://localhost:8080` by default. | `compose.yaml` locally (it follows `APP_PORT`); planned for the hosted demo (after M6): the ECS task definition |

The booking rules that are settings rather than code are under `branchbook.booking` in application.yml: the
two-hour lead time, the thirty-day horizon, and the lookup limit of five wrong tries per reference in an hour.

There are no defaults for the database variables. If one is missing, startup fails (Hikari reports that the `url`
must start with `jdbc`) instead of connecting to a database nobody configured.

## Endpoints today

| Path | Purpose |
|---|---|
| `GET /api/v1/branches` | The active branches, ordered by name |
| `GET /api/v1/branches/{code}` | One branch: address, hours, services, the bookable window and the closed days in it |
| `GET /api/v1/branches/{code}/availability?service=&date=` | One day's slots for one service at one branch, each with the seats left, or why the branch is closed that day |
| `POST /api/v1/appointments` | Book a slot, with an `Idempotency-Key`. `201` with the appointment, its `Location` and its PENDING SMS |
| `GET /api/v1/appointments/{reference}` | Open an appointment again, with `X-Mobile-Last4` |
| `GET /api/v1/messages?limit=` | The simulated inbox: the newest messages of every booking, recipients redacted to `082 *** ****`. Only while `branchbook.inbox.enabled` is true; otherwise a plain 404 |
| `/v3/api-docs`, `/v3/api-docs.yaml` | The OpenAPI 3.1 document, the same as [docs/openapi.yaml](../docs/openapi.yaml) |
| `/swagger-ui.html` | Swagger UI, to read and try the API |
| `/` and the app's routes (`/book`, `/manage`, `/messages`, `/privacy`, `/appointments/{reference}`) | The React app |
| `/readyz` | Readiness: up once the app has started and PostgreSQL answers. Used by the image's Docker health check; the planned load balancer will use it too. |
| `/livez` | Liveness: up while the process is healthy. Deliberately ignores the database. |
| `/actuator/health`, `/actuator/info` | Overall health (no details) and build information |

Every other actuator endpoint is closed. Every response carries the security headers described in
[SecurityConfig](src/main/java/za/co/fynbos/branchbook/shared/security/SecurityConfig.java) and an
`X-Correlation-Id`: the caller's, if it is up to 64 letters, digits and hyphens, otherwise a new one. Swagger UI's
pages alone may use inline styles, which its rendering needs; inline scripts are refused everywhere.

`GET /api/v1/branches/POL`, shortened, on 23 October 2026. The window is computed by the server in the branch's
time zone. Sundays are closed at Polokwane, and 4 November is the election-day public holiday:

```json
{
  "code": "POL",
  "name": "Polokwane Savannah",
  "timezone": "Africa/Johannesburg",
  "bookableFrom": "2026-10-23",
  "bookableTo": "2026-11-21",
  "operatingHours": [{ "weekday": 1, "opensAt": "08:00", "closesAt": "17:00" }, "..."],
  "services": [{ "code": "FICA", "name": "Update personal details (FICA)", "durationMinutes": 15, "...": "..." }],
  "closedDates": [
    { "date": "2026-10-25", "reason": "CLOSED_DAY", "name": null },
    { "date": "2026-11-01", "reason": "CLOSED_DAY", "name": null },
    { "date": "2026-11-04", "reason": "PUBLIC_HOLIDAY", "name": "Local Government Elections" },
    "..."
  ]
}
```

`GET /api/v1/branches/POL/availability?service=FICA&date=2026-11-04`. A closed day is an answer, not an error:

```json
{
  "branchCode": "POL",
  "serviceCode": "FICA",
  "date": "2026-11-04",
  "timezone": "Africa/Johannesburg",
  "generatedAt": "2026-10-25T13:18:55+02:00",
  "closedReason": "PUBLIC_HOLIDAY",
  "holidayName": "Local Government Elections",
  "slots": []
}
```

How slots are made ([docs/domain-rules.md](../docs/domain-rules.md), BR-1 to BR-6):

- They start at opening time and step by the service's duration (15, 30 or 45 minutes), and the last one ends by
  closing time.
- A slot starting less than two hours from now is left out, and only today and the next 29 days can be asked for.
- `seatsAvailable` is the capacity minus the seats that confirmed or checked-in appointments hold during the slot.
  A full slot stays in the list with 0, so the page can show it as taken.
- Wall-clock hours are turned into instants only through the branch's time zone, and every instant is written with
  its offset, so the answer is the same whatever zone the server runs in (BR-20).

The checks run in a fixed order: the branch must exist (404), offer the service (422
`SERVICE_NOT_OFFERED_AT_BRANCH`) and have the date in its window (422 `DATE_OUTSIDE_BOOKING_WINDOW`).

How a booking is decided ([docs/architecture.md](../docs/architecture.md), section 4):

- One `READ COMMITTED` transaction runs ten steps: claim the idempotency key, load the branch and service, check
  the slot, lock the customer's number, refuse a duplicate and then an overlap, lock the service's seats, take the
  lowest free seat, insert the appointment, write the SMS, complete the key. A refusal rolls all of it back.
- The same key with the same details returns the first booking with `Idempotent-Replayed: true`; with other details
  it is `422 IDEMPOTENCY_KEY_REUSED`. The details are compared after parsing, so `0825550101` and `082 555 0101` are
  the same request.
- A reference collision, a deadlock or a serialisation failure runs the transaction once more. A lock not granted
  in 2 seconds is `503 TRY_AGAIN` with `Retry-After: 2`.
- A full slot is `409 SLOT_FULL` with up to three free slots later that day, found after the rollback.

Opening an appointment needs its reference, in any case and with or without hyphens, and the last four digits of
the number. Every mismatch is the same `404`, and five wrong tries block the reference, right digits included, until
an hour after the first (`429` with `Retry-After`).

Every error is an RFC 9457 problem with a stable `code` ([docs/errors.md](../docs/errors.md)). Its `instance` names
the occurrence, never the request path, and an unexpected exception is logged with its type and stack frames but
not its message, which could quote personal data:

```json
{
  "type": "urn:branchbook:problem:BRANCH_NOT_FOUND",
  "title": "Branch not found",
  "status": 404,
  "detail": "We couldn't find that branch.",
  "instance": "urn:branchbook:request:readme-example-1",
  "code": "BRANCH_NOT_FOUND",
  "correlationId": "readme-example-1"
}
```

## The contract

springdoc generates the OpenAPI document from the controllers and their annotations
([OpenApiConfig](src/main/java/za/co/fynbos/branchbook/shared/web/OpenApiConfig.java) adds the shared `Problem`
schema). The document is committed as [docs/openapi.yaml](../docs/openapi.yaml), and the web app's TypeScript types
are generated from that file ([ADR-0005](../docs/adr/0005-committed-openapi-contract.md)). Two checks keep the three
in step:

1. `OpenApiContractIT` fails when the API no longer generates exactly the committed document.
2. CI regenerates `web/src/api/schema.d.ts` from the document and fails if anything changed.

So an API change shows up in review as a diff of the contract, and `make contract` refreshes both files after an
intended one. In the document every response property is required, because the API always sends it; one that can be
empty is nullable instead, so the generated types say exactly what arrives. The `Problem` schema's `code` lists every
`ProblemCode`, so the web app's type for it is the exact set of codes in [docs/errors.md](../docs/errors.md).

## Layout

```text
api/
├── pom.xml
└── src/
    ├── main/java/za/co/fynbos/branchbook/
    │   ├── BranchBookApplication.java   entry point
    │   ├── catalogue/                   branches, services, hours, holidays; the branch API
    │   │   ├── api/                     controller and response records
    │   │   ├── application/             queries, each a read-only transaction
    │   │   ├── domain/                  plain Java: BranchCalendar, PublicHolidayCalendar
    │   │   └── infrastructure/          SQL reads through JdbcClient
    │   ├── scheduling/                  slots and availability
    │   │   ├── api/                     the availability endpoint
    │   │   ├── application/             the availability query; the port to read booked seats
    │   │   └── domain/                  plain Java: SlotCalendar, Slot, BookedInterval
    │   ├── booking/                     appointments: booking and lookup
    │   │   ├── api/                     the appointment endpoints and their request and response records
    │   │   ├── application/             the booking transaction, lookup, the limiter, and the ports it declares
    │   │   ├── domain/                  plain Java: Appointment, MobileNumber, CustomerName, AppointmentReference
    │   │   └── infrastructure/          JPA and SQL adapters, the constraint translator, the key purge
    │   ├── notification/                the confirmation SMS and the simulated inbox
    │   │   ├── api/                     the inbox endpoint, which exists only while switched on
    │   │   ├── application/             the outbox writer, the message reads and the inbox query
    │   │   ├── domain/                  plain Java: NotificationTemplates, Gsm7
    │   │   └── infrastructure/          SQL for notification_messages
    │   └── shared/                      cross-cutting concerns
    │       ├── problem/                 RFC 9457 error responses
    │       ├── correlation/             correlation ids
    │       ├── time/                    the injected Clock and the one format for instants
    │       ├── config/                  typed settings
    │       ├── security/                HTTP security and headers
    │       └── web/                     serving the React app; the OpenAPI description
    ├── main/resources/
    │   ├── application.yml              configuration, one comment per property
    │   └── db/migration/                Flyway migrations V1 to V4, one header comment each
    └── test/java/za/co/fynbos/branchbook/
        └── support/                     @IntegrationTest, MutableClock, DatabaseReset, Bookings, the container
```

**Next:** the module map in [docs/architecture.md, section 3](../docs/architecture.md#3-modules-and-the-dependency-rule),
and one request through the jar in [section 2.1](../docs/architecture.md#21-one-request-through-the-jar).
