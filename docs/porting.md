# Porting the design

BranchBook is built on Spring Boot and React, but its guarantees do not depend on either. The double-booking rule
lives in PostgreSQL, the API is fixed by a committed contract, and every business rule names the tests that prove
it. This page says how the same design would be built on .NET and on Vue 3: what carries over unchanged, and what is
rewritten. Nothing here is planned; it records how far the seams reach.

## What carries over unchanged

- **The schema.** The eight Flyway migrations, with the two `EXCLUDE USING gist` constraints, the 61 named
  constraints and the indexes ([architecture.md, section 7](architecture.md#7-data-model)). The database rejects an
  overlapping seat from any code path ([ADR-0002](adr/0002-double-booking-prevention.md)), so the guarantee holds
  whichever runtime writes the rows. Flyway also runs as a command-line tool or a container, so the same files can
  migrate the database before a .NET app starts.
- **The contract.** [openapi.yaml](openapi.yaml) fixes every path, header (`Idempotency-Key`, `X-Mobile-Last4`),
  response and error code ([errors.md](errors.md)). A back-end port is finished when the document it generates
  matches the committed file, the check `OpenApiContractIT` makes today. A front-end port touches only `web/`
  ([ADR-0005](adr/0005-committed-openapi-contract.md)).
- **The rules and their tests, as specifications.** Each rule in [domain-rules.md](domain-rules.md) names its
  proving tests, and the names read as requirements, such as
  `ConcurrentBookingIT.twentyRacersForOneSeatProduceExactlyOne201`. A port rewrites each test under the same name,
  the integration tests still against real PostgreSQL, and a rule is ported when its tests pass. `SchemaConstraintIT` drives the schema with raw
  SQL, so its statements carry over as they are.
- **The web app's framework-free code.** The typed client ([client.ts](../web/src/api/client.ts), on openapi-fetch
  and the generated `schema.d.ts`), the customer copy for each error code
  ([problemCopy.ts](../web/src/api/problemCopy.ts)), the SAST formatting, the mobile parser and the safe storage in
  `web/src/lib/`, and the design tokens in [tokens.css](../web/src/ui/tokens.css) import nothing from React.

## The back end on .NET

ASP.NET Core, with EF Core on Npgsql over the same PostgreSQL 17 schema.

| Concern | Today, on Spring Boot | On .NET |
|---|---|---|
| Module boundaries | Packages per capability, held by four ArchUnit rules ([ADR-0001](adr/0001-modular-monolith.md)) | One project per module: the compiler refuses a circular project reference, so the cycle rule comes free. ArchUnitNET or NetArchTest for the layer rules |
| Data access | Spring Data JPA, and SQL through `JdbcClient` | EF Core with Npgsql for the aggregates, raw SQL for the locks and the dispatcher's claim. EF Core does not model exclusion constraints, so the migrations stay hand-written SQL |
| The booking transaction | Ten steps in one `READ COMMITTED` transaction, in a fixed lock order ([architecture.md, section 4](architecture.md#4-the-booking-transaction)) | The same steps in the same order inside `BeginTransactionAsync(IsolationLevel.ReadCommitted)`, with `pg_advisory_xact_lock` and `FOR UPDATE` as raw SQL. The one re-run, after a reference collision, `40P01` or `40001`, is a loop of its own, not EF Core's retrying strategy, because it draws a new reference and a second conflict answers `503 TRY_AGAIN` |
| Constraint errors | Mapped by SQLSTATE and constraint name, never the message | `PostgresException.SqlState` and `ConstraintName`, inside EF Core's `DbUpdateException`. Npgsql leaves the `DETAIL` line, which can quote a mobile number, out of its messages unless `Include Error Detail` is set, and it stays unset (BR-19) |
| Session timeouts | `lock_timeout` 2 s and `statement_timeout` 5 s, set through the driver's `options` | The same `-c` settings in the connection string's `Options` |
| The outbox dispatcher | Its own thread, every 2 s, up to 20 rows, one transaction each, `FOR UPDATE SKIP LOCKED` ([ADR-0003](adr/0003-transactional-outbox-for-confirmations.md)) | A hosted service (`BackgroundService` on a `PeriodicTimer`) running the same SQL |
| Errors | RFC 9457 problem details with `code` and `correlationId` | `AddProblemDetails` and an `IExceptionHandler`, adding the same members |
| Time | An injected `Clock`, bound into SQL as `:now`; `Africa/Johannesburg` for wall-clock times | An injected `TimeProvider`, bound the same way. Npgsql reads `timestamptz` as UTC, and the API still answers with the branch's `+02:00` |
| Probes and the app | `/livez`, `/readyz`, and the built web app served from the jar | `MapHealthChecks` on the same two paths, the database checked only by `/readyz`; `MapFallbackToFile("index.html")` |
| Tests | JUnit and Testcontainers on `postgres:17.11-alpine` | xUnit and Testcontainers for .NET on the same image |

The infrastructure barely moves. The Fargate task runs whatever image the [Dockerfile](../Dockerfile) builds, so a
rewritten Dockerfile whose app listens on port 8080 and answers `/readyz` deploys as it is. The task's environment
variables are the app's settings, and the port reads the same names, except three
([app-stack.ts](../infra/lib/app-stack.ts)): `DB_URL` is a JDBC URL today, `SERVER_FORWARD_HEADERS_STRATEGY` is a
Spring Boot setting, replaced by ASP.NET Core's forwarded-headers middleware, and
`MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` limits Spring Boot's actuator to `health` and `info`.

## The front end on Vue 3

Vue Router, TanStack Query for Vue, and VeeValidate with zod.

| Concern | Today, on React | On Vue 3 |
|---|---|---|
| Routes | React Router: `/book`, `/appointments/:reference`, `/manage`, `/messages`, `/privacy` | Vue Router with the same paths; the server's forwarding of each to `index.html` does not change. The booking steps keep their state in the URL through `useRoute` and `router.replace` |
| Server state | TanStack Query hooks, polling every 2 s while a message is PENDING | `@tanstack/vue-query` composables with the same query keys and `refetchInterval` |
| Forms | react-hook-form with zod | VeeValidate with the same zod schemas, through its zod adapter. Check that the adapter supports the zod major in use, 4 today |
| Components | Function components, CSS Modules over the tokens | Single-file components with `<style module>` over the same tokens |
| Accessibility | `eslint-plugin-jsx-a11y-x`; focus moves and live regions commented where they are made | `eslint-plugin-vuejs-accessibility`; each focus move and live region rewritten from its comment |
| Tests | Vitest, Testing Library for React, MSW | Vitest, Testing Library for Vue, the same MSW handlers and fixtures |

The Content Security Policy still holds the port to account. Vue's full build compiles templates in the browser
with `new Function`, which `default-src 'self'` refuses; single-file components compiled by Vite need no compiler at
runtime ([web/README.md, Security](../web/README.md#security)).

Next: [architecture.md](architecture.md) for the design being ported; [production-readiness.md](production-readiness.md)
for the production controls a port must keep, each with its evidence.
