# Production readiness

*Status: Built and merged to `main`. Every control below is in the code and proven by the evidence beside it. The
pipeline deployed staging and production on 2026-11-02; both were torn down on 2026-11-04
([Deployment status](#deployment-status)).*

This page is the control-by-control account of what makes BranchBook fit to run for a bank: what each control is,
and its evidence, a test, a CI job, a CDK assertion or cdk-nag, so each claim can be checked. The decisions behind
them are [ADR-0008](adr/0008-two-environments-promoted-by-digest.md) (two environments, one image promoted by
digest), [ADR-0009](adr/0009-configuration-from-the-environment.md) (configuration from the environment, validated),
[ADR-0010](adr/0010-private-network-and-iam-database-auth.md) (private network and IAM database authentication) and
[ADR-0011](adr/0011-waf-captcha-instead-of-a-one-time-pin.md) (a CAPTCHA and per-address limits instead of a
one-time PIN). The threats each control answers are in [threat-model.md](threat-model.md); the failures each
contains are in [resilience.md](resilience.md).

A test written `Class.method` is under [api/src/test/java](../api/src/test/java); a `.test.ts` file named alone is
under [infra/test](../infra/test), with the test's name in quotes.

- [Deployment status](#deployment-status)
- [Security](#security)
- [Resilience](#resilience)
- [Delivery](#delivery)
- [Configuration](#configuration)
- [Observability](#observability)
- [Operations](#operations)
- [Still outside this repository](#still-outside-this-repository)
- [Residual risks](#residual-risks)
- [Not yet measured](#not-yet-measured)

## Deployment status

As of 2026-11-04, with `main` at `78019db`. This is the one place the deployment's state is recorded; other pages link
here.

**Torn down.** Staging and production ran in AWS Cape Town (`af-south-1`) from 2026-11-02 until 2026-11-04, when every
BranchBook resource in AWS was deleted to stop its costs ([the teardown](#the-teardown-2026-11-04), below). Neither
`stg.branchbook.click` nor `branchbook.click` resolves. The code, its configuration and the pipeline are unchanged, so
every control on this page is still in the code with its evidence, and the environments can be rebuilt from `main`
([infra/README.md, Bringing it back](../infra/README.md#bringing-it-back)). The history below is in the past tense; the
drills' figures were measured while the environments ran.

**Merged.** The production hardening is on `main`: pull requests
[#32](https://github.com/KatlehoKomeke/Appointment-Booking-System/pull/32) and
[#33](https://github.com/KatlehoKomeke/Appointment-Booking-System/pull/33) on 2026-10-31, and
[#34](https://github.com/KatlehoKomeke/Appointment-Booking-System/pull/34) on 2026-11-01 (`ab9aa7f`). It defines
both environments' stacks and bootstraps; the image registry `BranchbookRegistry`; and the pipeline that builds the
image once and promotes its digest from staging to production, behind a person's approval, with an automatic rollback.

**Done by the administrator.** On 2026-11-01: `BranchbookAccount`, `BranchbookGuardrails`, both bootstraps and the
GitHub environments `staging` and `production`. On 2026-11-02: the `Protect main` ruleset gained its required checks
and its code-scanning rule, actions pinned to a full commit SHA became required, and the `aws-demo` environment was
deleted; `BranchbookGuardrails` was redeployed, so #43's `ReadOwnStackOutputs` took effect; and, after production's
first deployment, `CDKToolkitProduction` was narrowed to the execution policy `branchbook-cdk-cfn-exec-production`
([infra/README.md](../infra/README.md#hardening-the-account-that-already-runs-production), step 5). The first
production deployment through the narrowed policy was #49's (`38d0a4d`).

**How it got there.**

- **The first run, 2026-11-01, at `ab9aa7f`.** Every check passed. `publish` deployed `BranchbookRegistry` and pushed
  the image to ECR. `deploy-staging` created `BranchbookStagingData`; `BranchbookStagingApp` then failed and rolled
  back, because staging's CloudFormation execution role lacked `cloudformation:DescribeStacks`. CloudFormation needs
  it to resolve the App stack's `Fn::GetStackOutput` references to the Data stack, since
  [cdk.json](../infra/cdk.json) makes cross-stack references weak. `deploy-production` was skipped; production was not
  touched.
- **The fix.** Pull request [#43](https://github.com/KatlehoKomeke/Appointment-Booking-System/pull/43) (`f64a429`,
  2026-11-02) lets each environment's execution role describe its own stacks (`ReadOwnStackOutputs`), with a test in
  deployment-policies.test.ts. It took effect when the administrator redeployed `BranchbookGuardrails`.
- **The app could not start on Fargate.** Tomcat could not create its temporary directory (`AccessDeniedException` on
  `/tmp`): the read-only task mounts a scratch volume at `/tmp`, which Fargate creates owned by root with mode 755.
  Pull request [#48](https://github.com/KatlehoKomeke/Appointment-Booking-System/pull/48) (`d6471c0`) adds `RUN chmod
  1777 /tmp` and `VOLUME ["/tmp"]` to the [Dockerfile](../Dockerfile), so Fargate copies the image's `/tmp` mode into
  the volume.
- **A log group left behind.** A failed first create of `BranchbookStagingApp` left its log group
  `/branchbook-staging/app` behind, since CDK retains log groups, so the next create failed CloudFormation's early
  validation ("already exists"). The empty log group was deleted by hand and the job re-run
  ([runbook](runbook.md#a-first-deployment-fails-the-log-group-already-exists)).

**Deployed, 2026-11-02.** One pipeline run, 41498174936, deployed both environments from `d6471c0` (#48's merge),
with one image digest:

- **Staging,** `stg.branchbook.click`, at 18:42Z: `BranchbookStagingData`, `BranchbookStagingApp` and
  `BranchbookStagingRelease`, through the bootstrap `CDKToolkitStaging` (qualifier `bbstg`). The smoke and end-to-end
  tests passed there. The WAF CAPTCHA was live, so `/api/v1/client-config` published the CAPTCHA script; WAF answered
  `/actuator` with `403`; the OpenAPI document and Swagger UI answered `404`.
- **Production,** `branchbook.click`, at 19:15Z, after a person's approval, the same digest: `BranchbookData`,
  `BranchbookApp` and `BranchbookRelease`, through the bootstrap `CDKToolkitProduction` (qualifier `bbprod`). It
  replaced the single hosted demo in place, keeping its stack IDs. Checked afterwards: the database ran Multi-AZ, with
  deletion protection, IAM database authentication and 14-day backups; every production IAM role carried
  `branchbook-permissions-boundary`; and the smoke test passed with `EXPECT_PROD_PROFILE=1 EXPECT_CAPTCHA=1`, so a
  booking without a CAPTCHA token got `405` with `x-amzn-waf-action: captcha`.

Until production's first deployment, `branchbook.click` served the single hosted demo, last deployed by the previous
pipeline on 2026-10-31 from `bab2d81`, on the infrastructure before the hardening, as
[ADR-0006](adr/0006-hosted-demo-on-aws-with-cdk.md) designed it.

**The drills, 2026-11-02,** run while the environments ran, with a probe calling
`GET /api/v1/branches/SAN/availability`, which reads the database, once a second
([resilience.md](resilience.md#recovery-targets)):

- Stopping staging's one task: 61 seconds of `502` and `503` until the replacement served.
- Stopping one of production's two tasks: none of 233 requests failed; the service was back at full strength about 95
  seconds after the stop. Target met.
- Failing production's database over: 1 minute 49 seconds of intermittent errors, 37 of about 109 requests. Target
  met.
- Restoring production's database to a point in time: usable after 13 minutes 10 seconds, meeting the RTO; restored to
  7 minutes 48 seconds before the request, missing the 5-minute RPO. Pointing the stack at the restored instance was
  not rehearsed.

**Open when they were torn down.** These applied to the running environments, and apply again once they are rebuilt:

- **`DatabaseMemory` was raised in both environments.** `db.t4g.micro` (1 GiB) left about 36 to 107 MB free under
  normal load, under the alarm's 128 MiB (`databaseFreeableMemoryMiB` in [infra/config/](../infra/config)). Lowering the
  threshold would hide it; the fix is a larger instance class, `db.t4g.small`, not yet made in the configuration
  ([runbook](runbook.md#alarms)).
- **The restore's RPO, measured again** on a quiet day: the newest restorable point was already 6.5 minutes behind
  shortly after the failover drill.
- **The restore's step 3,** pointing the stack at the restored instance, to rehearse
  ([runbook](runbook.md#restore-the-database-to-a-point-in-time)).
- **The SMS sandbox.** The account was in it while production ran, so only verified numbers received production's
  texts; the banner says so ([infra/README.md](../infra/README.md#real-sms)).
- **One AWS account for both environments,** and **a merge to `main` needs no second person's review**
  ([SECURITY.md](../SECURITY.md#known-risks)).

<a id="the-teardown-2026-11-04"></a>

**The teardown, 2026-11-04.** Every BranchBook resource in AWS was deleted to stop its costs, ahead of the planned
date of 2026-11-30.

- **Deleted:** the stacks `BranchbookApp`, `BranchbookData`, `BranchbookRelease`, `BranchbookStagingApp`,
  `BranchbookStagingData`, `BranchbookStagingRelease` and `BranchbookRegistry`; the bootstraps `CDKToolkitProduction`
  (`bbprod`) and `CDKToolkitStaging` (`bbstg`); and `BranchbookGuardrails`, so GuardDuty, AWS Config recording,
  Security Hub, the CloudTrail trail and IAM Access Analyzer are off in the account.
- **Removed by hand,** because their stacks keep them: every database snapshot, the final ones included, so no
  booking data is kept anywhere; the ECR repository `branchbook`; the bootstraps' asset buckets; production's canary
  artifact bucket; both of `BranchbookGuardrails`' audit buckets; the log groups left behind; and the Route 53 hosted
  zone for `branchbook.click`. Both CloudTrail KMS keys are scheduled for deletion on 2026-11-11, AWS's 7-day minimum.
- **Kept:** `BranchbookAccount` in `us-east-1`, with the GitHub OIDC provider, the two deploy roles and the budget
  alert, which costs nothing; the GitHub environments `staging` and `production`, with their secrets; and the
  registration of the domain `branchbook.click`, paid up front, with auto-renew off, which expires on 2027-10-26. With
  no hosted zone, neither address resolves.
- **Left alone:** the default `CDKToolkit`, which other CDK apps in the account share. Its `af-south-1` ECR repository
  held the hosted demo's images, which cost cents; their size was not measured.
- **The pipeline:** pull-request checks run as before. A push to `main` runs the checks, then the delivery jobs fail,
  because the bootstraps they deploy through are gone; nothing can be created in AWS.

**Bringing it back** is five steps, in [infra/README.md](../infra/README.md#bringing-it-back): a new hosted zone and
its ID in the configuration, the guardrails, the two bootstraps with their deploy scopes, a merge to `main`, and the
domain's auto-renew. The steps have not been run.

## Security

**At the edge**

| Control | What it is | Evidence |
|---|---|---|
| Web application firewall | AWS WAF blocks `/actuator` after decoding and lowercasing the path, then applies AWS's IP reputation, known bad inputs and common rule groups. | [environments.test.ts](../infra/test/environments.test.ts) "puts WAF in front: /actuator blocked, three per-address limits, a CAPTCHA on bookings, then AWS managed rules" |
| Per-address limits | Each address is limited, per 5 minutes, on bookings and cancellations, on lookups and on all requests; production allows 10, 100 and 1,000. | The same test; the values are in [infra/config/](../infra/config) |
| CAPTCHA on every booking and cancellation | WAF answers an unsolved `POST` with `405` and `x-amzn-waf-action: captcha`. The web app shows AWS's puzzle in an accessible dialog and resends the same request once, with the same `Idempotency-Key`, so a booking is never made twice. | The same test; [captcha.test.ts](../web/src/api/captcha.test.ts), [CaptchaProvider.test.tsx](../web/src/captcha/CaptchaProvider.test.tsx), [awsWafCaptcha.test.ts](../web/src/captcha/awsWafCaptcha.test.ts) |
| No CAPTCHA bypass in production | Only staging lets the pipeline's synthetic traffic past the CAPTCHA, with a token kept in Secrets Manager; the configuration validator refuses one for production. | [config.test.ts](../infra/test/config.test.ts) "refuses a synthetic-traffic bypass in production"; environments.test.ts "lets no request skip the CAPTCHA: there is no synthetic-traffic bypass or token" |
| HTTPS only | TLS 1.3 policy at the load balancer, plain HTTP redirected, HSTS. | environments.test.ts "serves HTTPS with a TLS 1.3 policy and redirects plain HTTP to it"; `SecurityHeadersIT.everyResponseCarriesTheBrowserDefences` |
| Strict Content-Security-Policy | `default-src 'self'`; AWS WAF's domain is added to scripts and connections only where a CAPTCHA is configured. The end-to-end tests fail on any CSP violation. | `SecurityHeadersIT.withoutACaptchaThePolicyIsTheStrictOne`, `CaptchaConfiguredIT.thePolicyAllowsAwsWafForScriptsAndConnections`, [e2e/fixtures.ts](../e2e/fixtures.ts) |

**In the network and the task**

| Control | What it is | Evidence |
|---|---|---|
| Private subnets | The tasks run in private subnets with no public address, behind NAT gateways (one per zone in production). | environments.test.ts "runs the tasks in private subnets with no public address" |
| Narrow security groups | The tasks admit only the load balancer, and call out only on 443 and to the database on 5432; the database admits only the tasks. | environments.test.ts "lets the tasks call out on 443 and to the database alone, and the database admit the tasks alone" |
| A hardened task | Read-only root file system, every Linux capability dropped, `/tmp` on a task volume, and a non-root user from the image. | environments.test.ts "runs a read-only root file system with no Linux capabilities, a /tmp volume and a /livez health check"; [Dockerfile](../Dockerfile) |
| The actuator off the public port | Under the `prod` profile the actuator listens on 8081, which no target group or rule names; the probes stay on 8080. | `ProdProfileIT.theActuatorHasAPortOfItsOwn`, `ProductionProfileGuardTest.theActuatorOnThePublicPortIsRefused`, environments.test.ts "never exposes the management port: no target group, rule or port mapping names 8081" |
| The `prod` profile fails closed | Swagger, the OpenAPI document and demo data are off, and the simulated inbox is off with references redacted; `ProductionProfileGuard` refuses unsafe combinations at start-up. | [ProductionProfileGuardTest](../api/src/test/java/za/co/fynbos/branchbook/shared/config/ProductionProfileGuardTest.java), `ProdProfileIT.theSimulatedInboxIsNotServed` |

**Data and identity**

| Control | What it is | Evidence |
|---|---|---|
| Database private, encrypted, TLS-only | RDS in subnets with no internet route, storage encrypted, `rds.force_ssl=1`, PostgreSQL's log not exported (a constraint's detail can quote a number). | environments.test.ts "keeps the database encrypted, private, TLS-only, IAM-authenticated, and its log unexported" |
| No application password | The service signs in as `branchbook_app` with IAM database authentication through the AWS Advanced JDBC Wrapper, and checks the server's certificate (`sslmode=verify-full`, the RDS CA bundle pinned in the image). | environments.test.ts "signs the service in to the database as branchbook_app with an IAM token, and gives it no password"; [AwsJdbcWrapperIT](../api/src/test/java/za/co/fynbos/branchbook/AwsJdbcWrapperIT.java) |
| Least-privilege runtime role | Migration V9 creates `branchbook_app` with row privileges only: no DDL and no access to Flyway's history. | `RuntimeRoleIT.theRoleCannotChangeTheSchema`, `RuntimeRoleIT.theRoleCannotTouchFlywaysHistory`, `RuntimeRoleIT.signedInAsTheRoleTheApplicationsWritesWork` |
| The master secret for migrations alone | The master credentials, generated into Secrets Manager, go to the one-off migration task and nothing else. | environments.test.ts "gives only the migration task the master secret, with a plain verify-full URL, and no web server", "generates the database credentials and the SMS cap key into Secrets Manager, never into the template" |
| A narrow task role | The task may connect to the database as its own user, send SMS only through its protect configuration, and write metrics only to its environment's namespace. | environments.test.ts "lets the task send SMS through its protect configuration and nothing else"; [observability.test.ts](../infra/test/observability.test.ts) "lets the task role put metrics into its environment's namespace and no other"; [nag.test.ts](../infra/test/nag.test.ts) |
| SMS to South Africa only | A protect configuration blocks every other country and every voice and MMS destination; production sends at most 5 SMS a day to one number outside the demo block. | environments.test.ts "texts South Africa only: every other SMS country, and every voice and MMS destination, is blocked"; `RecipientDailyCapIT.theSmsPastTheCapIsWrittenFailedAndTheBookingStillSucceeds` |
| No personal data in logs | No name, number or reference in the application's log, PostgreSQL's, or the `prod` profile's JSON lines; WAF logs redact the path, the query, cookies and the identifying headers; the load balancer keeps no access log ([BR-19](domain-rules.md#br-19)). | `LogRedactionIT.bookingLogsContainNoCustomerNameOrMobile`, `StructuredLogRedactionIT.ecsLinesCarryTheCorrelationIdAndNothingOfTheCustomer`, environments.test.ts "logs WAF's requests with the number, the synthetic token, the path, the query and the referrer redacted (BR-19)" |

**The account and the pipeline's reach**

| Control | What it is | Evidence |
|---|---|---|
| Audit and detection | `BranchbookGuardrails`: a CloudTrail trail over every Region with log file validation, GuardDuty, AWS Config, Security Hub (AWS Foundational Security Best Practices) and IAM Access Analyzer. | [guardrails-stack.test.ts](../infra/test/guardrails-stack.test.ts) |
| A permissions boundary on every application role | The boundary denies IAM users and keys, leaving or changing the boundary, account and Organizations changes, tampering with the audit and detection services, and borrowing an unbounded role. | guardrails-stack.test.ts "the permissions boundary"; [deployment-policies.test.ts](../infra/test/deployment-policies.test.ts) "every role the stacks it deploys create carries the permissions boundary, custom-resource roles included" |
| A narrowed execution policy per environment | Each CDK bootstrap's CloudFormation role may use the application's services, read its own environment's stacks' outputs ([pull request #43](https://github.com/KatlehoKomeke/Appointment-Booking-System/pull/43)), and use IAM only on its own environment's roles, with the boundary attached. Staging's bootstrap had it from the start; production's from 2026-11-02, after its first deployment. | deployment-policies.test.ts "the execution policy allows the service of every resource type the stacks it deploys contain" and "the execution policy may read the outputs of every stack the stacks it deploys reference, and no other stack"; guardrails-stack.test.ts |
| One deploy role per environment | Each GitHub environment's role trusts only this repository, by its immutable IDs, and that environment; it may assume only its own bootstrap's roles, and a deploy scope denies it the other environment's stacks. | [account-stack.test.ts](../infra/test/account-stack.test.ts) "creates exactly one deploy role per environment and no other role" |
| cdk-nag | The AWS Solutions rule pack runs over every stack of both environments; each accepted finding is acknowledged where its resource is defined, with the reason. | [nag.test.ts](../infra/test/nag.test.ts) "every stack synthesises with no unacknowledged finding" |
| No committed database password | `compose.yaml` holds none; `make up` writes a random one to the git-ignored `.env`, and CI generates its own and fails if any log mentions a password. | The `stack` job of [ci.yml](../.github/workflows/ci.yml), step *Check that the logs hold no credentials* |

## Resilience

What each failure does and how it is rehearsed is in [resilience.md](resilience.md).

| Control | What it is | Evidence |
|---|---|---|
| More than one task, scaling on load | Production runs two to four tasks over two zones, scaling on CPU and on requests per task; no state lives in a task. | environments.test.ts "keeps two to four tasks and a NAT gateway in each zone", "scales between the configured bounds on CPU and on requests per task"; `LookupAttemptLimiterIT.countsAreSharedBetweenInstances` |
| A NAT gateway per zone | Losing one zone does not cut the other zone's way out to AWS's APIs. | The same test; [config.test.ts](../infra/test/config.test.ts) refuses fewer for production |
| Multi-AZ database with deletion protection | A synchronous standby in the other zone; production's database cannot be deleted without first turning protection off, and leaves a final snapshot. | environments.test.ts "runs the database Multi-AZ with deletion protection and 14-day backups" |
| Point-in-time restore | 14 days of automated backups in production, 3 in staging. | The same test |
| Fail fast on the database | A request waits at most 3 seconds for a connection and a statement at most 5, then answers `503 TRY_AGAIN` with `Retry-After`. | `AwsJdbcWrapperIT.theLockAndStatementTimeoutsReachTheServer`; [errors.md](errors.md#retry-after) |
| The SMS circuit breaker | Around the SMS gateway: while it is open the dispatcher claims nothing, so a provider outage spends no customer's attempts. | `NotificationDispatcherIT.whileTheCircuitIsOpenNoMessageIsClaimedAndNoAttemptIsSpent`, `NotificationDispatcherIT.theCircuitRecoversThroughHalfOpenWhenTheProviderIsBack`, [DeliveryCircuitTest](../api/src/test/java/za/co/fynbos/branchbook/notification/application/DeliveryCircuitTest.java) |
| Backoff with jitter | Retries after 2, 4, 8 and 16 seconds, each shortened by up to a fifth at random; FAILED on the fifth attempt. | `RetryBackoffTest.defaultJitterKeepsEveryWaitWithinAFifthBelowTodaysSchedule`, `NotificationDispatcherIT.retriesWithBackoffThenMarksFailedAfterFiveAttempts` |
| The confirmation cannot be lost | The SMS is written in the booking's transaction and delivered from the outbox ([ADR-0003](adr/0003-transactional-outbox-for-confirmations.md)). | `ConfirmationOutboxIT.bookingAndPendingMessagesCommitAtomically` |
| A deployment rolls itself back | ECS's deployment circuit breaker rolls back a deployment that never becomes healthy, and its deployment alarm one that raises `ServerErrors`. | environments.test.ts "rolls back a deployment that never becomes healthy or that raises the 5xx alarm" |
| Fault injection | AWS FIS templates stop one task, and fail production's database over, with the 5xx and no-healthy-task alarms as stop conditions. | observability.test.ts "fails the Multi-AZ database over to its standby, under the same stop conditions" |

## Delivery

Each job and step is described in [delivery.md](delivery.md).

| Control | What it is | Evidence |
|---|---|---|
| Built once | The ARM64 image is built once; the `stack` job and `publish` check they hold that exact build; no job rebuilds it. | The `image`, `stack` and `publish` jobs of [ci.yml](../.github/workflows/ci.yml) |
| Tested as it ships | Smoke tests, Playwright, a k6 run and an OWASP ZAP baseline scan run against that image under Docker Compose. | The `stack` job |
| Scanned and described | Trivy fails the build on a fixable HIGH or CRITICAL vulnerability; a CycloneDX SBOM is written for every build. | The `image` job |
| Promoted by digest | `publish` pushes the image to ECR, whose tags are immutable, and every environment deploys it by digest; a tag is refused. | The `publish` job; [registry-stack.test.ts](../infra/test/registry-stack.test.ts) "creates the branchbook repository with immutable tags and a scan of every push"; [release-stack.test.ts](../infra/test/release-stack.test.ts) "refuses an image named by tag instead of digest" |
| Attested provenance, verified before production | `publish` attests the build's provenance and SBOM; `deploy-production` verifies the provenance against this repository, `ci.yml`, `main` and GitHub-hosted runners. | The `publish` and `deploy-production` jobs |
| Staging first | Staging deploys the digest and must pass the smoke and end-to-end tests before production is offered. | The `deploy-staging` job |
| A person approves production | The `production` GitHub environment requires an approval, which administrators may not bypass. | [delivery.md](delivery.md#github-settings-this-relies-on) |
| The release is recorded after it passes | Each environment records its commit and digest only after the smoke test, then reads them back. | release-stack.test.ts; the *Verify the recorded commit and image* steps |
| Automatic rollback | If production's deployment or smoke test fails, `rollback-production` redeploys the last recorded release, its CDK app with its digest. | The `rollback-production` job |
| No stale deployment | A deployment job refuses a commit older than the release the environment runs. | The order checks in `deploy-staging` and `deploy-production` ([release.sh](../.github/scripts/release.sh)) |
| No stored AWS keys | GitHub Actions signs in through OpenID Connect; the account ID is masked in logs. | account-stack.test.ts; [aws-connection-check.yml](../.github/workflows/aws-connection-check.yml) |
| Pinned actions, linted workflows | Every action is pinned to a commit SHA; actionlint (with shellcheck) and zizmor check every workflow. | The `workflow-lint` job |
| Static analysis | CodeQL's `security-extended` queries over the Java, the TypeScript and the workflows. | [codeql.yml](../.github/workflows/codeql.yml), job `analyze` |
| Dependency gate | A pull request that adds a dependency with a high or critical advisory fails. | [dependency-review.yml](../.github/workflows/dependency-review.yml) |
| Secret scanning | gitleaks in the pre-commit hook and over the full history on every push; GitHub push protection. | [secret-scan.yml](../.github/workflows/secret-scan.yml), job `gitleaks`; [.githooks/pre-commit](../.githooks/pre-commit) |
| Required checks | `main` refuses a push or merge until every check above has passed. | [delivery.md](delivery.md#github-settings-this-relies-on) |

## Configuration

The settings themselves are in [configuration.md](configuration.md) and [infra/config/](../infra/config)
([ADR-0009](adr/0009-configuration-from-the-environment.md)).

| Control | What it is | Evidence |
|---|---|---|
| Every setting is named and documented | Every tunable value reads a named environment variable with a safe default, and configuration.md lists each one. | `ConfigurationReferenceTest.everyPropertyIsDocumentedWithItsEnvironmentVariable` |
| Unsafe production settings stop start-up | Under `prod`, `ProductionProfileGuard` refuses demo data, an unredacted inbox, a plain-HTTP public address, the SMS cap without its key, and the actuator on the public port, naming each problem without quoting a value. | `ProductionProfileGuardTest.everyProblemIsNamedAndNoValueIsQuoted` and the other refusals in that class |
| Validated environment files | Each environment's JSON is checked field by field before any stack is built: types, ranges, unknown fields, values AWS offers, and the database's connection budget. | [config.test.ts](../infra/test/config.test.ts) "refuses pools that could open more connections than the database allows" and the other refusals |
| Production-only rules | Production must run Multi-AZ with deletion protection, at least two tasks, a NAT gateway per zone and no CAPTCHA bypass. | config.test.ts "refuses production without Multi-AZ, deletion protection, two tasks, or a NAT gateway per zone" |
| Secrets are never configuration | The validator refuses anything named like a secret in the app's environment; secrets come from Secrets Manager or IAM. | config.test.ts "refuses a secret, or a variable the stack computes, in the app environment" |
| Migrations are not the service's job | Both environments run `prod` with migrations off at start-up; the migration task alone runs them. | config.test.ts "refuses migrate-on-start, another profile, or an unredacted inbox"; `MigrateOnlyIT.aMigrateOnlyRunStartsNothingButTheMigration` |

## Observability

What each alarm means and what to do is in the [runbook](runbook.md#alarms); the alarms' metrics and thresholds are in
[infra/README.md](../infra/README.md#observability-and-fault-injection).

| Control | What it is | Evidence |
|---|---|---|
| Structured logs | Under `prod`, one JSON object per line in Elastic Common Schema, each carrying the request's correlation id, kept 30 days in production. | `StructuredLogRedactionIT.ecsLinesCarryTheCorrelationIdAndNothingOfTheCustomer`; environments.test.ts "keeps application logs for the configured period" |
| The outbox's health as metrics | Gauges for the oldest pending message's age, FAILED messages and the time since the dispatcher's last tick, with no tags. | [OutboxHealthGaugesTest](../api/src/test/java/za/co/fynbos/branchbook/notification/application/OutboxHealthGaugesTest.java), `OutboxHealthMetricsIT.theAlarmGaugesFollowTheOutboxAndTheClock` |
| The circuit breaker as metrics | The breaker's state and calls, tagged with its name and state only. | `DeliveryCircuitTest.itsMetersAreTaggedWithItsNameAndState` |
| Export to CloudWatch | Only the chosen meters go to CloudWatch, into one namespace per environment. | `CloudWatchMetricsConfigurationTest.onItSendsTheIncludedMetersToTheNamespaceAndNothingElse`; observability.test.ts |
| Alarms | Backlog age, new failures, a stalled dispatcher, an open circuit, p99 latency, the database's and the service's CPU, memory and connections, a failing canary, and the earlier 5xx, no-healthy-task and storage alarms. | observability.test.ts, one test per alarm; "keeps the first three alarms as they were, and adds eleven" |
| A dashboard per environment | Every alarm beside the figures it reads, WAF's blocks and CAPTCHAs, and the canary. | observability.test.ts "draws one dashboard named after the resource prefix, covering every signal" |
| An outside-in canary | Every 5 minutes, `GET /readyz` and the availability API from outside, GET only, so it books nothing. | [canary-script.test.ts](../infra/test/canary-script.test.ts); observability.test.ts "runs the canary on the newest supported runtime, on the configured schedule, against this environment" |
| Network and edge logs | VPC flow logs of rejected traffic, and WAF's logs with the identifying fields redacted. | environments.test.ts "gives the tasks private subnets with a way out through the configured NAT gateways, and logs rejected traffic" |
| No personal data in metrics | Meter tags are fixed words, never a value a customer typed. | `MetricsIT.scrapeHoldsNoPersonalData` |

## Operations

| Control | What it is | Evidence |
|---|---|---|
| Migrations as a deployment step | A one-off ECS task runs Flyway with the master secret, before the service is updated; a non-zero exit fails the deployment. | environments.test.ts "migrates the schema with a one-off task before the service is updated"; [schema-migration-handler.test.ts](../infra/test/schema-migration-handler.test.ts); `MigrateOnlyIT.aMigrateOnlyProcessThatCannotMigrateExitsNonZero` |
| A runbook for every alarm | What each alarm means, the first three checks and the fix; the SMS circuit and a stalled dispatcher in detail. | [runbook.md](runbook.md#alarms) |
| Rollback by hand | Redeploy the previous release's commit with its digest from `/<prefix>/release-image`. | [runbook.md](runbook.md#roll-back-by-hand) |
| Point-in-time restore | Restore to a new instance, then move the stack to it. | [runbook.md](runbook.md#restore-the-database-to-a-point-in-time) |
| Migrations a rollback can survive | Expand first, contract a release later, so the previous image works against the newer schema. | [runbook.md](runbook.md#write-migrations-a-rollback-can-survive) |
| Fault-injection experiments | How to run each, and what to record. | [runbook.md](runbook.md#run-a-fault-injection-experiment) |
| Personal data deleted when it is due | Each booking is deleted within the hour after its appointment ends, and at once when it is cancelled. | `RetentionPurgeIT.anAppointmentGoesOnceItHasEndedNotBefore`, `CancelAppointmentIT.cancelReturnsTheCancelledAppointmentWithOnlyItsCancellationAndDeletesIt` |
| Cost alerts | An AWS budget e-mails at 50, 80 and 100 percent of its monthly limit. The two environments, the guardrails and the observability are estimated, not measured, at about US$380 to 450 a month. | account-stack.test.ts; the limit is in [infra/config/account.json](../infra/config/account.json) |
| Administrator steps written down | The guardrails, both bootstraps, the deploy roles, the GitHub environments, the order for an account that already runs production, and bringing the environments back after the teardown. | [infra/README.md](../infra/README.md#first-time-setup), [Bringing it back](../infra/README.md#bringing-it-back) |

## Still outside this repository

What a bank would add that this repository does not attempt, each because it needs something the repository cannot
supply:

- **Identity before a booking counts:** a one-time PIN, or sign-in through the bank's identity provider, so a lookup is
  bound to a verified customer rather than to a reference and four digits
  ([ADR-0004](adr/0004-restraint-no-login-no-id-number.md), [ADR-0011](adr/0011-waf-captcha-instead-of-a-one-time-pin.md)).
- **Delivery receipts** from the SMS provider, matched on `provider_message_id` without logging the number, and the
  bank's own origination identity. Today `SENT` means AWS accepted the message.
- **Tracing** with OpenTelemetry, the correlation id beside the trace id.
- **A retention schedule and operator agreements under POPIA:** how long an appointment is kept, with backups and logs
  on the same clock (section 14); agreements with every operator (sections 20 and 21); and a process for breach
  notification and access requests (section 22).
- **An AWS account per environment,** and a required approval on pull requests with `CODEOWNERS`, which this
  one-person repository cannot give itself ([residual risks](#residual-risks)).

## Residual risks

The risks that remain, each with its reason, are in [SECURITY.md, Known risks](../SECURITY.md#known-risks), and the
pipeline's remaining gaps in [delivery.md, Known gaps](delivery.md#known-gaps).

## Not yet measured

The recovery targets were measured on 2026-11-02, while the environments ran, apart from the database failover's data
loss (nothing was written during the drill) and the restore's step 3 ([resilience.md](resilience.md#recovery-targets));
the restore's RPO missed its target and is to be measured again. These are still to measure, once the environments are
rebuilt, and are recorded once run:

- **The pipeline end to end:** the first push through staging, the approval and production ran on 2026-11-02, but was
  not timed, and the automatic rollback has not been rehearsed against AWS
  ([delivery.md](delivery.md#one-push-measured)).
- **AWS's CAPTCHA puzzle in a browser** on staging, including that the Content-Security-Policy blocks nothing it
  needs. CI uses fakes and the staging bypass.
- **Load in AWS.** The k6 figures come from a laptop ([resilience.md](resilience.md#load-test)).

Next: [threat-model.md](threat-model.md) for the threats these controls answer; [SECURITY.md](../SECURITY.md) for the
policy.
