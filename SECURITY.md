# Security policy

BranchBook is an engineering assessment for a fictional bank. It holds no real customer data, but it is built as
if it did. This document explains how secrets and personal information are protected, and how to report a problem.

- [Reporting a vulnerability](#reporting-a-vulnerability)
- [Secrets](#secrets)
- [Personal information (POPIA)](#personal-information-popia)
- [Hardening in the application](#hardening-in-the-application)
- [Pipeline access](#pipeline-access)
- [Known risks](#known-risks)

## Reporting a vulnerability

Please report it privately through GitHub: open the repository's **Security** tab and choose
**Report a vulnerability**, or go straight to
[the private report form](https://github.com/KatlehoKomeke/Appointment-Booking-System/security/advisories/new).
Only you and the repository's administrators can see the report. Please do not open a public issue for a security
problem.

## Secrets

The rule is that no secret enters this repository: not in code, configuration, tests, documentation, screenshots or
the git history. Local development uses throwaway values: [.env.example](.env.example) holds placeholders for the
local Docker Compose database only, and `.env` is git-ignored.

![Where a secret is stopped, in four phases from git commit to run time: before the commit, .gitignore, blocked file names and gitleaks in an opt-in pre-commit hook that fails closed (1 to 3); at the push, GitHub push protection for provider tokens (4); after the push, once public, the secret-scan workflow over the full history, a required check that blocks a merge but not the push, GitHub secret scanning, and CI's log check for the run's database password (5 to 7); at run time, OpenID Connect with no AWS access keys, Secrets Manager holding the master database secret for the migration task alone, the SMS cap key and staging's synthetic-traffic token, IAM database authentication so the app has no database password, and the random local password make up writes to the git-ignored .env (8 to 11).](docs/assets/diagrams/secret-layers.svg)

Layers 1 to 4 act before the code is public. Layers 5 to 7 act after it, so they can report a secret but not keep it
private. Layers 8 to 11 keep secrets out of the running system, so there are as few as possible to leak.

### In place today

1. **.gitignore** ([.gitignore](.gitignore)) keeps `.env` files (`.env` and `.env.*`, but not `.env.example`), keys
   and certificates (`*.pem`, `*.key`, `*.p12`, `*.pfx`, `*.jks`, `*.keystore`, `*.crt`, `*.der`, `id_rsa*`,
   `id_ed25519*`), `credentials` files, `*.secret(s)`, `secrets/`, `.aws/`, `.npmrc`, `.netrc`, `.claude/`,
   `CLAUDE.local.md` and `cdk.context.json` out of `git add`. It can be skipped with `git add -f`; layer 2 then
   refuses the name.
2. **The pre-commit hook refuses blocked file names** ([.githooks/pre-commit](.githooks/pre-commit)): staged `.env`
   files (not `.env.example`), `*.pem`, `*.key`, `*.p12`, `*.pfx`, `*.jks`, `*.keystore`, `id_rsa*`, `id_ed25519*`,
   anything under `.claude/`, `CLAUDE.local.md` and `cdk.context.json`, even when they were force-added. It blocks
   the commit.
3. **The pre-commit hook runs [gitleaks](https://github.com/gitleaks/gitleaks) on the staged diff**: a local
   install if there is one, otherwise v8.30.1 in Docker. It applies gitleaks' default rules plus the two in
   [.gitleaks.toml](.gitleaks.toml): `postgres-url-with-password`, and
   `aws-account-arn`, which catches an account ID inside an ARN, though not a bare 12-digit ID. It fails closed: a
   finding, or having neither gitleaks nor a running Docker, refuses the commit. Both pre-commit checks are opt-in
   (`make hooks`, once per clone), and `git commit --no-verify` skips them.
4. **GitHub push protection** is on for known provider token formats only. Non-provider patterns are off, and GitHub
   does not read `.gitleaks.toml`, so the two custom rules do not apply here. It blocks the push; skipping it takes a
   bypass that GitHub records. From here on, the commits are public.
5. **The secret-scan workflow** ([secret-scan.yml](.github/workflows/secret-scan.yml)) applies the `.gitleaks.toml`
   rules to the full history on every push and pull request, so a skipped local hook is still found after the push.
   Its `gitleaks` job is a required check on `main`, so a finding stops a merge, but the pushed commits are already
   public ([docs/delivery.md](docs/delivery.md#known-gaps)).
6. **GitHub secret scanning** finds provider tokens anywhere in the history and raises alerts.
7. **The CI log check** (the `stack` job in [ci.yml](.github/workflows/ci.yml)) fails `ci` if the app or database
   container logs hold that run's generated database password, or mention `password` at all, ignoring case.

The GitHub settings behind layers 4 and 6 can be read with
`gh api repos/KatlehoKomeke/Appointment-Booking-System --jq .security_and_analysis`.

### At run time

*Layers 9 and 10 are built in code and not yet deployed. Until the administrator's steps in
[infra/README.md](infra/README.md#hardening-the-account-that-already-runs-production) run, the hosted demo runs as
[infra/README.md](infra/README.md#the-hosted-demo) describes, with its database credentials in Secrets Manager.*

8. **No AWS access key exists.** GitHub Actions signs in through OpenID Connect, with sessions of at most one hour.
   Each GitHub environment, `staging` and `production`, holds its own role's ARN as its secret
   `AWS_DEPLOY_ROLE_ARN`, and `ALERT_EMAIL` where alarms are e-mailed; the account ID is masked in the workflow logs.
   The one secret the pipeline reads at run time, staging's synthetic-traffic token, is masked before it is used
   ([ADR-0008](docs/adr/0008-two-environments-promoted-by-digest.md),
   [infra/README.md](infra/README.md#how-github-reaches-aws-without-keys)).
9. **Secrets Manager, in each environment.** AWS generates the database's master credentials into
   `<prefix>/database`, which only the one-off migration task receives; the key the SMS daily cap fingerprints each
   number with, `<prefix>/sms-cap-key`; and, in staging only, the token that lets the pipeline's synthetic traffic skip
   the CAPTCHA. No secret is ever typed, committed, held by GitHub or written into a template.
10. **IAM database authentication.** The service signs in as `branchbook_app` with a short-lived token its task role
    signs, so it has **no database password** at all
    ([ADR-0010](docs/adr/0010-private-network-and-iam-database-auth.md)). The CAPTCHA's API key reaches the task as
    plain configuration because it is public by design: AWS binds it to the environment's domain, and the browser
    must read it.
11. **The local stack.** `compose.yaml` commits no database password: the first `make up` writes a random
    `DB_PASSWORD` to the git-ignored `.env`, and CI generates and masks its own.

### In AWS

The controls below are built in code and not yet deployed, as above.

Neither environment has a sign-in, so neither holds a password of its own
([ADR-0004](docs/adr/0004-restraint-no-login-no-id-number.md)). Production texts real SMS, so any South African
mobile number may book there, and every page asks visitors to use only their own number; staging takes the published
demo numbers only and texts no one. What limits the personal data they hold, and who production can text:

- **Each booking is deleted once its appointment is over** (`BRANCHBOOK_RETENTION_ENABLED`): the hourly job removes
  it, with its messages and idempotency keys, within the hour after it ends, and never before. A cancel deletes its
  booking at once.
- **A CAPTCHA and per-address limits.** AWS WAF asks for a CAPTCHA on every booking and cancellation, and limits each
  address's bookings and cancellations, lookups and requests; production allows 10, 100 and 1,000 in five minutes
  ([ADR-0011](docs/adr/0011-waf-captcha-instead-of-a-one-time-pin.md)).
- **South Africa only.** The SMS go through AWS End User Messaging SMS in Cape Town (`BRANCHBOOK_SMS_GATEWAY=aws`),
  with a protect configuration that blocks SMS to every country AWS lists except South Africa, and every voice and
  MMS destination. The task role may call `sms-voice:SendTextMessage` through that protect configuration and nothing
  else.
- **At most five SMS a day to one number** (`BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP=5`), counted by a keyed fingerprint
  of the number, cancelled bookings' messages included. The task takes `BRANCHBOOK_SMS_RECIPIENT_CAP_KEY` from
  Secrets Manager, so a restart or a new task keeps every number's count, and the `prod` profile refuses to start
  with the cap on and no key.
- **The demo numbers are never texted.** 082 555 0000 to 082 555 0199 always stay on the simulated gateway, so the
  README's examples never reach a stranger.
- **No delivery events are logged.** No configuration set records them, because they carry the destination number
  ([BR-19](docs/domain-rules.md#br-19)).
- **The simulated inbox is off,** and the `prod` profile shows every reference as `APT-****-****` should it be
  switched on, in the reference field, the body, the subject and the last error; `ProductionProfileGuard` refuses to
  start with the inbox on and references shown. Each booker still sees their own SMS and its status on the
  confirmation page.

A new AWS account is in the SMS sandbox, where only verified destination numbers receive messages; until AWS grants
production access, a text to any other number ends `FAILED`, and the booking still stands
([infra/README.md](infra/README.md#real-sms)).

The other controls, in [infra/lib/app-stack.ts](infra/lib/app-stack.ts) and
[infra/lib/data-stack.ts](infra/lib/data-stack.ts), each with its evidence in
[docs/production-readiness.md](docs/production-readiness.md#security): AWS WAF blocks `/actuator` (403) after
decoding and lowercasing the path, and applies AWS's managed rules; the actuator listens on port 8081, which no
target group or rule names; HTTPS uses ELB's recommended TLS 1.3 policy, plain HTTP is redirected, and HSTS keeps
browsers there; the tasks run in private subnets with no public address, read-only root file systems and no Linux
capabilities, and accept only the load balancer; the database, in subnets with no route to the internet, accepts only
the tasks, over TLS with the certificate checked (`sslmode=verify-full`), encrypted at rest. PostgreSQL's own log is
not exported, because a constraint error's detail line can quote a mobile number; WAF's logs redact the path, query,
cookies and identifying headers; and the application's logs are kept 30 days in production and 7 in staging.

### If a secret is committed

1. **Treat it as compromised.** Rotate or revoke it first, before anything else.
2. **Remove it from the history** with `git filter-repo`, then force-push. The `Protect main` ruleset refuses
   force-pushes, so switch it off for that one push (Settings, Rules) and on again straight after. Every clone must
   be re-cloned.
3. **Record what happened** and add a scanner rule if gitleaks did not catch it.

Removing a secret from the history does not make it safe again. Rotation is the fix; the cleanup only reduces noise.

## Personal information (POPIA)

BranchBook is designed around the Protection of Personal Information Act, even though all of its data is fictional.

- **Minimal collection (section 10).** *Built.* A booking stores a first name, a last name and a South African mobile
  number. No ID number, address, date of birth or account number is collected
  ([ADR-0004](docs/adr/0004-restraint-no-login-no-id-number.md)).
- **No exposure.** *Built in M3.* Once an appointment exists, the API never returns its full mobile number: the
  caller who booked it, or who has just proved its last four digits, sees `082 *** 0101`. The simulated inbox,
  built in M4, shows only `082 *** ****`, and is off unless a deployment switches it on (`branchbook.inbox.enabled`);
  its retry, built in M6, exists only with it and can do no more than make a failed message due again
  ([BR-19](docs/domain-rules.md#br-19); `AppointmentApiIT.responsesNeverContainUnmaskedMobile`,
  `InboxApiIT.listsTheNewestMessagesWithTheirReferencesAndRedactedRecipients`).
  Error bodies never contain personal data or another booking's details ([BR-23](docs/domain-rules.md#br-23)).
- **Who else receives the number.** *Built.* Where `BRANCHBOOK_SMS_GATEWAY=aws`, as in production, the number
  and the text of each SMS go to AWS End User Messaging SMS in Cape Town (af-south-1), and on to the mobile network,
  to deliver it; a refusal is stored as a fixed code such as `AWS_THROTTLED`, never AWS's message, which can quote
  the number. Elsewhere nothing leaves the application.
- **No personal data in logs.** *Built: the database and driver settings in M1, the log proof in M3.* Application
  logs and PostgreSQL's own error logs carry no names or numbers:
  - PostgreSQL logs errors tersely, without the DETAIL line that quotes a constraint's conflicting values (set in
    `compose.yaml` and for the test database).
  - The JDBC driver leaves those values out of its exception messages;
    `SchemaConstraintIT.violationMessagesNeverQuoteTheConflictingValues` checks this.
  - Unexpected exceptions are logged with their types and stack frames but never their messages;
    `ProblemDetailHandlerTest` and `ErrorDispatchControllerTest` check this.
  - Hibernate's own SQL error log, which repeats the database's message, is switched off; the booking module logs
    only the constraint name and SQLSTATE.
  - `LogRedactionIT.bookingLogsContainNoCustomerNameOrMobile` takes one synthetic customer through every outcome
    of a booking and a lookup, then checks the application's log and PostgreSQL's for their name, their number in
    any form, masked or not, and the appointment's reference.
    `LogRedactionIT.cancelAndRetryLogsContainNoCustomerNameMobileOrReference` does the same for a cancellation and
    the retry of its messages.
  - Under the `prod` profile each line is a JSON object in Elastic Common Schema;
    `StructuredLogRedactionIT.ecsLinesCarryTheCorrelationIdAndNothingOfTheCustomer` checks those lines carry the
    correlation id and nothing of the customer.
- **Log lines name appointments by id.** *Built in M3.* A reference and the last four digits open an appointment, so log
  lines carry the appointment's id and never its reference.
- **No personal data in metrics.** *Built in M6.* `/actuator/prometheus` holds counts, durations and JVM figures.
  Their tags are fixed words: methods, statuses, exception types, and URI templates such as
  `/api/v1/appointments/{reference}`, never the path a caller sent; an unknown path is `uri="/**"`. The booking
  counter's only tag is its outcome, one of seven words (FR-30). `MetricsIT.scrapeHoldsNoPersonalData` books, looks
  the booking up, cancels it, opens the app page that names its reference and types a number into an unknown path,
  then checks the scrape for the customer's name, their number in any form and the reference.
- **Synthetic test data only.** Tests and demos use made-up names and the published demo numbers: `082 555 01xx`,
  and `082 555 0000`, which the simulated gateway always refuses, to show a failed delivery. The demo data that
  docker compose books on a fresh database uses `+27 60 000 0001`, `+27 60 000 0002` and `+27 60 000 0000`, the last
  refused like any number ending 0000, with the fictional surname Demo ([api/README.md](api/README.md#demo-data)).
  Never use a real person's details, including your own.
- **A deployment can refuse real numbers.** *Built; off by default, on in staging, and off in production, which sends real SMS.* While
  `branchbook.booking.demo-numbers-only` is true (`BRANCHBOOK_BOOKING_DEMO_NUMBERS_ONLY=true`), a booking is taken
  only for the demo block, `082 555 0000` to `082 555 0199`. Any other South African mobile is refused with
  `422 DEMO_NUMBER_REQUIRED` before anything is written, so a real person's number is never stored or sent an SMS,
  and the refusal never repeats the number (`DemoNumbersOnlyIT.aNumberOutsideTheBlockIsRefusedOnItsFieldAndWritesNothing`,
  `MobileNumberTest.theDemoBlockRunsFrom0825550000To0825550199`). The start-up log says which numbers bookings take.
  The SMS gateway is simulated by default, and for the demo block everywhere; where `BRANCHBOOK_SMS_GATEWAY=aws`, as
  in production, every other number is sent a real SMS (`RoutingSmsGatewayTest.theDemoBlockIsAlwaysSimulated`,
  and [Known risks](#known-risks)). In staging and production the simulated inbox is off, so no
  visitor can list other people's bookings, and every booking is deleted with the purge below. Names are free text;
  the form and the privacy page ask for a made-up one. ADR-0006 planned to lock each demo number to a fictional
  persona; the block and the purge were built instead
  ([ADR-0006](docs/adr/0006-hosted-demo-on-aws-with-cdk.md)).
- **Staging and production delete every booking once its appointment is over.** *Built; on in AWS only.* While
  `branchbook.retention.enabled` is true (`BRANCHBOOK_RETENTION_ENABLED=true`), a job runs every hour, from a minute
  after start-up, and deletes every appointment whose end time has passed, with its SMS messages, which hold the
  number and the reference, and its idempotency keys (`branchbook.retention.after-end`,
  `BRANCHBOOK_RETENTION_AFTER_END`, can add a delay; it is zero by default). So within about an hour of an appointment
  ending, nothing the customer typed is left in the live database. A booking for a future date is kept until then.
  Backups are not purged: the automated database backups can restore a deleted booking until they expire, 14 days
  after they were taken in production and 3 in staging, and tearing the database down keeps a final snapshot until
  someone deletes it ([docs/resilience.md](docs/resilience.md#lost-data)). The branches, services, hours and holidays
  are never touched. Only the staging and production task definitions switch it on. It is off by default, so `make up`,
  `make dev-api` and the tests keep their bookings until you delete them, as the privacy page says of a local copy.
  Each run logs one line of counts (`retention.purged appointments= messages= keys=`) and nothing else
  (`RetentionPurgeIT.aFutureBookingIsKeptHoweverLongAgoItWasMade`, `RetentionPurgeIT.anAppointmentGoesOnceItHasEndedNotBefore`).
- **A cancelled booking is deleted at once.** *Built; everywhere.* The cancel deletes the appointment, with the
  customer's name and number, and every confirmation SMS of it, in the same transaction that cancels it, so no
  cancelled booking stays in the live database ([BR-14](docs/domain-rules.md#br-14)). Two things are kept, with no
  appointment: the cancellation SMS, which holds the number and the reference, only until it is sent or has failed
  for good, within about 35 seconds while the dispatcher runs, or about 2 seconds where AWS refuses it for good,
  when it is deleted too; and the booking's
  idempotency key, which holds no name or number, only an unkeyed SHA-256 of the request's details (pseudonymised:
  someone who guesses the details could confirm them against it), for the rest of its 24 hours, so a retry cannot
  book the cancelled booking again. Opening or cancelling the reference afterwards is the same `404` as for a reference never issued.
  The deleted messages still count towards the per-recipient daily cap for 24 hours, through the cap's ledger of
  keyed fingerprints below ([BR-17](docs/domain-rules.md#br-17)). Backups are not purged, as above
  (`CancelAppointmentIT.cancelReturnsTheCancelledAppointmentWithOnlyItsCancellationAndDeletesIt`,
  `NotificationDispatcherIT.cancelDeletesThePendingConfirmationAndTheSentCancellationIsDeleted`).
- **The daily cap keeps a keyed fingerprint of a number, not the number, for 24 hours.** *Built; only where the cap
  is on, as in production.* While `BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP` is above 0, each SMS written to a number
  outside the demo block adds one row to the cap's ledger, `sms_recipient_sends`: the number's fingerprint,
  HMAC-SHA256 of its E.164 form under `BRANCHBOOK_SMS_RECIPIENT_CAP_KEY`, and when the SMS was written. No number,
  name, reference, text or link to a booking or message is stored there. The cap counts these rows, so the messages a
  cancel deletes still count, and an hourly job deletes each row once it is 24 hours old, so up to about an hour later
  ([BR-17](docs/domain-rules.md#br-17)). Where the cap is off, nothing is written. A fingerprint is still personal
  information to whoever holds the key, who could match it to a number by trying every South African mobile number,
  so the key is a secret: at least 32 characters, never logged or printed. Where none is set, a random key is made at
  each start, kept only in memory, and lost with it, so earlier fingerprints can no longer be matched by anyone
  (`RecipientCapLedgerIT.theLedgerHoldsOnlyTheNumbersKeyedFingerprint`,
  `RecipientCapLedgerIT.bookingAndCancellingInTurnReachesTheCapThoughEveryMessageIsDeleted`,
  `RecipientSendLedgerPurgeJobIT.purgeRemovesRowsTwentyFourHoursOldAndKeepsYoungerOnes`,
  `NotificationOutboxIT.theDailyCapIsOffByDefault`, `RecipientFingerprintsTest.aShortKeyStopsStartUpAndIsNeverPrinted`).

## Hardening in the application

- **Stateless, with security headers.** No cookies or sessions. A strict `Content-Security-Policy` (`default-src 'self'`)
  is set, and framing is denied; where a CAPTCHA is configured, AWS WAF's domain is added to scripts and connections
  and nothing else ([SecurityConfig.java](api/src/main/java/za/co/fynbos/branchbook/shared/security/SecurityConfig.java),
  `SecurityHeadersIT.withoutACaptchaThePolicyIsTheStrictOne`).
- **Input is validated at the edge,** and all SQL uses bound parameters.
- **Lookups resist enumeration.** *Built in M3.* The same 404 for an unknown reference and for wrong digits, and a
  per-reference rate limit: five wrong lookups block a reference for an hour from the first
  ([BR-13](docs/domain-rules.md#br-13)). In AWS, WAF also limits each address's lookups.
- **Only three operational endpoints, off the public port in AWS.** `/actuator/health`, `/actuator/info` and
  `/actuator/prometheus` are exposed; `env`, `beans`, `heapdump`, `metrics`, `loggers` and the rest answer 404
  (`SecurityHeadersIT.operationalEndpointsBeyondHealthInfoAndPrometheusAreClosed`). `/actuator/prometheus` carries no
  personal data (above), but shows request rates and timings, connection-pool use and JVM figures. Under docker
  compose it is on `127.0.0.1` only. Under the `prod` profile, which staging and production run, the actuator listens
  on port 8081, which no load balancer rule reaches, and the probes stay on 8080 (`ProdProfileIT.theActuatorHasAPortOfItsOwn`).
- **The `prod` profile fails closed.** Swagger, the OpenAPI document and demo data are off, and `ProductionProfileGuard`
  refuses to start with an unsafe combination, such as the inbox with references shown or the actuator on the public
  port ([docs/configuration.md](docs/configuration.md#process-and-profile)).
- **The database login can only read and write rows.** The service's role, `branchbook_app` (migration V9), has no
  DDL and no access to Flyway's history (`RuntimeRoleIT.theRoleCannotChangeTheSchema`); migrations run as a separate
  task with the master secret.
- **Containers run as a non-root user** with pinned base images ([Dockerfile](Dockerfile)); in AWS the root file
  system is read-only and every Linux capability is dropped.
- **Dependencies are pinned and updated through Dependabot** ([dependabot.yml](.github/dependabot.yml)). Dependabot
  alerts and security updates are on.
- **Code and the image are scanned on every pull request and push to `main`** ([docs/delivery.md](docs/delivery.md#supply-chain)):
  CodeQL's `security-extended` queries over the Java, the TypeScript and the workflows; the `dependency-review` workflow, which
  refuses a new dependency with a high or critical advisory; Trivy, which fails the build on a fixable HIGH or
  CRITICAL vulnerability in the image; gitleaks over the full history; an OWASP ZAP baseline scan of the running app;
  and zizmor and actionlint over the workflows.

## Pipeline access

The pipeline reaches AWS with no stored key. Each GitHub environment, `staging` and `production`, has its own role,
which trusts only this repository and that environment, and may assume only its own CDK bootstrap's roles. Production
also waits for a person's approval. Since this change, IAM limits a deployment too: every role the application stacks
create carries a permissions boundary, each bootstrap's execution policy allows IAM only on its own environment's
roles, and a deploy scope denies each role the other environment's stacks and the administrator's
([ADR-0008](docs/adr/0008-two-environments-promoted-by-digest.md)). What IAM, GitHub and the code each enforce, and
what is still not prevented, are in [infra/README.md](infra/README.md#who-can-change-what-in-aws).

## Known risks

The risks that remain, each with the reason it remains, what limits it today and what would close it. Each control
named here, with its evidence, is in [docs/production-readiness.md](docs/production-readiness.md); the threats behind
them are in [docs/threat-model.md](docs/threat-model.md).

| Risk | Why it remains | What limits it | What would close it |
|---|---|---|---|
| **Booking and cancelling without sign-in.** A person who solves CAPTCHAs can still book, and so make production text any South African number; booking with someone's number can reveal from a `409` that they have a booking, though never its reference or time ([BR-23](docs/domain-rules.md#br-23)); a leaked reference with the number's last four digits opens and cancels an appointment; and someone who knows a reference can block its lookups for an hour. | The product owner chose no login and no one-time PIN: an SMS PIN costs a message per booking, adds friction and invites SMS pumping ([ADR-0004](docs/adr/0004-restraint-no-login-no-id-number.md), [ADR-0011](docs/adr/0011-waf-captcha-instead-of-a-one-time-pin.md)). | The CAPTCHA and per-address limits; at most 5 SMS a day to one number, counting the messages a cancel deletes (cancels racing each other can pass it by a message or two); South Africa only; `MaxPrice` and AWS's monthly SMS spend limit; the demo block never texted; the lookup limit ([BR-13](docs/domain-rules.md#br-13)); a cancellation SMS to the booked number. | A one-time PIN to the number, or sign-in through the bank's identity provider, with appointments bound to the verified customer. |
| **One AWS account for both environments.** Staging's stacks could reach production's resources through services the execution policy allows, such as a security group rule or a record in the shared hosted zone. | Separate accounts were rejected for this account's cost and setup time ([ADR-0008](docs/adr/0008-two-environments-promoted-by-digest.md)). The deploy-scope deny stops CloudFormation on the other environment's stacks, not every API call. | A bootstrap, an execution policy and a deploy role per environment, the deploy-scope deny, and the permissions boundary. | An AWS account per environment. |
| **No required approval on `main`.** A merged commit could change what an application role trusts or may do, within the permissions boundary. | One-person repository: no second person can approve. | Required checks on `main`; the `production` environment's required approval gates every production deployment; the boundary stops an account takeover. | A required approval on pull requests and a `CODEOWNERS` file for `.github/` and `infra/`. |
| **Automatic rollback waits for approval.** `rollback-production` cannot start until a person approves it. | The production role trusts only the `production` environment, which requires an approval for every job. | During a deployment, ECS's circuit breaker and the 5xx alarm roll back on their own; a failed `cdk deploy` is restored by CloudFormation ([docs/delivery.md](docs/delivery.md#known-gaps)). | A rollback environment without approvers whose role may only redeploy a recorded digest. |
| **The first production deployment after the cutover cannot roll back automatically.** | `/branchbook/release-image` is first written by this pipeline, so there is no recorded digest to return to. | `rollback-production` stops and says so; a rollback by hand ([docs/runbook.md](docs/runbook.md#roll-back-by-hand)). | Nothing: it closes with the first successful production deployment. |
| **The CAPTCHA puzzle is only exercised by hand.** | A test cannot solve AWS's puzzle: CI passes the staging bypass, and production's smoke test asserts only the challenge. | Unit tests with a fake puzzle; the smoke test proves an unsolved booking is challenged. | A check by hand in a browser on staging after any change to the CAPTCHA path. |
| **Attestations are verified online and not stored in ECR.** | The registry's host name holds the account ID, and a public repository's attestations are public. | `deploy-production` verifies the provenance with `gh attestation verify` before every deployment. | A registry host without the account ID in it, then attestations pushed beside the image and verified in AWS. |
| **ZAP runs a passive baseline only,** over what its spider finds; the single-page app's API calls are not crawled. | A passive scan is safe against a shared stack and needs no API definition. | The smoke test checks the API's headers and refusals; CodeQL reads the code. | ZAP as a proxy for the end-to-end tests, or an API scan from `docs/openapi.yaml` against a disposable stack. |
| **GuardDuty, AWS Config and Security Hub cover `af-south-1` only.** | They are regional, and the workload runs only in `af-south-1`. | CloudTrail records every Region, so activity elsewhere is recorded, though not analysed. | Turn them on in every enabled Region, or disable the unused Regions. |
| **The master database secret is not rotated.** | Scheduled rotation needs AWS's rotation function, which is not set up ([infra/lib/data-stack.ts](infra/lib/data-stack.ts)). | Only the one-off migration task reads it; the service has no database password. | Rotation through Secrets Manager. |
| **Simulated SMS failures do not raise the failed-message alarm.** | The demo number ending 0000 fails on purpose, so counting it would raise `OutboxNewFailures` on every demo. | Real sends are counted; the simulated gateway sends nothing. | Nothing needed while the demo numbers stay simulated. |

**Closed by the production hardening** ([docs/production-readiness.md](docs/production-readiness.md)): the database
certificate is now checked (`sslmode=verify-full`, [Security](docs/production-readiness.md#security)); the actuator
is off the public port; the pipeline's deployments are limited by IAM, not only by code; WAF limits each address's
lookups as well as its bookings; code, dependencies and the image are scanned; staging comes before production, behind
an approval, with the image promoted by digest ([Delivery](docs/production-readiness.md#delivery)); and the service no
longer connects as the database's owner.
