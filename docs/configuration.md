# Configuration reference

Every setting the API reads, where it comes from, and its value in each environment. The files are
[application.yml](../api/src/main/resources/application.yml), shared by every environment, and
[application-prod.yml](../api/src/main/resources/application-prod.yml), the `prod` profile that staging and production
run with. Each property in them carries a comment explaining its value; this page is the operator's view.

Rules:

- **Every tunable value reads a named environment variable with a safe default**, written `${NAME:default}` in the
  file. The name is the one Spring Boot's relaxed binding gives the property (dots and dashes become underscores, in
  capitals), except for a few short names kept from before (`DB_URL`, `DB_USER`, `DB_PASSWORD`, `PORT`,
  `PUBLIC_BASE_URL`) and the deployment switches (`BRANCHBOOK_MIGRATE_ON_START`, `BRANCHBOOK_DB_*`).
- **Design decisions are literal** in the file (for example `ddl-auto: validate`). The variable named below would
  still override one through relaxed binding; none is ever set.
- **`ConfigurationReferenceTest` fails the build** if a property in `application*.yml` is missing from this page or its
  variable is not named here, and if the defaults the unit tests use drift from application.yml's.
- **Secrets never appear in a file.** A secret is marked **yes** below; it comes from the environment, which AWS Secrets
  Manager fills in staging and production, and is never logged.

The environments ([docs/delivery.md](delivery.md)): **local** is `make up` (docker compose) and `make dev-api`;
**staging** is `https://stg.branchbook.click`, where every push to `main` deploys first; **production** is
`https://branchbook.click`, the same image promoted after staging passes. Where a cell says *default*, the environment
does not set the variable. Staging and production values are set by the CDK app from `infra/config/<environment>.json`.

## Process and profile

| Property | Variable | Default | What it controls | Secret | Local | Staging | Production |
|---|---|---|---|---|---|---|---|
| (profile) | `SPRING_PROFILES_ACTIVE` | none | `prod` loads application-prod.yml and arms `ProductionProfileGuard` | no | unset | `prod` | `prod` |
| `branchbook.migrate-only` | `BRANCHBOOK_MIGRATE_ONLY` | `false` | Migrate, validate the schema, exit 0 (non-zero on failure); no web server, scheduler, dispatcher or demo data | no | default | `true` in the migration task only | `true` in the migration task only |
| (web type) | `SPRING_MAIN_WEB_APPLICATION_TYPE` | servlet | `none` starts no web server; the migrate-only mode forces it | no | unset | `none` in the migration task | `none` in the migration task |
| `server.port` | `PORT` | `8080` | The HTTP port: the app, the API and the probes `/livez`, `/readyz` | no | default | default | default |
| `management.server.port` (prod file) | `MANAGEMENT_SERVER_PORT` | unset; `8081` under `prod` | The actuator's own port (health details, info, prometheus), which the load balancer never routes. Unset, it shares the main port | no | unset | `8081` | `8081` |
| `spring.lifecycle.timeout-per-shutdown-phase` | `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE` | `20s` | How long in-flight requests get to finish on shutdown; below the 30 seconds compose and ECS wait | no | default | default | default |
| `logging.structured.format.console` (prod file) | `LOGGING_STRUCTURED_FORMAT_CONSOLE` | plain text; `ecs` under `prod` | One JSON object per log line in Elastic Common Schema, for CloudWatch Logs Insights | no | unset | `ecs` | `ecs` |

## Database

| Property | Variable | Default | What it controls | Secret | Local | Staging | Production |
|---|---|---|---|---|---|---|---|
| `spring.datasource.url` | `DB_URL` | none: start-up fails | The JDBC URL | no | `jdbc:postgresql://db:5432/branchbook` | app: `jdbc:aws-wrapper:postgresql://<rds>:5432/branchbook?wrapperPlugins=iam&sslmode=verify-full&sslrootcert=/app/certs/rds-global-bundle.pem`; migration task: `jdbc:postgresql://<rds>:5432/branchbook?sslmode=verify-full&sslrootcert=/app/certs/rds-global-bundle.pem` | as staging, production's database |
| `spring.datasource.username` | `DB_USER` | none: start-up fails | The database user | no | `branchbook` | app: `branchbook_app`; migration task: the RDS master user | as staging |
| `spring.datasource.password` | `DB_PASSWORD` | empty | The user's password; empty with IAM authentication, and a database that wants one refuses the connection | **yes** | random, in the git-ignored `.env` that `make up` writes | app: empty (IAM authentication); migration task: the RDS master secret | as staging |
| `spring.datasource.driver-class-name` | `SPRING_DATASOURCE_DRIVER_CLASS_NAME` | empty: chosen from the URL | The JDBC driver; a `jdbc:aws-wrapper:` URL needs `software.amazon.jdbc.Driver` | no | default | app: `software.amazon.jdbc.Driver` | as staging |
| `spring.datasource.hikari.connection-timeout` | `SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT` | `3000` (ms) | How long a request waits for a connection before `503 TRY_AGAIN` | no | default | default | default |
| `spring.datasource.hikari.maximum-pool-size` | `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `10` | Connections per instance; instances times this must stay below `max_connections`. The task definitions spell it `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE`, which relaxed binding treats as the same property (`ConfigurationReferenceTest`) | no | default | `5` | `5` |
| `spring.datasource.hikari.data-source-properties.options` | `BRANCHBOOK_DB_LOCK_TIMEOUT`, `BRANCHBOOK_DB_STATEMENT_TIMEOUT` | `2000`, `5000` (ms) | Every session's `lock_timeout` and `statement_timeout` | no | default | default | default |
| `spring.datasource.hikari.data-source-properties.logServerErrorDetail` | `SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_LOGSERVERERRORDETAIL` | `false`, fixed | Leaves the server's DETAIL line, which can quote a mobile number, out of exceptions (BR-19) | no | fixed | fixed | fixed |
| `spring.flyway.enabled` | `BRANCHBOOK_MIGRATE_ON_START` | `true`; `false` under `prod` | Whether the app migrates at start-up | no | default | `false` (profile) | `false` (profile) |
| `spring.flyway.fail-on-missing-locations` | `SPRING_FLYWAY_FAIL_ON_MISSING_LOCATIONS` | `true`, fixed | A jar without migrations fails start-up | no | fixed | fixed | fixed |
| `spring.flyway.placeholders.appRole` | `BRANCHBOOK_DB_APP_ROLE` | `branchbook_app` | The runtime role migration V9 creates and grants row access to | no | default | default | default |
| `spring.jpa.open-in-view` | `SPRING_JPA_OPEN_IN_VIEW` | `false`, fixed | Connections are held only inside a transaction | no | fixed | fixed | fixed |
| `spring.jpa.hibernate.ddl-auto` | `SPRING_JPA_HIBERNATE_DDL_AUTO` | `validate`, fixed | Flyway owns the schema; Hibernate only checks it | no | fixed | fixed | fixed |
| `spring.jpa.properties.hibernate.jdbc.time_zone` | `SPRING_JPA_PROPERTIES_HIBERNATE_JDBC_TIME_ZONE` | `UTC`, fixed | Timestamps are read and written in UTC | no | fixed | fixed | fixed |

## Booking rules

| Property | Variable | Default | What it controls | Secret | Local | Staging | Production |
|---|---|---|---|---|---|---|---|
| `branchbook.booking.lead-time` | `BRANCHBOOK_BOOKING_LEAD_TIME` | `PT2H` | Earliest bookable start after now (BR-4) | no | default | default | default |
| `branchbook.booking.horizon` | `BRANCHBOOK_BOOKING_HORIZON` | `P30D` | Bookable days from today (BR-5) | no | default | default | default |
| `branchbook.booking.lookup-max-failures` | `BRANCHBOOK_BOOKING_LOOKUP_MAX_FAILURES` | `5` | Wrong lookups of a reference before it is blocked (BR-13) | no | default | default | default |
| `branchbook.booking.lookup-window` | `BRANCHBOOK_BOOKING_LOOKUP_WINDOW` | `PT1H` | How long a blocked reference stays blocked | no | default | default | default |
| `branchbook.booking.lookup-in-flight-lease` | `BRANCHBOOK_BOOKING_LOOKUP_IN_FLIGHT_LEASE` | `PT30S` | How long an unsettled lookup counts as in flight | no | default | default | default |
| `branchbook.booking.lookup-in-flight-retry-after` | `BRANCHBOOK_BOOKING_LOOKUP_IN_FLIGHT_RETRY_AFTER` | `PT1S` | `Retry-After` of a lookup refused because others are in flight | no | default | default | default |
| `branchbook.booking.conflict-retry-after` | `BRANCHBOOK_BOOKING_CONFLICT_RETRY_AFTER` | `PT2S` | `Retry-After` of a booking that met two transaction conflicts | no | default | default | default |
| `branchbook.booking.idempotency-key-ttl` | `BRANCHBOOK_BOOKING_IDEMPOTENCY_KEY_TTL` | `PT24H` | How long an `Idempotency-Key` replays its booking (BR-12) | no | default | default | default |
| `branchbook.booking.max-alternatives` | `BRANCHBOOK_BOOKING_MAX_ALTERNATIVES` | `3` | Later slots a `409 SLOT_FULL` offers (BR-7) | no | default | default | default |
| `branchbook.booking.demo-numbers-only` | `BRANCHBOOK_BOOKING_DEMO_NUMBERS_ONLY` | `false` | Only the published demo numbers may book (ADR-0006) | no | default | `true` | `false` (real SMS to real numbers) |
| `branchbook.booking.demo-number-block.first` | `BRANCHBOOK_BOOKING_DEMO_NUMBER_BLOCK_FIRST` | `+27825550000` | First published demo number: bookable under the switch above, always simulated, never capped | no | default | default | default |
| `branchbook.booking.demo-number-block.last` | `BRANCHBOOK_BOOKING_DEMO_NUMBER_BLOCK_LAST` | `+27825550199` | Last published demo number | no | default | default | default |
| `branchbook.notification.public-base-url` | `PUBLIC_BASE_URL` | `http://localhost:8080` | The address in the SMS links; at most 28 characters; `https` required under `prod` | no | `http://localhost:<APP_PORT>` | `https://stg.branchbook.click` | `https://branchbook.click` |

## Notifications

| Property | Variable | Default | What it controls | Secret | Local | Staging | Production |
|---|---|---|---|---|---|---|---|
| `branchbook.dispatcher.enabled` | `BRANCHBOOK_DISPATCHER_ENABLED` | `true` | Whether the outbox dispatcher runs (BR-17) | no | default | default | default |
| `branchbook.dispatcher.interval` | `BRANCHBOOK_DISPATCHER_INTERVAL` | `PT2S` | Time between dispatcher ticks | no | default | default | default |
| `branchbook.dispatcher.simulated-latency` | `BRANCHBOOK_DISPATCHER_SIMULATED_LATENCY` | `PT0.15S` | How long the simulated gateway takes per message | no | default | default | default |
| `branchbook.sms.gateway` | `BRANCHBOOK_SMS_GATEWAY` | `simulated` | `aws` sends real SMS to numbers outside the demo block | no | default | `simulated` | `aws` |
| `branchbook.sms.recipient-daily-cap` | `BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP` | `0` (off) | Most SMS one number is written in 24 hours (BR-17) | no | default | from config | from config |
| `branchbook.sms.recipient-cap-key` | `BRANCHBOOK_SMS_RECIPIENT_CAP_KEY` | empty: random per start | HMAC key of the cap's ledger, at least 32 characters; required under `prod` when the cap is on | **yes** | default | Secrets Manager `branchbook-staging/sms-cap-key` | Secrets Manager `branchbook/sms-cap-key` |
| `branchbook.sms.aws.region` | `BRANCHBOOK_SMS_AWS_REGION` | empty: SDK chain | Region to send from | no | default | `af-south-1` | `af-south-1` |
| `branchbook.sms.aws.protect-configuration-id` | `BRANCHBOOK_SMS_AWS_PROTECT_CONFIGURATION_ID` | empty | Protect configuration (South Africa only) | no | default | from config | from config |
| `branchbook.sms.aws.configuration-set` | `BRANCHBOOK_SMS_AWS_CONFIGURATION_SET` | empty | Configuration set for delivery events | no | default | default | default |
| `branchbook.sms.aws.origination-identity` | `BRANCHBOOK_SMS_AWS_ORIGINATION_IDENTITY` | empty: AWS chooses | Number, pool or sender ID to send from | no | default | default | default |
| `branchbook.sms.aws.max-price` | `BRANCHBOOK_SMS_AWS_MAX_PRICE` | `0.20` | Most one message may cost, in US dollars | no | default | default | default |
| `branchbook.sms.aws.dry-run` | `BRANCHBOOK_SMS_AWS_DRY_RUN` | `false` | AWS checks each message and sends none | no | default | default | default |
| `branchbook.sms.aws.connect-timeout` | `BRANCHBOOK_SMS_AWS_CONNECT_TIMEOUT` | `PT2S` | Connecting to AWS | no | default | default | default |
| `branchbook.sms.aws.read-timeout` | `BRANCHBOOK_SMS_AWS_READ_TIMEOUT` | `PT5S` | Waiting for AWS's answer | no | default | default | default |
| `branchbook.sms.aws.call-timeout` | `BRANCHBOOK_SMS_AWS_CALL_TIMEOUT` | `PT10S` | One whole `SendTextMessage` call; the client never retries | no | default | default | default |
| `branchbook.inbox.enabled` | `BRANCHBOOK_INBOX_ENABLED` | `false` | The simulated inbox, `GET /api/v1/messages` | no | `true` (compose) | `false` | `false` |
| `branchbook.inbox.redact-references` | `BRANCHBOOK_INBOX_REDACT_REFERENCES` | `false`; `true` under `prod` | References shown as `APT-****-****`; the guard refuses the inbox without it | no | default | `true` (profile) | `true` (profile) |

## Data lifecycle and jobs

| Property | Variable | Default | What it controls | Secret | Local | Staging | Production |
|---|---|---|---|---|---|---|---|
| `branchbook.retention.enabled` | `BRANCHBOOK_RETENTION_ENABLED` | `false` | Hourly deletion of bookings that are over (SECURITY.md) | no | default | `true` | `true` |
| `branchbook.retention.after-end` | `BRANCHBOOK_RETENTION_AFTER_END` | `PT0S` | How long after its end a booking is kept | no | default | default | default |
| `branchbook.retention.batch-size` | `BRANCHBOOK_RETENTION_BATCH_SIZE` | `500` | Appointments one purge transaction deletes | no | default | default | default |
| `branchbook.demo-data.enabled` | `BRANCHBOOK_DEMO_DATA_ENABLED` | `false` | Three demo bookings on an empty database (FR-28); the guard refuses it under `prod` | no | `true` (compose) | `false` | `false` |
| `branchbook.jobs.idempotency-key-purge.interval` | `BRANCHBOOK_JOBS_IDEMPOTENCY_KEY_PURGE_INTERVAL` | `PT1H` | Expired idempotency keys purge | no | default | default | default |
| `branchbook.jobs.idempotency-key-purge.initial-delay` | `BRANCHBOOK_JOBS_IDEMPOTENCY_KEY_PURGE_INITIAL_DELAY` | `PT1H` | Its first run after start-up | no | default | default | default |
| `branchbook.jobs.lookup-attempt-purge.interval` | `BRANCHBOOK_JOBS_LOOKUP_ATTEMPT_PURGE_INTERVAL` | `PT1H` | Closed lookup windows purge | no | default | default | default |
| `branchbook.jobs.lookup-attempt-purge.initial-delay` | `BRANCHBOOK_JOBS_LOOKUP_ATTEMPT_PURGE_INITIAL_DELAY` | `PT1H` | Its first run after start-up | no | default | default | default |
| `branchbook.jobs.cap-ledger-purge.interval` | `BRANCHBOOK_JOBS_CAP_LEDGER_PURGE_INTERVAL` | `PT1H` | SMS cap ledger purge | no | default | default | default |
| `branchbook.jobs.cap-ledger-purge.initial-delay` | `BRANCHBOOK_JOBS_CAP_LEDGER_PURGE_INITIAL_DELAY` | `PT1M` | Its first run after start-up | no | default | default | default |
| `branchbook.jobs.retention-purge.interval` | `BRANCHBOOK_JOBS_RETENTION_PURGE_INTERVAL` | `PT1H` | Retention purge, while retention is on | no | default | default | default |
| `branchbook.jobs.retention-purge.initial-delay` | `BRANCHBOOK_JOBS_RETENTION_PURGE_INITIAL_DELAY` | `PT1M` | Its first run after start-up | no | default | default | default |
| `branchbook.jobs.holiday-check.cron` | `BRANCHBOOK_JOBS_HOLIDAY_CHECK_CRON` | `0 0 6 * * *` | Public-holiday coverage warning (BR-3), also run at start-up | no | default | default | default |
| `branchbook.jobs.holiday-check.zone` | `BRANCHBOOK_JOBS_HOLIDAY_CHECK_ZONE` | `Africa/Johannesburg` | The zone the cron is read in | no | default | default | default |

## Edge and web

| Property | Variable | Default | What it controls | Secret | Local | Staging | Production |
|---|---|---|---|---|---|---|---|
| `branchbook.captcha.script-url` | `BRANCHBOOK_CAPTCHA_SCRIPT_URL` | empty: no CAPTCHA | AWS WAF CAPTCHA JavaScript API, published at `GET /api/v1/client-config`; set, the CSP adds `https://*.awswaf.com` to `script-src` and `connect-src`. Must be `https` under `awswaf.com` | no | default | the account's integration URL + `jsapi.js` | as staging |
| `branchbook.captcha.api-key` | `BRANCHBOOK_CAPTCHA_API_KEY` | empty | CAPTCHA API key; public by design (AWS binds it to the token domains). Set together with the script | no | default | the staging domain's key | the production domain's key |
| `springdoc.api-docs.enabled` | `SPRINGDOC_API_DOCS_ENABLED` | `true`; `false` under `prod` | `/v3/api-docs` | no | default | `false` (profile) | `false` (profile) |
| `springdoc.swagger-ui.enabled` | `SPRINGDOC_SWAGGER_UI_ENABLED` | `true`; `false` under `prod` | Swagger UI | no | default | `false` (profile) | `false` (profile) |
| `springdoc.api-docs.version` | `SPRINGDOC_API_DOCS_VERSION` | `openapi_3_1`, fixed | The document's OpenAPI version (ADR-0005) | no | fixed | fixed | fixed |
| `springdoc.paths-to-match` | `SPRINGDOC_PATHS_TO_MATCH` | `/api/**`, fixed | Only the API is the contract | no | fixed | fixed | fixed |
| `springdoc.writer-with-order-by-keys` | `SPRINGDOC_WRITER_WITH_ORDER_BY_KEYS` | `true`, fixed | A stable document that diffs cleanly | no | fixed | fixed | fixed |
| `springdoc.override-with-generic-response` | `SPRINGDOC_OVERRIDE_WITH_GENERIC_RESPONSE` | `false`, fixed | Each operation lists only its own errors | no | fixed | fixed | fixed |

## Fixed by design

These are literal in application.yml because changing one would break a guarantee rather than tune it.

| Property | Variable | Value | Why it is fixed |
|---|---|---|---|
| `spring.application.name` | `SPRING_APPLICATION_NAME` | `branchbook` | Names the application in logs and `/actuator/info` |
| `spring.threads.virtual.enabled` | `SPRING_THREADS_VIRTUAL_ENABLED` | `true` | Requests waiting on PostgreSQL tie up no platform thread |
| `spring.jackson.deserialization.fail-on-unknown-properties` | `SPRING_JACKSON_DESERIALIZATION_FAIL_ON_UNKNOWN_PROPERTIES` | `true` | An undeclared request field is `400 UNKNOWN_FIELD` (docs/errors.md) |
| `server.shutdown` | `SERVER_SHUTDOWN` | `graceful` | In-flight requests finish before exit |
| `logging.pattern.correlation` | `LOGGING_PATTERN_CORRELATION` | `[%X{correlationId:-}] ` | Every request's log lines carry its correlation id |
| `logging.level.org.hibernate.orm.jdbc.error` | `LOGGING_LEVEL_ORG_HIBERNATE_ORM_JDBC_ERROR` | `OFF` | Hibernate's SQL error log can quote values (BR-19, `LogRedactionIT`) |
| `logging.level.software.amazon.awssdk` | `LOGGING_LEVEL_SOFTWARE_AMAZON_AWSSDK` | `WARN` | SDK request logs carry numbers (BR-19) |
| `management.endpoints.web.exposure.include` | `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health, info, prometheus` | Every other actuator endpoint stays closed |
| `management.endpoint.health.probes.enabled` | `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED` | `true` | Liveness and readiness probes exist |
| `management.endpoint.health.probes.add-additional-paths` | `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ADD_ADDITIONAL_PATHS` | `true` | `/livez` and `/readyz` stay on the main port for the load balancer |
| `management.endpoint.health.group.readiness.include` | `MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE` | `readinessState, db` | Ready only when PostgreSQL answers; liveness ignores it |

## Outside the application

| Variable | Read by | What it controls | Local | Staging and production |
|---|---|---|---|---|
| `APP_PORT` | `compose.yaml`, `make up` | Host port the app is published on, bound to 127.0.0.1 | `8080`, or any free port | not used |
| `DB_PASSWORD` (compose) | `compose.yaml`, `make up` | The local PostgreSQL's password. Required: `make up` writes a random one to the git-ignored `.env` when none is set; CI exports its own | random | not used |
| `COMPOSE_PROJECT_NAME` | docker compose | Runs several local stacks side by side | `branchbook` | not used |

`scripts/smoke.sh <base-url>` has its own switches: `EXPECT_DEMO_NUMBERS_ONLY=1`, `EXPECT_PROD_PROFILE=1`,
`EXPECT_CAPTCHA=1` and `SMOKE_SYNTHETIC_TOKEN`; the script's header explains each.

## Deliberately not configuration

These values stay constants in the code, each because something outside the application mirrors it, so changing it
alone would break that agreement:

- **The reference alphabet and pattern** (`AppointmentReference`): the database's `appointments_reference_format`
  check and the API contract's pattern repeat them.
- **The seat maximum** (`Appointment.MAX_SEAT`, 20): the database's capacity check mirrors it.
- **Name limits** (`CustomerName`, 60 code points; 240 characters before parsing): the database column and the
  contract's `maxLength` mirror them.
- **The South African mobile pattern** (`MobileNumber.E164_PATTERN`): the database's `appointments_customer_mobile_e164`
  check is the same text, which `SchemaConstraintIT` compares.
- **GSM-7 sizes and the SMS templates' name lengths** (`Gsm7.SINGLE_SEGMENT`, `NotificationTemplates`): the message
  format; the start-up check on `PUBLIC_BASE_URL` depends on them (BR-18).
- **Input length caps** (`MobileNumber`'s 32 typed characters, the correlation id's 64, the provider id's 64 and the
  error code's 500): the database columns and the contract mirror them.
- **The generic `Retry-After` of `503 TRY_AGAIN`** (`ProblemDetailHandler`, 5 and 2 seconds): [docs/errors.md](errors.md)
  publishes them as part of the error contract.
- **The SMS cap's 24-hour window** (`RecipientSendLedger.WINDOW`): BR-17 defines the cap as a day.
- **The first demo number's simulated failure** (`SimulatedSmsGateway`, numbers ending 0000): the demo's script and
  tests rely on it to show a failed delivery.
