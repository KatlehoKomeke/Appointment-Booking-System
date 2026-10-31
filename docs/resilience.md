# Resilience

*Status: Built in code; not yet deployed. Staging and production, their alarms and their AWS FIS experiments exist in
the CDK app and its tests, and reach AWS when an administrator runs the steps in
[infra/README.md](../infra/README.md#hardening-the-account-that-already-runs-production). The recovery targets below
are set; none is measured yet.*

This page lists what can fail, what contains each failure, how it is noticed and how it is rehearsed. What each alarm
means and how to act on it is in the [runbook](runbook.md#alarms); every threshold is in
[infra/config/](../infra/config) and every application setting in [configuration.md](configuration.md).

| Failure | Contained by | Detected by | Rehearsed by | Target |
|---|---|---|---|---|
| [A task stops](#task-loss) | At least two tasks behind the load balancer; ECS replaces the lost one | `NoHealthyTask`, `ServerErrors`, `CanaryFailing` | FIS `<prefix>-stop-one-task` | RTO under 2 minutes |
| [A zone is lost](#zone-loss) | Tasks, NAT gateways and the database in both zones | `ServerErrors`, `ResponseTimeP99`, `CanaryFailing` | Not rehearsed as a whole | As for its parts |
| [The database fails over](#database-failover) | Multi-AZ standby; requests fail fast with `503 TRY_AGAIN` | `ServerErrors`, RDS events | FIS `<prefix>-database-failover` | RTO 1 to 2 minutes; nothing committed is lost |
| [The SMS provider fails](#sms-provider-outage) | The circuit breaker; messages wait in the outbox | `SmsCircuitOpen`, `OutboxBacklogAge` | Integration tests | Every message sent once the provider is back |
| [Messages pile up](#backlog) | The dispatcher on every task, in batches | `OutboxBacklogAge`, `DispatcherStalled` | The k6 load test | The backlog drains |
| [A deployment is bad](#bad-deploy) | Staging first; ECS's circuit breaker and alarm rollback; the pipeline's rollback | The smoke tests, `ServerErrors`, `NoHealthyTask` | CDK tests; not yet in AWS | The previous release serves again |
| [Data is lost](#lost-data) | Point-in-time restore, deletion protection, a final snapshot | Noticed by people or by the app's errors | The restore drill, not yet run | RPO 5 minutes; RTO about 30 minutes |

## Task loss

**What contains it.** Production runs two to four ARM64 Fargate tasks, spread over two availability zones, behind the
load balancer, which sends traffic only to a task whose `/readyz` passes. ECS replaces a task whose `/livez` fails,
and keeps at least the minimum running. Nothing lives in one task: the dispatcher's claims (`FOR UPDATE SKIP
LOCKED`), the lookup limit's counts, the idempotency keys, the seat locks and the daily cap's ledger are all in
PostgreSQL, so the other task carries on with no state lost ([architecture.md](architecture.md#103-in-aws)). Staging
runs one task, so there a lost task is an outage until its replacement passes its health check.

- **Detected by** `NoHealthyTask` (no healthy task for 3 minutes), `ServerErrors` and the canary's `CanaryFailing`.
- **Rehearsed by** the FIS experiment `<prefix>-stop-one-task`, which stops one task at random
  ([runbook](runbook.md#run-a-fault-injection-experiment)). It stops itself if `ServerErrors` or `NoHealthyTask` is
  raised.
- **Evidence:** [environments.test.ts](../infra/test/environments.test.ts) (the task count, the health checks and
  autoscaling), [observability.test.ts](../infra/test/observability.test.ts) (the experiment and its stop
  conditions), `LookupAttemptLimiterIT.countsAreSharedBetweenInstances` and
  `NotificationDispatcherIT.twoDispatchersNeverSendTheSameMessageTwice`.

## Zone loss

**What contains it.** In production every tier has a copy in each of the two zones: the load balancer's nodes, a task
in each zone, a NAT gateway in each zone, so the tasks in the surviving zone keep their way out to AWS's APIs, and the
Multi-AZ database's standby. Losing a zone is then a lost task and, if the database's zone went, a failover. Staging,
with one task and one NAT gateway, does not survive it, by design: it costs less, and `config.ts` refuses those
settings for production.

- **Detected by** `ServerErrors`, `ResponseTimeP99` and `CanaryFailing`, and AWS Health events.
- **Rehearsed by** its parts only: the task and failover experiments. There is no experiment that cuts a zone's
  network; FIS offers one (`aws:network:disrupt-connectivity`) if a game day needs it.
- **Evidence:** [environments.test.ts](../infra/test/environments.test.ts) ("keeps two to four tasks and a NAT
  gateway in each zone") and [config.test.ts](../infra/test/config.test.ts) ("refuses production without Multi-AZ,
  deletion protection, two tasks, or a NAT gateway per zone").

## Database failover

**What contains it.** Production's database is Multi-AZ: RDS keeps a synchronous standby in the other zone and moves
the endpoint to it, so no committed transaction is lost. While it moves, a request waits at most 3 seconds for a
connection and then answers `503 TRY_AGAIN` with `Retry-After`, rather than hanging ([errors.md](errors.md#retry-after)),
and every statement gives up after 5 seconds. The connection pools then reconnect to the same endpoint, signing in
with an IAM token as before ([ADR-0010](adr/0010-private-network-and-iam-database-auth.md)). A booking
that failed mid-way rolls back whole, and the customer's retry with the same `Idempotency-Key` either books or
replays the booking that did commit ([BR-12](domain-rules.md#br-12)).

- **Detected by** `ServerErrors` while it lasts, and the RDS event log.
- **Rehearsed by** the FIS experiment `<prefix>-database-failover` (production only), which reboots the database with
  a forced failover under the same stop conditions.
- **Evidence:** [observability.test.ts](../infra/test/observability.test.ts) ("fails the Multi-AZ database over to
  its standby, under the same stop conditions"), [environments.test.ts](../infra/test/environments.test.ts) ("runs
  the database Multi-AZ with deletion protection and 14-day backups"), `IdempotencyIT.failedBookingLeavesNoKeyRow`.

## SMS provider outage

**What contains it.** The confirmation is written to the outbox in the booking's own transaction, so an outage never
loses one and never fails a booking ([ADR-0003](adr/0003-transactional-outbox-for-confirmations.md)). Three things
stop an outage from using up customers' attempts:

- **A circuit breaker** around the SMS gateway (`DeliveryCircuit`, Resilience4j). It opens when half of the last
  calls failed or all of them were slow; while it is open the dispatcher **claims nothing**, so every message stays
  PENDING and **no attempt is spent**. After a wait it lets a few messages through as probes, and closes when they
  pass. A permanent refusal, such as a number the SMS sandbox has not verified, says nothing about the provider and
  never opens it.
- **Backoff with jitter.** A retryable refusal is tried again after 2, 4, 8 and 16 seconds, each wait shortened by a
  random share of up to a fifth, so messages refused together are not all due again at the same instant; the fifth
  failed attempt marks the message FAILED ([BR-17](domain-rules.md#br-17)).
- **No retries inside the SDK,** and a 10-second limit on each call, so a slow provider holds up one message, not the
  batch.

The breaker's thresholds and the backoff are settings ([configuration.md](configuration.md#notifications)).

- **Detected by** `SmsCircuitOpen` while the breaker is open, and `OutboxBacklogAge` once a message has waited 5
  minutes ([runbook](runbook.md#sms-circuit-open)).
- **Rehearsed by** tests only. No experiment fails AWS End User Messaging, and a staging outage would not show it:
  staging sends only simulated SMS.
- **Evidence:** `NotificationDispatcherIT.whileTheCircuitIsOpenNoMessageIsClaimedAndNoAttemptIsSpent`,
  `NotificationDispatcherIT.theCircuitRecoversThroughHalfOpenWhenTheProviderIsBack`,
  `NotificationDispatcherIT.permanentRefusalsNeverOpenTheCircuit`,
  `DeliveryCircuitTest.opensOnceTheFailureRateReachesItsThresholdAndThenRefusesEveryPermission`,
  `RetryBackoffTest.jitterNeverLengthensTheTimeToTheFifthAttempt`, and
  [observability.test.ts](../infra/test/observability.test.ts) ("raises an alarm while the SMS circuit breaker is
  open").

## Backlog

**What contains it.** Every task runs the dispatcher, which takes up to 20 due messages a tick, every 2 seconds, each
in its own transaction; two tasks share the work without sending a message twice. A burst of bookings leaves a
queue that drains at the dispatcher's rate. Nothing is lost while it waits; a confirmation is only late.

- **Detected by** `OutboxBacklogAge` (the oldest PENDING message older than 5 minutes, on the worst task) and
  `DispatcherStalled` (no tick for over a minute, for 2 minutes, or no metrics at all, which counts as a breach).
- **Rehearsed by** the [load test](#load-test): after the heavier run 302 messages were pending, and they were sent
  within 2 minutes.
- **Evidence:** `OutboxHealthMetricsIT.theAlarmGaugesFollowTheOutboxAndTheClock`,
  `NotificationDispatcherIT.theBatchSizeAndTheLastAttemptComeFromTheSettings`, and
  [observability.test.ts](../infra/test/observability.test.ts) (both alarms).

## Bad deploy

**What contains it,** in the order a release meets it ([delivery.md](delivery.md#continuous-deployment),
[ADR-0008](adr/0008-two-environments-promoted-by-digest.md)):

1. **One image, tested.** The ARM64 image is built once; the end-to-end tests, the load test and the ZAP scan run
   against that build, and every environment deploys it by digest.
2. **Staging first.** Staging deploys the digest and must pass the smoke and end-to-end tests before production is
   offered.
3. **A failed migration stops the deployment.** The migration runs as a one-off task before the service is updated;
   a non-zero exit fails the deployment, and the running tasks keep serving the old version.
4. **ECS rolls back by itself.** A new task must pass its health check before an old one stops. A deployment that
   never becomes healthy is rolled back by the deployment circuit breaker, and one that raises `ServerErrors` by the
   deployment alarm.
5. **The pipeline rolls production back.** If production's deployment or smoke test fails, `rollback-production`
   redeploys the last release that passed, its commit's CDK app with its digest. It waits for the production
   approval first, and the first production deployment has nothing to roll back to
   ([SECURITY.md](../SECURITY.md#known-risks)). By hand: [runbook](runbook.md#roll-back-by-hand).

A rollback never undoes a migration, so every migration must work with the release before it
([runbook](runbook.md#write-migrations-a-rollback-can-survive)).

- **Detected by** the smoke test in each deployment job, `ServerErrors` and `NoHealthyTask`.
- **Rehearsed by** the CDK and handler tests. The pipeline's rollback has not run against AWS yet.
- **Evidence:** [environments.test.ts](../infra/test/environments.test.ts) ("rolls back a deployment that never
  becomes healthy or that raises the 5xx alarm", "migrates the schema with a one-off task before the service is
  updated"), [schema-migration-handler.test.ts](../infra/test/schema-migration-handler.test.ts) ("fail the
  deployment when the migration exits non-zero"), `MigrateOnlyIT.aMigrateOnlyProcessThatCannotMigrateExitsNonZero`,
  and the `deploy-production` and `rollback-production` jobs of [ci.yml](../.github/workflows/ci.yml).

## Lost data

**What contains it.** RDS keeps automated backups for 14 days in production (3 in staging) and can restore the
database to any second in that window, up to about five minutes ago. Production has deletion protection, so the
database cannot be deleted by a stack change or by hand without first turning it off, and deleting it leaves a final
snapshot. Storage is encrypted. A restore makes a new instance; how to restore, and what pointing the stack at the
new instance needs, is in the [runbook](runbook.md#restore-the-database-to-a-point-in-time).

Backups also keep what the application deleted on purpose: a booking the retention job or a cancel removed stays in
the backups until they expire ([SECURITY.md](../SECURITY.md#personal-information-popia)).

- **Detected by** people, or by the app's errors; no alarm watches for missing rows.
- **Rehearsed by** the restore drill, not yet run.
- **Evidence:** [environments.test.ts](../infra/test/environments.test.ts) ("runs the database Multi-AZ with deletion
  protection and 14-day backups"), [config.test.ts](../infra/test/config.test.ts) (production refuses a database
  without deletion protection).

## Recovery targets

RTO is how long until the service is whole again; RPO is how much committed data may be lost. These are targets.
Each is measured by the drill or experiment named, after the first deployment, and the operator records the date and
the result here.

| Failure | RTO target | RPO target | Measured by | Measured on | RTO measured | RPO measured |
|---|---|---|---|---|---|---|
| One task lost (production) | Under 2 minutes, with the other task serving throughout | None: no state in the task | FIS `<prefix>-stop-one-task` | Not yet | | |
| Database failover (production) | About 1 to 2 minutes | None: the standby is synchronous | FIS `<prefix>-database-failover` | Not yet | | |
| Point-in-time restore | About 30 minutes | 5 minutes | The restore drill ([runbook](runbook.md#restore-the-database-to-a-point-in-time)) | Not yet | | |

## Load test

[load/booking.js](../load/booking.js) runs with k6: availability reads and bookings at fixed rates, with
[thresholds](../load/README.md#pass-or-fail) that fail the run. `ci`'s `stack` job runs it briefly against the image
it built, on every pull request and push to `main`. These figures come from one run on a laptop against `make up`; they depend on the machine and
are a baseline for spotting a regression on the same machine, not a capacity figure for AWS
([load/README.md](../load/README.md#observed-results)).

| Load (1 minute) | Availability p95 | Booking p95 | 5xx |
|---|---|---|---|
| 20 reads/s, 5 bookings/s | 7.2 ms | 18.8 ms | 0 |
| 100 reads/s, 20 bookings/s | 4.7 ms | 14.8 ms | 0 |

Local delivery is about 4 messages a second: the simulated gateway takes 150 ms a message. After the heavier run 302
messages were pending, and the backlog cleared within 2 minutes, with the circuit breaker closed throughout. The
`OutboxBacklogAge` threshold of 5 minutes leaves room for such a burst.

Next: [runbook.md](runbook.md) for what to do when an alarm fires; [production-readiness.md](production-readiness.md)
for every control and its evidence.
