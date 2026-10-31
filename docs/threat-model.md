# Threat model

*Status: Written for the code as built. The AWS controls are built in code and not yet deployed
([production-readiness.md](production-readiness.md)).*

This page takes each main flow and asks the six STRIDE questions of it: can someone **S**poof an identity, **T**amper
with data, **R**epudiate an action, learn something they should not (**I**nformation disclosure), **D**eny service,
or **E**levate their privilege? Each threat names its control and the evidence that the control works. Where a
control only reduces a threat, the rest is a residual risk, listed in [SECURITY.md](../SECURITY.md#known-risks).

A test written `Class.method` is under [api/src/test/java](../api/src/test/java); a `.test.ts` file named alone is
under [infra/test](../infra/test).

**What is worth protecting:** a customer's name and mobile number (POPIA); an appointment's reference, which with the
number's last four digits opens and cancels it; the bank's SMS spend; the service's availability; and the AWS
account, whose pipeline can change what runs.

**Who it is protected from:** an anonymous visitor or script on the internet; someone who knows a customer's
reference or number; a compromised dependency or GitHub Action; a commit that reaches `main`; and a stolen
credential.

## Book an appointment

`POST /api/v1/appointments`, anonymous by design ([ADR-0004](adr/0004-restraint-no-login-no-id-number.md)).

| | Threat | Control | Evidence |
|---|---|---|---|
| S | A script books with another person's number, to text them or to fill slots. | WAF asks for a CAPTCHA on every booking; 10 bookings and cancellations per address in 5 minutes in production; at most 5 SMS a day to one number; the published demo numbers are never texted. No one-time PIN, by choice ([ADR-0011](adr/0011-waf-captcha-instead-of-a-one-time-pin.md)). | [environments.test.ts](../infra/test/environments.test.ts) "puts WAF in front: /actuator blocked, three per-address limits, a CAPTCHA on bookings, then AWS managed rules"; `RecipientDailyCapIT.theSmsPastTheCapIsWrittenFailedAndTheBookingStillSucceeds`; `RoutingSmsGatewayTest.theDemoBlockIsAlwaysSimulated` |
| T | A crafted body books outside the rules, or injects SQL. | Every field validated, an unknown field refused, bound parameters only, and the database's exclusion constraints reject an overlapping seat from any code path. | `AppointmentApiIT.postWithUnknownJsonFieldReturns400ValidationFailed`; `ConcurrentBookingIT.twentyRacersForOneSeatProduceExactlyOne201`; [SchemaConstraintIT](../api/src/test/java/za/co/fynbos/branchbook/SchemaConstraintIT.java) |
| R | A retry, or the CAPTCHA's resend, books twice, and no one can tell which request did what. | An `Idempotency-Key` replays the same booking for 24 hours; the web app resends a challenged request once with the same key; every log line carries the request's correlation id. | `IdempotencyIT.concurrentSameKey_bookOnceAndBothReceiveSameReference`; [captcha.test.ts](../web/src/api/captcha.test.ts) |
| I | A response, an error or a log reveals personal data or another booking. | Numbers masked once booked; a `409` never names another booking's reference or time; no name, number or reference in any log, plain or JSON. | `AppointmentApiIT.responsesNeverContainUnmaskedMobile`, `AppointmentApiIT.conflict409BodiesNeverContainReferenceOrStartsAt`, `LogRedactionIT.bookingLogsContainNoCustomerNameOrMobile`, `StructuredLogRedactionIT.ecsLinesCarryTheCorrelationIdAndNothingOfTheCustomer` |
| D | A flood of bookings exhausts the service or the database. | Per-address limits on bookings and on all requests; two to four tasks with autoscaling; a connection budget the configuration enforces; database waits capped, then `503 TRY_AGAIN`. | environments.test.ts "scales between the configured bounds on CPU and on requests per task"; [config.test.ts](../infra/test/config.test.ts) "refuses pools that could open more connections than the database allows"; `AwsJdbcWrapperIT.theLockAndStatementTimeoutsReachTheServer` |
| E | A visitor reaches the actuator, Swagger or the inbox to learn or change more than a booking. | WAF blocks `/actuator`; under `prod` the actuator is on port 8081, which nothing routes, and Swagger, the API document and the inbox are off. | `ProdProfileIT.theActuatorHasAPortOfItsOwn`, `ProdProfileIT.theSimulatedInboxIsNotServed`; environments.test.ts "never exposes the management port: no target group, rule or port mapping names 8081" |

## Look up an appointment

`GET /api/v1/appointments/{reference}` with `X-Mobile-Last4`.

| | Threat | Control | Evidence |
|---|---|---|---|
| S | Someone guesses references and digits to open other people's appointments. | Eight characters from 31 symbols; the same `404` for an unknown reference and for wrong digits; five wrong tries block a reference for an hour, across every task; 100 lookups per address in 5 minutes ([BR-13](domain-rules.md#br-13)). | `AppointmentApiIT.getWithWrongLast4Returns404NotForbidden`, `AppointmentApiIT.sixthWrongLast4WithinHourReturns429`, `LookupAttemptLimiterIT.countsAreSharedBetweenInstances`; environments.test.ts (the lookup limit) |
| I | A reference leaks through a log or a URL in a log. | Log lines name appointments by id; WAF's logs redact the path, query and referrer; the load balancer keeps no access log. | `LookupAttemptLimiterIT.attemptNeverShowsTheReference`; environments.test.ts "logs WAF's requests with the number, the synthetic token, the path, the query and the referrer redacted (BR-19)" |
| I | The simulated inbox lists every booking's reference. | Off under `prod`; the guard refuses it with references shown. | `ProductionProfileGuardTest.theInboxWithoutRedactedReferencesIsRefused`, `InboxSwitchedOffIT.inboxIsAPlain404WhenSwitchedOff` |
| D | Someone who knows a reference blocks its owner's lookups for an hour. | Accepted: a reference appears only in the customer's own SMS and pages. | [SECURITY.md](../SECURITY.md#known-risks) |

## Cancel an appointment

`POST /api/v1/appointments/{reference}/cancel` with `X-Mobile-Last4`.

| | Threat | Control | Evidence |
|---|---|---|---|
| S | Someone with a leaked reference and the last four digits cancels another person's booking. | The same check and limit as a lookup; a CAPTCHA on every cancellation; a cancellation SMS to the booked number. The reference and digits remain the credential ([residual risk](../SECURITY.md#known-risks)). | `CancelAppointmentIT.wrongDigitsReturn404AreCountedAndFiveBlockTheReferenceEvenForTheRightDigits`; environments.test.ts (the CAPTCHA rule) |
| T | Two cancels, or a cancel and a booking, race and corrupt the seat. | The cancel locks the row with its version checked and deletes the booking in one transaction. | `CancelAppointmentIT.twoConcurrentCancelsGiveOne200AndOne404ThatIsCounted`, `CancelAppointmentIT.cancelFreesSeatForNewBooking` |
| R | A customer's booking disappears and they are not told. | The cancellation SMS goes to the number that booked ([BR-14](domain-rules.md#br-14)). | `CancelAppointmentIT.deleteAndCancellationCommitTogether` |
| I | A cancelled booking's details linger. | The cancel deletes the booking at once; only its cancellation SMS outlives it, until sent. | `CancelAppointmentIT.cancelReturnsTheCancelledAppointmentWithOnlyItsCancellationAndDeletesIt` |

## SMS delivery

The dispatcher sends through AWS End User Messaging SMS in production, and simulates elsewhere and for the demo
numbers.

| | Threat | Control | Evidence |
|---|---|---|---|
| S | The app is made to text premium-rate or foreign numbers (toll fraud). | A protect configuration allows South Africa only, and no voice or MMS; the task role may send only through it; each message has a maximum price. | environments.test.ts "texts South Africa only: every other SMS country, and every voice and MMS destination, is blocked", "lets the task send SMS through its protect configuration and nothing else"; `AwsSmsGatewayTest.sendsATransactionalSmsWithTheMaxPriceAndLeavesBlankSettingsOut` |
| I | AWS's error message, which can quote the number, reaches the database or a log. | A refusal is stored and logged as a fixed code, never AWS's message; delivery events, which carry the number, are not recorded. | `AwsSmsGatewayTest.eachRefusalBecomesItsFixedCodeAndNeverQuotesTheMessage` |
| D | Booking in a loop runs up the SMS bill (SMS pumping). | The CAPTCHA, the per-address limit, 5 SMS a day to one number, and AWS's monthly SMS spend limit. | `RecipientDailyCapIT.cancellationsCountTowardsTheCap`, `RecipientCapLedgerIT.bookingAndCancellingInTurnReachesTheCapThoughEveryMessageIsDeleted` |
| D | A provider outage uses up every customer's attempts. | The circuit breaker: while open, nothing is claimed and no attempt is spent ([resilience.md](resilience.md#sms-provider-outage)). | `NotificationDispatcherIT.whileTheCircuitIsOpenNoMessageIsClaimedAndNoAttemptIsSpent` |
| I | The daily cap's ledger stores numbers. | It stores an HMAC-SHA256 fingerprint under a key from Secrets Manager, for 24 hours. | `RecipientCapLedgerIT.theLedgerHoldsOnlyTheNumbersKeyedFingerprint` |

## The pipeline's deploy path

From a commit on `main` to a running task ([delivery.md](delivery.md)).

| | Threat | Control | Evidence |
|---|---|---|---|
| S | A fork, a feature branch or another repository assumes a deploy role. | OpenID Connect, no stored key; each role trusts this repository by its immutable IDs and one GitHub environment; each environment admits `main` only; pull requests never reach the delivery jobs. | [account-stack.test.ts](../infra/test/account-stack.test.ts) "creates exactly one deploy role per environment and no other role"; [delivery.md](delivery.md#github-settings-this-relies-on) |
| T | An image other than the one tested reaches production. | Built once; deployed by digest from a repository with immutable tags; provenance attested by `publish` and verified by `deploy-production` against this repository, `ci.yml` and `main`. | The `image`, `publish` and `deploy-production` jobs of [ci.yml](../.github/workflows/ci.yml); [registry-stack.test.ts](../infra/test/registry-stack.test.ts); [release-stack.test.ts](../infra/test/release-stack.test.ts) "refuses an image named by tag instead of digest" |
| T | A compromised action or dependency runs in the pipeline. | Every action pinned to a commit SHA and audited by zizmor, including for impostor commits; the `dependency-review` check refuses high and critical advisories; CodeQL; Trivy on the image; no dependency cache in the jobs that hold credentials. | The `workflow-lint` job; [dependency-review.yml](../.github/workflows/dependency-review.yml); [codeql.yml](../.github/workflows/codeql.yml); the `image` job |
| R | No one can say what is live, or who let it into production. | Each environment records its commit and digest after its smoke test; production waits for a person's approval in the `production` environment, which GitHub records. | release-stack.test.ts; the *Verify the recorded commit and image* steps |
| I | A secret or the account ID leaks through the repository or a log. | gitleaks in the pre-commit hook and on every push, push protection, the account ID masked in logs, staging's token masked, Playwright traces off while it is set. | [secret-scan.yml](../.github/workflows/secret-scan.yml); [SECURITY.md](../SECURITY.md#secrets) |
| E | A pipeline run widens its own access or reaches the other environment. | A permissions boundary on every role the stacks create; each bootstrap's execution policy limited to its environment's roles; a deploy scope denying the other environment's stacks and the administrator's; one role per environment. | [guardrails-stack.test.ts](../infra/test/guardrails-stack.test.ts) "the permissions boundary"; [deployment-policies.test.ts](../infra/test/deployment-policies.test.ts); account-stack.test.ts |

## Operator access

The administrator, the running task, and anyone holding either's credentials.

| | Threat | Control | Evidence |
|---|---|---|---|
| S | An administrator's credentials are stolen. | One IAM administrator with MFA, signed in through `aws login` with short-lived credentials and no access key; GuardDuty watches for misuse. | [infra/README.md](../infra/README.md#first-time-setup); guardrails-stack.test.ts "turns on GuardDuty with exactly the configured protection plans" |
| T | The audit trail is altered or switched off. | CloudTrail over every Region with log file validation, into an encrypted, versioned, retained bucket; the boundary denies application roles any change to it. | guardrails-stack.test.ts "records every Region and global service in one trail with log file validation, encrypted with its own rotating key", "denies tampering with CloudTrail, GuardDuty, AWS Config, Security Hub and the audit records" |
| R | A change in the account cannot be traced to who made it. | CloudTrail records every call; AWS Config records every resource's history; FIS keeps each experiment's record. | guardrails-stack.test.ts "records every supported resource with AWS Config, through its service-linked role, into the audit bucket" |
| I | Personal data is read from the database or the logs. | The database is in subnets with no internet route and admits only the tasks; logs hold no personal data; the backups are encrypted. | environments.test.ts "keeps the database encrypted, private, TLS-only, IAM-authenticated, and its log unexported" |
| D | The database is deleted, by mistake or on purpose. | Deletion protection in production, a final snapshot on deletion, and point-in-time restore. | environments.test.ts "runs the database Multi-AZ with deletion protection and 14-day backups" |
| E | A compromised task changes the schema, rewrites history or calls other AWS services. | The service signs in as `branchbook_app`, which has row privileges only; the task role can connect as that user, send SMS through one protect configuration and write metrics to one namespace; its root file system is read-only. | `RuntimeRoleIT.theRoleCannotChangeTheSchema`, `RuntimeRoleIT.theRoleCannotTouchFlywaysHistory`; [observability.test.ts](../infra/test/observability.test.ts) "lets the task role put metrics into its environment's namespace and no other" |

## Residual risks

What these controls leave open is listed, each with its reason, in [SECURITY.md, Known risks](../SECURITY.md#known-risks).
The largest: one AWS account holds both environments; a commit can reach `main` with no second person's approval; a
person who solves CAPTCHAs can still book, and text a number, up to the per-address and per-recipient limits; and a
leaked reference with the last four digits still opens and cancels an appointment.

Next: [production-readiness.md](production-readiness.md) for every control and its evidence;
[resilience.md](resilience.md) for the failures that are not attacks.
