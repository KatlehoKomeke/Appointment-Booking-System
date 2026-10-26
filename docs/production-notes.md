# Production notes

BranchBook is built as if for a bank, but it stops short of production in places it knows about. This page lists
what a real bank deployment would add or change, each beside where the repository stops today. None of it is
planned for a milestone. The accepted risks behind several of these are in
[SECURITY.md, Known risks](../SECURITY.md#known-risks), and the pipeline's gaps in
[delivery.md, Known gaps](delivery.md#known-gaps).

- [Identity before a booking counts](#identity-before-a-booking-counts)
- [A real SMS provider](#a-real-sms-provider)
- [The database](#the-database)
- [More than one task](#more-than-one-task)
- [The pipeline deploys the application](#the-pipeline-deploys-the-application)
- [Observability](#observability)
- [Retention and POPIA](#retention-and-popia)
- [The hosted demo's open-access decisions](#the-hosted-demos-open-access-decisions)

## Identity before a booking counts

**Today.** There is no login and no one-time PIN. Anyone can book with any South African mobile number, and an
appointment is opened with its reference and the last four digits of that number
([ADR-0004](adr/0004-restraint-no-login-no-id-number.md)). The accepted risks are SMS pumping and slot squatting,
learning from a `409` that a number has a booking, and blocking someone's lookup for an hour.

**In production:**

- An OTP sent to the number before an appointment counts as confirmed. It proves the customer holds the number, and
  it closes the `409` that tells a stranger a booking exists.
- Customers who bank with Fynbos sign in through the bank's identity provider, and a lookup is bound to the verified
  customer rather than to a reference and four digits.
- Per-recipient and per-prefix SMS caps, CAPTCHA on booking, premium-rate prefixes blocked, and alerts on SMS spend.
  The schema and the contract already carry `RECIPIENT_DAILY_CAP`, which no v1 rule produces
  ([architecture.md, section 5](architecture.md#5-the-simulated-confirmation)).

## A real SMS provider

**Today.** The dispatcher sends through the `MessageGateway` port, and its only adapter, the simulated gateway,
sends nothing: it takes 150 ms and refuses every number ending in `0000`. `SENT` means the gateway accepted the
message ([ADR-0003](adr/0003-transactional-outbox-for-confirmations.md)).

**In production:**

- A provider is one more implementation of
  [MessageGateway](../api/src/main/java/za/co/fynbos/branchbook/notification/application/MessageGateway.java). It
  answers `Accepted` with the provider's message id, which `provider_message_id` stores, or `Refused` with a short
  code that never holds the recipient or the body.
- Delivery is at least once, so the adapter passes the message's id to the provider as its deduplication key where
  the provider supports one.
- Delivery receipts. Accepted is not delivered: the provider reports what the handset did later, to a callback the
  application would expose, verified as the provider's and matched on `provider_message_id`. Recording it needs a
  state the schema does not have today, so it arrives with a new migration and a contract change.
- The provider's API key lives in Secrets Manager, as the database credentials do, and the provider's agreement is
  an operator agreement ([Retention and POPIA](#retention-and-popia)).

## The database

**Today.** RDS for PostgreSQL 17 on `db.t4g.micro`, Single-AZ, with 7-day backups and deletion protection off. The
task connects with `sslmode=require`, which encrypts but does not check the server's certificate. PostgreSQL's own
log is not exported, because a constraint error's `DETAIL` line can quote a mobile number
([infra/README.md](../infra/README.md#the-hosted-demo), [data-stack.ts](../infra/lib/data-stack.ts)).

**In production:**

- Multi-AZ, so a failure is a failover to the standby rather than a restore. While it lasts, a request waits 3
  seconds for a connection and then answers `503 TRY_AGAIN` with `Retry-After`, rather than hanging
  ([errors.md](errors.md)).
- `sslmode=verify-full`, with the RDS certificate bundle in the image, so an impostor inside the VPC is refused.
- Deletion protection on, an instance class sized by load tests, and backups kept as the bank's records policy says.
- Still no RDS Proxy or transaction pooler: they break the session timeouts and Flyway's session lock
  ([ADR-0006](adr/0006-hosted-demo-on-aws-with-cdk.md)).
- PostgreSQL's errors logged tersely in the parameter group, as [compose.yaml](../compose.yaml) does locally, and
  only then exported.

## More than one task

**Today.** One Fargate task, in a public subnet with a public IP so it reaches ECR and Secrets Manager without a NAT
gateway. The lookup limit counts wrong tries in memory, for that one instance
([architecture.md, Lookup](architecture.md#lookup)).

**In production:**

- At least two tasks across two Availability Zones, scaling on load, in private subnets that reach AWS through VPC
  endpoints.
- What already works with several tasks: the dispatcher claims with `FOR UPDATE SKIP LOCKED`, so two tasks never
  send the same message, and idempotency keys and seat locks live in PostgreSQL. The hourly key purge deletes only
  expired keys and the daily holiday check only logs, so running them on every task is harmless.
- What has to change: the lookup counts move to a shared store, a PostgreSQL table or a Redis-compatible cache, or
  each task allows five tries of its own. WAF also limits lookups per address, as it limits booking `POST`s today.

## The pipeline deploys the application

**Today.** `deploy` deploys only `BranchbookRelease`, the release record. The administrator deploys the hosted demo
by hand, from whatever commit is checked out. `deploy` does not wait for `ci`, and `main` requires no passing check
([delivery.md, Known gaps](delivery.md#known-gaps)).

**In production:**

- `main` protected, with `ci` and `secret-scan` as required checks, one approving review, and a `CODEOWNERS` file
  covering `.github/` and `infra/` ([CONTRIBUTING.md](../CONTRIBUTING.md#workflow)).
- One job that, after `ci` passes, builds the ARM64 image on an ARM runner, deploys `BranchbookData` and
  `BranchbookApp`, runs [smoke.sh](../scripts/smoke.sh) against the new version, and only then writes the release
  record, so the record names a version that works
  ([ADR-0007](adr/0007-continuous-deployment-from-main.md)).
- Releases from a `v*` tag after a change review, the choice ADR-0007 names as safer for a bank, through a staging
  environment first.
- The bootstrap's execution role narrowed by a custom policy or a permissions boundary
  ([infra/README.md](../infra/README.md#who-can-change-what-in-aws)).

## Observability

**Today.** On the hosted demo, application logs go to CloudWatch for 7 days, every line carrying the request's
correlation id, and three alarms watch for 5xx answers, no healthy task and low database storage. Only `health` and
`info` are exposed, and WAF blocks `/actuator`. There are no custom metrics (Tier 2), no tracing and no outbox alarm:
the outbox is read through the simulated inbox where it is switched on, or the SQL in [runbook.md](runbook.md)
([ADR-0003, Notes](adr/0003-transactional-outbox-for-confirmations.md#notes)).

**In production:**

- **Metrics:** bookings by outcome code, the rate of `409 SLOT_FULL`, lookups blocked, and the dispatcher's sends,
  refusals and latency. No label ever carries a name, a number or a reference
  ([BR-19](domain-rules.md#br-19)).
- **Tracing:** OpenTelemetry, with the correlation id beside the trace id, so a booking and its SMS read as one trace,
  as they already read as one story in the log.
- **Alerts on the outbox:** the age of the oldest PENDING message, any FAILED message, and any `DISPATCH_ERROR`.
  Today a stopped dispatcher is silent: bookings succeed, the 5xx alarm stays quiet, and no SMS leaves.
- **Where they are read:** the metrics endpoint on a management port the load balancer never routes, and logs kept
  as long as the bank's policy says, in its central log store.

## Retention and POPIA

**Today.** Collection is minimal: a name and a South African mobile number
([SECURITY.md](../SECURITY.md#personal-information-popia)). The hosted demo deletes every booking 48 hours after it
is made (`BRANCHBOOK_RETENTION_ENABLED`, live since 058e6fd); its backups expire after 7
days, and its logs, which hold no personal data, after 7 days. A local copy keeps its data until `make down`.

**In production:**

- A retention schedule agreed with the bank's records and privacy officers (POPIA section 14): how long an
  appointment is kept after its date, then deleted or de-identified, with backups and logs on the same clock.
- Written operator agreements (sections 20 and 21) with every party that processes the data for the bank: AWS, the
  SMS provider, and any log or monitoring service. A provider that handles numbers outside South Africa also has to
  meet the conditions for cross-border transfers (section 72).
- A process for a customer's access and correction requests, and for notifying the Information Regulator and the
  customers affected by a compromise (section 22).

## The hosted demo's open-access decisions

The hosted demo is open on purpose ([ADR-0006, Notes](adr/0006-hosted-demo-on-aws-with-cdk.md#notes),
[infra/README.md](../infra/README.md#the-hosted-demo)). Each choice suits a demo and would be wrong for a bank.

| On the demo | Why | In production |
|---|---|---|
| No sign-in | A sign-in prompt in front of a demo is a poor experience for everyone who opens it | Customers verified by OTP or the bank's identity provider |
| Only the demo numbers 082 555 0000 to 082 555 0199 may book (live since 058e6fd) | Keeps real numbers out when no one signs in | Any South African mobile number, proved by OTP before the booking counts |
| Every booking deleted 48 hours after it is made (live since 058e6fd) | Nothing typed in stays | The retention schedule above |
| The simulated inbox is off | With no sign-in it would list every booking's reference, and a demo number's last four digits are public | No inbox: the customer's phone is the inbox. It stays a local tool, off unless a deployment switches it on (`branchbook.inbox.enabled`) |
| The simulated gateway sends nothing | No real message reaches anyone | A real provider, with delivery receipts |
| WAF allows 30 booking `POST`s per address in 5 minutes | Slows a script when no one signs in | Kept, beside per-recipient caps and CAPTCHA |
| Deployed by hand, to be torn down on 2026-11-30 | An optional extra for the review window | Deployed by the pipeline, and kept running |

Next: [SECURITY.md](../SECURITY.md) for the controls in place; [porting.md](porting.md) for the same design on
another stack.
