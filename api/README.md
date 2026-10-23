# BranchBook API

The Spring Boot application at the heart of BranchBook. It serves the booking API, runs the confirmation
dispatcher, and serves the React app from [web/](../web/README.md) on the same origin. How it is structured and why
is in [docs/architecture.md](../docs/architecture.md).

**Status (M0):** the skeleton runs end to end. It starts, migrates PostgreSQL, answers its health probes, sends
the security headers and serves the app. The booking modules arrive in later milestones.

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

Integration tests import `TestcontainersConfiguration`, which starts `postgres:17.11-alpine`, the image that
`compose.yaml` runs. Spring caches the application context between test classes, so one container serves the whole
run.

## Configuration

Every property is in [application.yml](src/main/resources/application.yml), with the reason for its value. The
environment supplies the rest:

| Variable | Purpose | Set by |
|---|---|---|
| `DB_URL` | JDBC URL of the PostgreSQL database | `compose.yaml` locally; the ECS task definition in AWS |
| `DB_USER`, `DB_PASSWORD` | Database credentials | `compose.yaml` locally; AWS Secrets Manager in AWS |
| `PORT` | HTTP port, 8080 by default | Optional |

There are no defaults for the database variables. If one is missing, startup fails (Hikari reports that the `url`
must start with `jdbc`) instead of connecting to a database nobody configured.

## Endpoints today

| Path | Purpose |
|---|---|
| `/` and the app's routes (`/book`, `/manage`, `/messages`, `/privacy`, `/appointments/{reference}`) | The React app |
| `/readyz` | Readiness: up once the app has started and PostgreSQL answers. Used by Docker and the load balancer. |
| `/livez` | Liveness: up while the process is healthy. Deliberately ignores the database. |
| `/actuator/health`, `/actuator/info` | Overall health (no details) and build information |

Every other actuator endpoint is closed. Every response carries the security headers described in
[SecurityConfig](src/main/java/za/co/fynbos/branchbook/shared/security/SecurityConfig.java).

## Layout

```text
api/
├── pom.xml
└── src/
    ├── main/java/za/co/fynbos/branchbook/
    │   ├── BranchBookApplication.java   entry point
    │   └── shared/                      cross-cutting concerns
    │       ├── security/                HTTP security and headers
    │       └── web/                     serving the React app
    ├── main/resources/
    │   ├── application.yml              configuration, one comment per property
    │   └── db/migration/                Flyway migrations, one header comment each
    └── test/java/za/co/fynbos/branchbook/
```
