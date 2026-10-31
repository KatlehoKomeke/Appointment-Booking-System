# BranchBook API

The Spring Boot application at the heart of BranchBook. It serves the booking API and the React app from
[web/](../web/README.md) on the same origin, and runs the dispatcher that delivers its SMS. How it is structured and
why is in [docs/architecture.md](../docs/architecture.md).

**Status (M6, Tier 2 API built):** the application starts, migrates PostgreSQL, answers its health probes, serves
the app, lists branches with their bookable dates and each day's slots with the seats left, books an appointment with
its confirmation SMS in one transaction, opens it again with its reference and the last four digits, and delivers the
SMS through a simulated gateway (M5), or, where a deployment asks, as a real SMS through AWS End User Messaging SMS
to every number outside the published demo block, with a per-recipient daily cap. From M6 it also cancels an appointment before it starts, with its cancellation
SMS; retries a failed message from the simulated inbox; books three demo appointments on a fresh compose stack; and
serves two meters of its own on `/actuator/prometheus`. Where a deployment switches them on, it takes bookings only
from the demo numbers and deletes each booking once its appointment is over. It publishes its OpenAPI contract.

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
| Compiler lint | `-Xlint:all,-processing -Werror` in [pom.xml](pom.xml) | Any compiler warning appears in production code. |
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
`MutableClock` that tests set, so rules about dates and times are tested at fixed moments. Integration tests share
that configuration, so Spring builds the context once and one container serves them. The six that switch a
setting, `InboxSwitchedOffIT`, `InboxReferencesHiddenIT`, `DispatcherLoopIT`, `DemoNumbersOnlyIT`,
`RecipientDailyCapIT` and `RecipientCapLedgerIT`, each build a context of their own, and with it a container of their
own, because the container is a bean of the context.

Tests that book through the API add `@ExtendWith(DatabaseReset.class)`, which empties the booking tables before and
after each test, `lookup_attempts` with them, so the lookup counts start again. The lookup limiter's tests are
integration tests, `LookupAttemptLimiterIT`, on the real database; `LookupAttemptLimiterTest` covers only a settle that
fails, over the test-support `InMemoryLookupAttemptStore`. `Bookings` builds request bodies and sends bookings and
lookups, and `ScriptedReferenceGenerator`, which replaces the random generator in every integration test, hands out the
references a test scripts first, so a test can force a reference collision. The dispatcher's own loop is off in the
tests: a test that delivers calls `NotificationDispatcher.runOnce` with the instant it wants, so no test sleeps.
`BookingPause` holds a booking at step 10, after it has written its appointment and SMS and before it commits, or a
cancel at the statement a test names, such as its INSERT of the cancellation SMS, or a retry at its reset before that
statement locks anything, so a test can look from outside the transaction or send a second request into it at a moment
it chooses. `LockWaits` waits until PostgreSQL shows the sessions a test expects waiting for a lock, so a race is
forced, never hoped for, and no test sleeps.

The schema is proven directly: `SchemaConstraintIT` runs raw SQL that bypasses the application and shows that each
named constraint rejects what it should, and fails if a constraint is added without a case. It also shows that rows
the rules allow together, such as back-to-back appointments on one seat, are accepted. The constraints and their
totals are listed in [docs/architecture.md, section 7](../docs/architecture.md#7-data-model).

## Configuration

[docs/configuration.md](../docs/configuration.md) is the reference: every property, the environment variable that
sets it, its default, whether it is secret, and its value locally, in staging and in production.
`ConfigurationReferenceTest` fails the build if a property in `application*.yml` is missing from it.

- **Every tunable value reads a named environment variable with a safe default**, as `${NAME:default}` in
  [application.yml](src/main/resources/application.yml): the booking rules, the limits and `Retry-After` values, the
  idempotency key's lifetime, the SMS client's timeouts, the published demo-number block, the housekeeping jobs'
  schedules (`branchbook.jobs`), the database pool and session timeouts. Each is checked at start-up by its
  `@ConfigurationProperties` record. Values that a database constraint, the API contract or a message format mirrors
  stay constants; the reference lists them under "Deliberately not configuration".
- **The database URL and user have no defaults.** If `DB_URL` or `DB_USER` is missing, startup fails (Hikari reports
  that the `url` must start with `jdbc`) instead of connecting to a database nobody configured. `DB_PASSWORD` defaults
  to empty, as IAM authentication needs; a database that wants a password then refuses the connection. `make up`
  writes a random `DB_PASSWORD` to the git-ignored `.env`; none is committed.
- **The `prod` profile** ([application-prod.yml](src/main/resources/application-prod.yml),
  `SPRING_PROFILES_ACTIVE=prod`) is what staging and production run: no OpenAPI document or Swagger UI, no demo data,
  the inbox off with references redacted, the actuator on its own port 8081 (`MANAGEMENT_SERVER_PORT`) while `/livez`
  and `/readyz` stay on 8080, JSON logs in Elastic Common Schema, and no migrations at start-up.
  `ProductionProfileGuard` refuses to start under it with the inbox on without redaction, demo data on, a non-`https`
  `PUBLIC_BASE_URL`, the SMS cap on without its key, or the actuator on the main port (`ProductionProfileGuardTest`,
  `ProdProfileIT`); a migrate-only run is held to the first two only.
- **Migrations are a deployment step.** `BRANCHBOOK_MIGRATE_ON_START` (`true` by default, `false` under `prod`) decides
  whether the app migrates when it starts. `BRANCHBOOK_MIGRATE_ONLY=true` runs Flyway, lets Hibernate validate the
  schema, starts no web server, scheduler, dispatcher or demo data, and exits 0, or non-zero on any failure
  (`MigrateOnlyIT`). Migration V9 creates the runtime role `branchbook_app` (`BRANCHBOOK_DB_APP_ROLE`), which may read
  and write rows but not change the schema or touch Flyway's history (`RuntimeRoleIT`).
- **IAM database authentication.** In AWS the app connects as `branchbook_app` through the AWS Advanced JDBC Wrapper:
  `DB_URL=jdbc:aws-wrapper:postgresql://...?wrapperPlugins=iam&sslmode=verify-full&sslrootcert=/app/certs/rds-global-bundle.pem`,
  `SPRING_DATASOURCE_DRIVER_CLASS_NAME=software.amazon.jdbc.Driver` and an empty password. The image carries RDS's CA
  bundle at that path, pinned by checksum. `AwsJdbcWrapperIT` proves the wrapper with Hikari and Flyway on PostgreSQL;
  the `iam` plugin itself needs AWS. A plain `jdbc:postgresql:` URL with a password works unchanged.
- **CAPTCHA.** `BRANCHBOOK_CAPTCHA_SCRIPT_URL` and `BRANCHBOOK_CAPTCHA_API_KEY` publish AWS WAF's CAPTCHA settings at
  `GET /api/v1/client-config` and add `https://*.awswaf.com` to the Content-Security-Policy's `script-src` and
  `connect-src`. Unset, the endpoint answers `{"captcha":null}` and the policy is unchanged (`ClientConfigApiIT`,
  `CaptchaConfiguredIT`, `SecurityHeadersIT`).

## Endpoints today

| Path | Purpose |
|---|---|
| `GET /api/v1/branches` | The active branches, ordered by name |
| `GET /api/v1/branches/{code}` | One branch: address, hours, services, the bookable window and the closed days in it |
| `GET /api/v1/branches/{code}/availability?service=&date=` | One day's slots for one service at one branch, each with the seats left, or why the branch is closed that day |
| `POST /api/v1/appointments` | Book a slot, with an `Idempotency-Key`. `201` with the appointment, its `Location` and its PENDING SMS |
| `GET /api/v1/appointments/{reference}` | Open an appointment again, with `X-Mobile-Last4` |
| `POST /api/v1/appointments/{reference}/cancel` | Cancel an appointment before it starts, with `X-Mobile-Last4`, and delete it at once. `200` with the appointment as the cancel left it: `CANCELLED`, with only its cancellation SMS, PENDING, or FAILED where the daily cap stopped it. From then on the reference is a `404`, counted |
| `GET /api/v1/messages?limit=` | The simulated inbox: the newest messages of every booking, recipients redacted to `082 *** ****`, and references to `APT-****-****` while `branchbook.inbox.redact-references` is true. Only while `branchbook.inbox.enabled` is true; otherwise a plain 404 |
| `GET /api/v1/client-config` | The deployment's client settings: AWS WAF's CAPTCHA script and API key where a CAPTCHA protects bookings and cancels, otherwise `{"captcha":null}` |
| `POST /api/v1/messages/{id}/retry` | Make a FAILED message due again (the web inbox's Retry button calls it); any other message is returned unchanged. The answer is the message as the inbox shows it, its reference redacted the same way. Only while `branchbook.inbox.enabled` is true; otherwise a plain 404 |
| `/v3/api-docs`, `/v3/api-docs.yaml` | The OpenAPI 3.1 document, the same as [docs/openapi.yaml](../docs/openapi.yaml) |
| `/swagger-ui.html` | Swagger UI, to read and try the API |
| `/` and the app's routes (`/book`, `/manage`, `/messages`, `/privacy`, `/appointments/{reference}`) | The React app |
| `/readyz` | Readiness: up once the app has started and PostgreSQL answers. Used by the image's Docker health check and the hosted demo's load balancer. |
| `/livez` | Liveness: up while the process is healthy. Deliberately ignores the database. |
| `/actuator/health`, `/actuator/info` | Overall health (no details) and build information |
| `/actuator/prometheus` | Every meter in Prometheus's text format, the application's two included ([Metrics](#metrics)) |

Every other actuator endpoint is closed: `env`, `beans`, `heapdump`, `metrics` and `loggers` answer 404
(`SecurityHeadersIT`). Every response carries the security headers described in
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

- The number is checked before the transaction, so a refused one writes nothing: anything but a South African
  mobile is `422 MOBILE_NUMBER_REQUIRED`, and while `branchbook.booking.demo-numbers-only` is on, a mobile outside
  the published demo numbers is `422 DEMO_NUMBER_REQUIRED`. Both name `customer.mobile` and never repeat the number.
- One `READ COMMITTED` transaction runs ten steps: claim the idempotency key, load the branch and service, check
  the slot, lock the customer's number, refuse a duplicate and then an overlap, lock the service's seats, take the
  lowest free seat, insert the appointment, write the SMS, complete the key. A refusal rolls all of it back.
- The same key with the same details returns the first booking with `Idempotent-Replayed: true`; with other details
  it is `422 IDEMPOTENCY_KEY_REUSED`. The details are compared after parsing, so `0825550101` and `082 555 0101` are
  the same request. Once that booking is cancelled, the same key is `422 IDEMPOTENCY_KEY_REUSED` too, with a detail
  saying the booking has been cancelled: a retry never books it again.
- A reference collision, a deadlock or a serialisation failure runs the transaction once more. A lock not granted
  in 2 seconds is `503 TRY_AGAIN` with `Retry-After: 2`.
- A full slot is `409 SLOT_FULL` with up to three free slots later that day, found after the rollback.

Opening an appointment needs its reference, in any case and with or without hyphens, and the last four digits of
the number. Every mismatch is the same `404`, and five wrong tries block the reference, right digits included, until
an hour after the first (`429` with `Retry-After`).

How a cancel is decided (BR-13, BR-14, BR-16, BR-24). A cancelled booking is deleted at once; only its
cancellation SMS is kept, until it is sent or has failed for good:

- It needs the same reference and digits as a lookup, through the same check, and its wrong tries count towards the
  same five.
- At or after the start it is `409 TOO_LATE_TO_CANCEL`, and the appointment stays `CONFIRMED`.
- One `READ COMMITTED` transaction loads the appointment through JPA without a lock, then locks its row with
  `SELECT ... WHERE id = ? AND version = ? FOR UPDATE`. It deletes every confirmation of the appointment, whatever
  its status, writes the PENDING cancellation SMS, builds the response, and deletes the appointment with
  `DELETE ... WHERE id = ? AND version = ?`, which frees the seat. The foreign keys keep the cancellation SMS and
  the idempotency key, each with no appointment. All of it commits together.
- A confirmation the dispatcher is sending at that moment is locked, so the cancel waits for that one send, for up
  to 2 seconds; a longer send makes the cancel `503 TRY_AGAIN`, with nothing changed. Once it went out, the cancel
  deletes it all the same: a confirmation is delivered before its cancellation, or never.
- The dispatcher deletes the cancellation SMS once it is sent, or has failed for good, logging only its message id and
  kind.
  The key expires with its 24 hours, as any key does.
- From the commit the reference is unknown. A repeat cancel, or a lookup, is `404 APPOINTMENT_NOT_FOUND` and counts
  as a wrong try, exactly as for a reference never issued. Two cancels at the same moment get one `200` and that
  same counted `404`: the second waits for the first's lock and finds the row gone.
- An appointment the retention purge has deleted once it was over, before the cancel or while it runs, is the same counted
  `404 APPOINTMENT_NOT_FOUND`, never a `500`.

`POST /api/v1/appointments/APT-7K3M-9Q2X/cancel` with `X-Mobile-Last4: 0101`, shortened. The confirmation is not
listed: it has been deleted.

```json
{
  "reference": "APT-7K3M-9Q2X",
  "status": "CANCELLED",
  "canCancel": false,
  "cancelledAt": "2026-10-26T08:10:00+02:00",
  "customer": { "firstName": "Thandi", "lastName": "Mokoena", "mobileMasked": "082 *** 0101" },
  "notifications": [
    {
      "kind": "CANCELLATION",
      "status": "PENDING",
      "recipientMasked": "082 *** 0101",
      "body": "Appt cancelled: FICA update, Sandton Summit, Tue 27 Oct 09:30 SAST. Ref APT-7K3M-9Q2X. Rebook: http://localhost:8080/book",
      "...": "..."
    }
  ]
}
```

The retry of a message locks it with `FOR UPDATE` until it commits, and waits up to 2 seconds for a send of it in
flight, so it never races the dispatcher or a cancel. It resets a FAILED message whose `failureReason` is
`DELIVERY_FAILED` or `DISPATCH_ERROR` to PENDING, with no attempts and due now. Every other message, and the
confirmation of an appointment that is cancelled or has started, comes back unchanged with `retryable` false, by the
same rule that sets `retryable` in the inbox. A cancellation whose booking has been deleted shows `APT-****-****` as
its reference, in the inbox and its retry, and once it is deleted its retry is `404 MESSAGE_NOT_FOUND`. Where the inbox is off, [docs/runbook.md](../docs/runbook.md) holds the
guarded SQL that replays a message instead.

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

## Metrics

`GET /actuator/prometheus` serves every meter in Prometheus's text format (`text/plain;version=0.0.4`). Spring
Boot's own are there: `http_server_requests_seconds` by method, URI template, status and exception type,
`hikaricp_connections_*`, `jvm_*`, `process_*` and `logback_events_total`. The application adds two (FR-30):

| Metric | Type | What it says |
|---|---|---|
| `appointments_booked_total{outcome}` | Counter | Every `POST /api/v1/appointments`, counted once its response is written, by how it ended. `outcome` is its only tag. |
| `outbox_pending` | Gauge | The messages still PENDING after the dispatcher's last tick, those backing off after a refusal included. One `COUNT` query per tick, every 2 seconds; a scrape never touches the database. `NaN` until the first tick, so a dispatcher that is switched off shows no number rather than a healthy 0. |

[BookingOutcome](src/main/java/za/co/fynbos/branchbook/booking/application/BookingOutcome.java) is the one place
that decides the outcome, and it must name every problem code to compile (`BookingOutcomeTest`). The counting happens
in [BookingOutcomeMeter](src/main/java/za/co/fynbos/branchbook/booking/api/BookingOutcomeMeter.java), after the
response is written, so it also sees the 400s Spring gives before the controller runs and the 503s that only the
problem handler names:

| The booking request was answered with | `outcome` |
|---|---|
| `201`, a new appointment | `confirmed` |
| `201` with `Idempotent-Replayed: true` | `replayed` |
| `409 SLOT_FULL` | `slot_full` |
| `409 DUPLICATE_APPOINTMENT` | `duplicate` |
| `409 CUSTOMER_OVERLAP` | `customer_overlap` |
| `400 VALIDATION_FAILED` (a malformed, missing or unknown field, or an unreadable body); `400 IDEMPOTENCY_KEY_REQUIRED`; `404 BRANCH_NOT_FOUND`; `422 SERVICE_NOT_OFFERED_AT_BRANCH`, `DATE_OUTSIDE_BOOKING_WINDOW`, `SLOT_ON_CLOSED_DAY`, `SLOT_OUTSIDE_HOURS`, `SLOT_OFF_GRID`, `SLOT_WITHIN_LEAD_TIME`, `MOBILE_NUMBER_REQUIRED`, `DEMO_NUMBER_REQUIRED` or `IDEMPOTENCY_KEY_REUSED` | `validation` |
| `503 TRY_AGAIN`: a lock not granted in 2 s, no free connection in 3 s, a statement over 5 s, or a second deadlock or serialization conflict | `try_again` |
| `500 INTERNAL_ERROR` after the booking committed, as when the client has gone before its `201` is written | `confirmed` (or `replayed`): the appointment exists and holds its seat |
| `500 INTERNAL_ERROR` that booked nothing | Not counted. It is a defect, not an outcome of booking; it shows as `status="500"` in `http_server_requests_seconds` and in the log with its correlation id. |
| A refusal before the request reaches the endpoint: `405`, `406` or `415` | Not counted |

The booking endpoint has no `429`. Should another endpoint's code, such as `LOOKUP_RATE_LIMITED`, ever answer a
booking, it is left out rather than counted as something it is not. Bookings that do not come through HTTP, such as
the demo data, are not counted. So `confirmed` counts exactly the appointments booked through the API: a client that
never got its `201` and retries with the same key adds one `replayed`. All seven series exist from start-up at 0, so
a rate over one that never moved is 0, not missing (`BookingOutcomeMeterTest`). `MetricsIT` books, replays and
refuses through the API and reads the scrape, and checks that the scrape holds no name, number or reference;
`DemoNumbersOnlyIT`, which runs with only the demo numbers taken, checks that `DEMO_NUMBER_REQUIRED` counts as
`validation`. The SECURITY.md section on hardening says where the endpoint is reachable, and `scripts/smoke.sh`
checks that the running stack serves it.

## Demo data

With `BRANCHBOOK_DEMO_DATA_ENABLED=true` (`branchbook.demo-data.enabled`), which only `compose.yaml` sets, the
application books three appointments once it is ready, if the `appointments` table is empty, so the first screen
and the simulated inbox have something to show (FR-28):

| Branch | Service | Customer | Number |
|---|---|---|---|
| Bellville Kingsway (`BEL`) | FICA update | Ayanda Demo | +27 60 000 0001 |
| Umhlanga Ridge (`UMH`) | Card replacement | Pieter Demo | +27 60 000 0002 |
| Umhlanga Ridge (`UMH`) | FICA update | Priya Demo | +27 60 000 0000 |

[DemoDataLoader](src/main/java/za/co/fynbos/branchbook/booking/infrastructure/DemoDataLoader.java) books each
through `BookAppointmentHandler`, with its own `Idempotency-Key`, at the first free slot of the branch's next open
day after today, as `SlotCalendar` finds it, so the lead time, Sundays and public holidays are respected whatever day
the stack starts. It never books at Polokwane, whose single seats `make demo-409` races for, or at Sandton, which the
smoke test and the README's walkthrough use. The three confirmations carry one correlation id, `sys-<uuid>`; the one
to the number ending 0000 fails all five attempts by design and shows as FAILED in the inbox about 35 seconds after
start-up. The load runs on a virtual thread of its own, `demo-data`, so it never delays readiness, and a fixture that
cannot be booked is logged as a warning naming its branch and service only (`demo_data.no_free_slot`,
`demo_data.fixture_refused` or `demo_data.fixture_failed`); the application
carries on. A restart books nothing more while any appointment exists. While
`branchbook.booking.demo-numbers-only` is on, or `branchbook.sms.gateway` is `aws`, so a fixture could never be sent a real SMS, the loader books nothing and logs one INFO line,
`demo_data.skipped reason=demo_numbers_only` or `reason=real_sms_gateway`, before it reads the database. Its
`+27 60 000` numbers are outside the
demo block, and it books through the booking handler, past the API's demo-number check, so it checks the setting
itself rather than store numbers that deployment promised not to take. `DemoDataLoaderIT` drives the loader
directly, and checks that the loader the application builds hands the load to that thread; `DemoDataPropertiesTest`
reads `compose.yaml` and checks that the app's environment there switches the loader on.

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
    │   ├── booking/                     appointments: booking, lookup and cancellation
    │   │   ├── api/                     the appointment endpoints and their request and response records
    │   │   ├── application/             the booking and cancel transactions, lookup, the limiter, the retention purge, and their ports
    │   │   ├── domain/                  plain Java: Appointment, MobileNumber, CustomerName, AppointmentReference
    │   │   └── infrastructure/          JPA and SQL adapters, the constraint translator, the key and lookup purges, the retention job
    │   ├── notification/                the confirmation and cancellation SMS, and the simulated inbox
    │   │   ├── api/                     the inbox and retry endpoints, which exist only while switched on
    │   │   ├── application/             the outbox writer, the dispatcher, the message reads, the inbox and the retry, the cap's fingerprints
    │   │   ├── domain/                  plain Java: NotificationTemplates, Gsm7
    │   │   └── infrastructure/          SQL for notification_messages and the cap's ledger, the ledger's purge, the SMS gateways
    │   └── shared/                      cross-cutting concerns
    │       ├── problem/                 RFC 9457 error responses
    │       ├── correlation/             correlation ids
    │       ├── time/                    the injected Clock and the one format for instants
    │       ├── config/                  typed settings
    │       ├── security/                HTTP security and headers
    │       └── web/                     serving the React app; the OpenAPI description
    ├── main/resources/
    │   ├── application.yml              configuration, one comment per property
    │   └── db/migration/                Flyway migrations V1 to V8, one header comment each
    └── test/java/za/co/fynbos/branchbook/
        └── support/                     @IntegrationTest, MutableClock, DatabaseReset, Bookings, the container
```

**Next:** the module map in [docs/architecture.md, section 3](../docs/architecture.md#3-modules-and-the-dependency-rule),
and one request through the jar in [section 2.1](../docs/architecture.md#21-one-request-through-the-jar).
