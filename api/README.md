# BranchBook API

The Spring Boot application at the heart of BranchBook. It serves the booking API, runs the confirmation
dispatcher, and serves the React app from [web/](../web/README.md) on the same origin. How it is structured and why
is in [docs/architecture.md](../docs/architecture.md).

**Status (M1):** the schema, the seeded catalogue and the branch API. The application starts, migrates PostgreSQL,
answers its health probes, serves the app and lists branches with their bookable dates. Slots and booking arrive in
later milestones.

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
| `./mvnw verify` | Adds the integration tests on PostgreSQL 17, the formatting check and the coverage report (`target/site/jacoco/index.html`). This is what CI runs. |
| `./mvnw spotless:apply` | Formats every Java file with google-java-format. |
| `./mvnw spring-boot:test-run` | Runs the API on port 8080 against a throwaway PostgreSQL in Docker (`make dev-api`). |

## What the build enforces

| Check | Where | Fails the build when |
|---|---|---|
| Compiler lint | `-Xlint:all -Werror` in [pom.xml](pom.xml) | Any compiler warning appears in production code. |
| Javadoc lint | `-Xdoclint:all/protected` | A public or protected type or member has no Javadoc, or its Javadoc is broken. |
| Package documentation | `PackageDocumentationTest` | A package has no `package-info.java`. |
| Architecture | `ArchitectureTest` (ArchUnit) | A module cycle appears, a domain class imports Spring, a controller reaches the infrastructure layer, or a class outside the application layer declares a transaction. |
| Error catalogue | `ProblemCodeCatalogueTest` | `ProblemCode` and [docs/errors.md](../docs/errors.md) disagree. |
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

The schema is proven directly: `SchemaConstraintIT` runs raw SQL that bypasses the application and shows that each of
the 54 named constraints (8 primary keys, 3 unique, 35 checks, 6 foreign keys and 2 exclusion constraints)
rejects what it should, and fails if a constraint is added without a case. It also shows that rows the rules allow
together, such as back-to-back appointments on one seat, are accepted.

## Configuration

Every property is in [application.yml](src/main/resources/application.yml), with the reason for its value. The
environment supplies the rest:

| Variable | Purpose | Set by |
|---|---|---|
| `DB_URL` | JDBC URL of the PostgreSQL database | `compose.yaml` locally; the ECS task definition in AWS |
| `DB_USER`, `DB_PASSWORD` | Database credentials | `compose.yaml` locally; AWS Secrets Manager in AWS |
| `PORT` | HTTP port, 8080 by default | Optional |

The booking rules that are settings rather than code (the two-hour lead time and the thirty-day horizon) are under
`branchbook.booking` in application.yml.

There are no defaults for the database variables. If one is missing, startup fails (Hikari reports that the `url`
must start with `jdbc`) instead of connecting to a database nobody configured.

## Endpoints today

| Path | Purpose |
|---|---|
| `GET /api/v1/branches` | The active branches, ordered by name |
| `GET /api/v1/branches/{code}` | One branch: address, hours, services, the bookable window and the closed days in it |
| `/` and the app's routes (`/book`, `/manage`, `/messages`, `/privacy`, `/appointments/{reference}`) | The React app |
| `/readyz` | Readiness: up once the app has started and PostgreSQL answers. Used by Docker and the load balancer. |
| `/livez` | Liveness: up while the process is healthy. Deliberately ignores the database. |
| `/actuator/health`, `/actuator/info` | Overall health (no details) and build information |

Every other actuator endpoint is closed. Every response carries the security headers described in
[SecurityConfig](src/main/java/za/co/fynbos/branchbook/shared/security/SecurityConfig.java) and an
`X-Correlation-Id`: the caller's, if it is up to 64 letters, digits and hyphens, otherwise a new one.

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
    │   ├── booking/domain/              the Appointment entity and its lifecycle
    │   └── shared/                      cross-cutting concerns
    │       ├── problem/                 RFC 9457 error responses
    │       ├── correlation/             correlation ids
    │       ├── time/                    the injected Clock
    │       ├── config/                  typed settings
    │       ├── security/                HTTP security and headers
    │       └── web/                     serving the React app
    ├── main/resources/
    │   ├── application.yml              configuration, one comment per property
    │   └── db/migration/                Flyway migrations V1 to V4, one header comment each
    └── test/java/za/co/fynbos/branchbook/
        └── support/                     @IntegrationTest, MutableClock, the database container
```
