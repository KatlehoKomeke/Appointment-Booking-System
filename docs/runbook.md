# Runbook

How to look after a running BranchBook: whether it is healthy, what happened to a message, what an AWS refusal
means and how to let production send real SMS, how to replay one that failed, what the daily cap's ledger holds,
whether a reference is blocked, whether the retention purge ran, how to follow one request through the logs and read
the metrics, what each alarm means and what to do, how to run a fault-injection experiment, restore the database to
a point in time and roll back by hand, and how to write a migration a rollback can survive. The commands are for the
local stack that `make up` starts, where `make psql` opens a SQL prompt on its database, unless a section says it is
for staging or production. Every number in this file is a synthetic demo number. The failure modes behind the alarms,
and the drills that rehearse them, are in [resilience.md](resilience.md).

## Is it healthy?

| Check | Command | Healthy |
|---|---|---|
| The process | `curl -s localhost:8080/livez` | `{"status":"UP"}`. It ignores the database on purpose, so a database outage never restarts the app. |
| Ready for traffic | `curl -s localhost:8080/readyz` | `{"status":"UP"}`: started, and PostgreSQL answers. Docker and the load balancer use it. |
| The whole stack | `make smoke` | `All checks passed`. It writes nothing. |
| The race for the last seat | `make demo-409` | `Exactly one booking won the seat`. It books one appointment, so only on a local stack. |
| A deployed environment | `EXPECT_PROD_PROFILE=1 EXPECT_CAPTCHA=1 scripts/smoke.sh https://branchbook.click` (or `https://stg.branchbook.click`) | `All checks passed`. The checks a CAPTCHA stops are skipped; it writes nothing. Each environment's dashboard and its canary watch the same from outside ([Alarms](#alarms)). |

## What happened to a message?

The dispatcher delivers PENDING messages every 2 seconds, on its own thread, `outbox-1`
([BR-17](domain-rules.md#br-17)). The simulated inbox lists the newest messages of every booking, recipients redacted,
and references too, as `APT-****-****`, where `BRANCHBOOK_INBOX_REDACT_REFERENCES` is on (it is off in `make up`);
each message's `id` identifies it either way:

```bash
curl -s 'localhost:8080/api/v1/messages?limit=20'
```

A message is in one of three states:

- **PENDING**: waiting for its next attempt at `nextAttemptAt`. A refused attempt is retried after about 2, 4, 8
  and 16 seconds, each wait up to a fifth shorter at random (`branchbook.dispatcher.backoff`,
  [configuration.md](configuration.md#notifications)), unless the refusal is permanent; `attempts` and `lastError`
  say how far it got. While the [SMS circuit is open](#sms-circuit-open) no message is claimed, so a message waits
  PENDING with no attempt spent.
- **SENT**: the gateway accepted it at `sentAt`.
- **FAILED**: it will not be tried again on its own. `failureReason` says why:
  - `DELIVERY_FAILED`: the gateway refused all five attempts, or refused one permanently, as AWS refuses a number it
    will not text ([the AWS codes](#what-the-aws-codes-mean)); `lastError` holds the code. The simulated gateway
    refuses every number ending in `0000`, such as 082 555 0000, five times, so this is how a failed delivery is shown.
  - `DISPATCH_ERROR` with `lastError` `MARK_FAILED`: the gateway was called, but the outcome could not be recorded,
    so the message was set FAILED rather than risk sending it again and again. It may have been delivered once.
  - `SUPERSEDED_BY_CANCELLATION`: a confirmation the dispatcher found, when it claimed it, to belong to a cancelled
    appointment, so it was not sent ([BR-24](domain-rules.md#br-24)). A cancel deletes its booking's confirmations
    with the booking, so only an appointment cancelled before cancels deleted bookings (V6), or set CANCELLED by
    hand, can leave one.
  - `RECIPIENT_DAILY_CAP`: the number had already been written as many SMS in the last 24 hours as
    `BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP` allows, counted in the cap's ledger, deleted messages included
    ([below](#the-daily-caps-ledger)), so this one was written FAILED and never sent. The booking or the cancel went
    through. It is never retried; the number gets SMS again once its oldest counted SMS is a day old. A cancellation
    the cap stops is never stored at all: the cancel's response shows it FAILED with this reason.

A cancellation SMS is never kept in a final state. A cancel deletes the booking at once, so the cancellation has no
appointment and shows `APT-****-****` as its reference in the inbox; once it is SENT, or FAILED for good, the
dispatcher deletes it in the same transaction and logs `outbox.deleted messageId=... kind=CANCELLATION`. So the
inbox shows a cancellation only while it is PENDING ([BR-14](domain-rules.md#br-14)).

`retryable` is true exactly when a retry would send the message again: FAILED with `DELIVERY_FAILED` or
`DISPATCH_ERROR`, and, for a confirmation, an appointment that is neither cancelled nor started.

An appointment never depends on its messages: a FAILED confirmation leaves the booking CONFIRMED.

The same facts in SQL:

```sql
SELECT id, kind, status, attempts, next_attempt_at, failure_reason, last_error, sent_at
FROM notification_messages
ORDER BY created_at DESC
LIMIT 20;
```

## What the AWS codes mean

Where `BRANCHBOOK_SMS_GATEWAY=aws`, as in production, every number outside the published demo block, 082 555 0000
to 0199, is sent through AWS End User Messaging SMS, and the start-up log has one line naming the gateway, the
region and whether a protect configuration and a daily cap are set. A refusal that sending again cannot cure, marked
*At once* below, makes the message FAILED with `DELIVERY_FAILED` on that attempt. Any other, marked *Retried*, is
tried again after the backoff above, as the simulated gateway's refusal is, and the fifth makes it FAILED with
`DELIVERY_FAILED`; a run of *Retried* refusals also opens the [SMS circuit](#sms-circuit-open). Either way
`lastError` holds the last code. Each code is the exception's type and, where AWS gives one, its reason; AWS's
message is never kept, because it can quote the number.

| `lastError` | Fails | What happened | What to do |
|---|---|---|---|
| `AWS_CONFLICT_DESTINATION_PHONE_NUMBER_NOT_VERIFIED` | At once | The account is in the SMS sandbox and the number is not a verified destination. | Verify the number, or leave the sandbox (below). |
| `AWS_CONFLICT_DESTINATION_COUNTRY_BLOCKED_BY_PROTECT_CONFIGURATION` | At once | The protect configuration does not allow the destination's country. | Nothing, for a number outside South Africa; otherwise check the protect configuration. |
| `AWS_CONFLICT_DESTINATION_PHONE_NUMBER_OPTED_OUT` | At once | The recipient replied STOP, or is on the account's opt-out list. | Nothing: AWS will not send to them. |
| `AWS_CONFLICT_` and another reason, or `AWS_CONFLICT` | At once | The account or one of its resources is in a state that forbids the send. | The reason names it. |
| `AWS_QUOTA_MONTHLY_SPEND_LIMIT_REACHED_FOR_TEXT`, or the same with `_FOR_MEDIA`, `_FOR_VOICE`, `_FOR_RCS` or `_FOR_NOTIFY` | At once | A monthly spend limit is used up, for SMS usually the text one; nothing more is sent this month. | Raise the limit through the Support case below, or wait for the next month. |
| `AWS_QUOTA_` and any other reason | Retried | Another AWS quota is reached. | The reason names it. |
| `AWS_VALIDATION_PRICE_OVER_THRESHOLD` | At once | The message would cost more than `BRANCHBOOK_SMS_AWS_MAX_PRICE` (US$0.20 by default). | Raise the setting only if the price is expected. |
| `AWS_VALIDATION_` and another reason | At once | AWS refused the request, for example a setting it does not accept. | The reason names it; check the `BRANCHBOOK_SMS_AWS_*` settings. |
| `AWS_ACCESS_DENIED` | At once | The task role may not call `sms-voice:SendTextMessage`, or the account is disabled for SMS. | Check the task role's policy in the CDK app. |
| `AWS_RESOURCE_NOT_FOUND` | At once | A protect configuration, configuration set or origination identity named in the settings does not exist. | Correct the setting. |
| `AWS_THROTTLED`, `AWS_INTERNAL` | Retried | AWS is busy or failed; the next attempt usually succeeds. | Nothing, unless every message shows it. |
| `AWS_UNREACHABLE` | Retried | The SDK could not complete the call: the network, a 10-second timeout, or no credentials or region. | Check the task's network route to AWS and its role. |
| `AWS_ERROR` | Retried | Any other AWS error. | Search the AWS Health Dashboard; the code carries nothing more. |

The log gives the same code without a query. The dispatcher's `outbox.retry` and `outbox.failed` lines name the
message by its id, never its number, and end with the code. A code marked *At once* ends the message on the attempt
that met it, usually the first; any other shows on `outbox.retry` until the fifth attempt's `outbox.failed`:

```text
outbox.retry messageId=... attempts=1 retryInSeconds=1.734 code=AWS_THROTTLED
outbox.failed messageId=... attempts=1 code=AWS_CONFLICT_DESTINATION_PHONE_NUMBER_NOT_VERIFIED
```

`retryInSeconds` is the wait with its jitter, to the millisecond. Locally, `docker compose logs app | grep
outbox.failed` lists them; in staging and production, search the log group `/<prefix>/app` in CloudWatch Logs for
`outbox.failed` (the `prod` profile writes each line as one JSON object, so the line is in its `message` field). The inbox or the SQL above shows the message by that id. Replaying a message that failed at once
([below](#replay-a-failed-message)) sends it again, so fix the cause first, or it fails at once again.

A message AWS accepted is SENT with AWS's `MessageId` as its provider id. SENT means AWS took it, not that the phone
received it: South Africa supports no sender IDs, so with no origination identity AWS sends over a shared route, best
effort.

<a id="let-the-hosted-demo-send-real-sms"></a>

### Let production send real SMS

Production texts for real (`sms.gateway` is `aws` in [infra/config/production.json](../infra/config/production.json));
staging stays simulated. The account owner does these steps in the AWS console, in Cape Town (af-south-1); the CDK
app creates the protect configuration, the task role's permission and the settings, but not these.

1. **In the sandbox, verify each number that should receive SMS.** A new account is in the SMS sandbox: it sends only
   to verified destination numbers, at most 10, and spends at most US$1 a month. In AWS End User Messaging SMS, open
   *Phone numbers > Verified destination numbers* (or the sandbox page), add the number, and enter the code AWS texts
   to it. A booking for any other number is FAILED with `AWS_CONFLICT_DESTINATION_PHONE_NUMBER_NOT_VERIFIED` on
   its first attempt.
2. **To text any South African number, open a Support case for production access.** In the same console, request
   production access (a case with AWS Support) for af-south-1, naming the use (transactional appointment
   confirmations), the expected volume and South Africa as the only destination. In the same case, or another, ask
   for the monthly SMS spend limit the demo needs; the account's own limit can then be set up to it. Until AWS
   answers, the sandbox's limits stand.
3. **Check the start-up log** after the next deployment: `SMS gateway: AWS End User Messaging SMS in af-south-1 ...`,
   with a protect configuration and a daily cap set.

To stop real SMS, set `sms.gateway` to `simulated` in the environment's configuration and let the pipeline deploy
it; every message then goes to the simulated gateway. While AWS itself is failing, the
[SMS circuit](#sms-circuit-open) already holds messages back on its own.

## Replay a failed message

Where the simulated inbox is switched on (`branchbook.inbox.enabled`, as in `make up` and `make dev-api`), the retry
endpoint is the replay path. The web inbox's Retry button calls it. Put the message's id, from the inbox, in
place of the example:

```bash
curl -s -X POST 'localhost:8080/api/v1/messages/00000000-0000-0000-0000-000000000000/retry'
```

It answers with the message as the inbox shows it. A message a retry could send comes back PENDING, with no
attempts, due now; the dispatcher picks it up within 2 seconds. Any other message comes back unchanged with
`retryable` false: one that is PENDING or SENT, a superseded or capped one, and the confirmation of an appointment
that has been cancelled or has already started. An unknown id is `404 MESSAGE_NOT_FOUND`, and so is a cancellation
once it has been sent or has failed for good, because it is then deleted: there is nothing left to replay. If the dispatcher is
sending that message at that moment, the retry waits up to 2 seconds for it to finish, then decides; a send that
takes longer makes the retry `503 TRY_AGAIN`, with the message left as it was, and the retry can simply be sent
again.

Where the inbox is off, as it is by default, the endpoint does not exist and this guarded update is the replay
path. It applies the same rule: it resets only a message that could be cured, and never a confirmation of an
appointment that has been cancelled or has already started.

```sql
UPDATE notification_messages m
SET status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error = NULL, failure_reason = NULL
FROM appointments a
WHERE m.id = '00000000-0000-0000-0000-000000000000'
  AND a.id = m.appointment_id
  AND m.status = 'FAILED'
  AND m.failure_reason IN ('DELIVERY_FAILED', 'DISPATCH_ERROR')
  AND NOT (m.kind = 'CONFIRMATION' AND (a.status = 'CANCELLED' OR a.starts_at <= now()));
```

It reports `UPDATE 1` when the message was replayed, and `UPDATE 0` when the guard refused it. The dispatcher picks
the message up within 2 seconds. Should a confirmation of a cancelled appointment ever be set PENDING by other
means, the dispatcher marks it `SUPERSEDED_BY_CANCELLATION` instead of sending it. A message to 082 555 0000 will
fail again, by design, and one AWS refused at once fails again at once until its cause is fixed.

## What a cancel leaves behind

A cancel deletes the booking in its own transaction, the moment the customer cancels
([BR-14](domain-rules.md#br-14)): the appointment and every confirmation SMS of it, whatever its status. No cancelled
booking stays in the database, and no cancellation SMS stays once it is final. Two things outlive the booking for a
while, each with no appointment, and a third where the daily cap is on:

- its cancellation SMS, PENDING, which holds the number and the text until the dispatcher sends it or gives up on
  it, within about 35 seconds while the dispatcher runs, or about 2 seconds where AWS refuses it for good, and then
  deletes it;
- its idempotency key, which holds no name or number, only an unkeyed SHA-256 of the request's details, until the
  key expires 24 hours after the booking and the hourly purge removes it, up to about an hour later, so a retry of
  that booking is `422 IDEMPOTENCY_KEY_REUSED` rather than a new booking;
- where the daily cap is on, one row in the cap's ledger for each SMS the booking was written, which holds a keyed
  fingerprint of the number and nothing that names the booking, until the ledger's hourly purge removes it, once it is
  24 hours old ([below](#the-daily-caps-ledger)).

The log line `cancel.deleted appointmentId=...` marks each cancel. To check that nothing else is left:

```sql
SELECT count(*) FROM appointments WHERE status = 'CANCELLED';
SELECT count(*) FROM notification_messages WHERE appointment_id IS NULL AND status <> 'PENDING';
SELECT count(*) FROM notification_messages WHERE appointment_id IS NULL AND created_at < now() - interval '5 minutes';
```

The first is 0, except for a booking cancelled before V6 or set CANCELLED by hand. Such a booking can be deleted as
a cancel would have, its messages first; its keys are kept, with no appointment, until they expire:

```sql
BEGIN;
DELETE FROM notification_messages
WHERE appointment_id IN (SELECT id FROM appointments WHERE status = 'CANCELLED');
DELETE FROM appointments WHERE status = 'CANCELLED';
COMMIT;
```

The second is always 0. The third is 0 while the dispatcher runs: a
cancellation still waiting after five minutes means the dispatcher has stopped, and the retention purge does not
reach a message with no appointment, so start the dispatcher again rather than deleting the row, or the customer is
never told.

## The daily cap's ledger

Where `BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP` is above 0, as in staging and production, every SMS written to a number outside
the demo block adds one row to `sms_recipient_sends` ([BR-17](domain-rules.md#br-17)), in the transaction that
writes the message. A row holds two things: `recipient_fingerprint`, HMAC-SHA256 of the number's E.164 form under
`BRANCHBOOK_SMS_RECIPIENT_CAP_KEY`, in lower-case hex, and `written_at`. There is no number, name, reference, text,
or link to a booking or a message. The cap counts a fingerprint's rows of the last 24 hours, so a confirmation a
cancel deleted, or a cancellation the dispatcher deleted once final, still counts; an SMS the cap stopped adds no
row. With the cap off the table stays empty.

To see how near numbers are to the cap, without seeing a number:

```sql
SELECT left(recipient_fingerprint, 12) AS fingerprint, count(*) AS sms, max(written_at) AS last_sms
FROM sms_recipient_sends
WHERE written_at > now() - interval '24 hours'
GROUP BY recipient_fingerprint
ORDER BY sms DESC
LIMIT 20;
```

A fingerprint whose `sms` has reached the cap gets no SMS until its oldest row is 24 hours old. Deleting a
fingerprint's rows lifts the cap for that number at once; nothing else in the application reads them.

The ledger's purge deletes the rows once they are 24 hours old: a minute after start-up, then every hour, whether
the cap is on or not. Each run writes one INFO line with how many rows it deleted, and nothing else:

```text
cap_ledger.purged count=12
```

`count=0` is normal while the cap is off or the demo is quiet. No such line at all means the job is not running. Right
after a run this is 0:

```sql
SELECT count(*) FROM sms_recipient_sends WHERE written_at <= now() - interval '24 hours';
```

**The key.** With no `BRANCHBOOK_SMS_RECIPIENT_CAP_KEY`, the application makes a random key at each start, and while
the cap is on the start-up log says so once, at WARN:

```text
Recipient daily cap: branchbook.sms.recipient-cap-key is not set, so the ledger's fingerprints use a random key made at start-up, and every number's count starts again from zero whenever the application restarts.
```

The rows from before a restart then match no number, so they stop counting, and the purge removes them within a day.
To keep counts across restarts, set the variable from a secret store to at least 32 characters, such as the output
of `openssl rand -base64 48`; a shorter one stops start-up. Staging and production do: each task gets the key from
the `<prefix>/sms-cap-key` secret in AWS Secrets Manager. The key is never logged or printed. Changing it has the same
effect as a restart: every count starts again.

## Is a reference blocked?

Five wrong lookups or cancels of a reference in an hour block it with `429 LOOKUP_RATE_LIMITED`
([BR-13](domain-rules.md#br-13)). The counts live in `lookup_attempts` (V8), shared by every instance. Blocking a
reference writes one WARN line, which names no reference:

```text
lookup.blocked failures=5 until=2026-10-27T10:15:00Z
```

This counts the references blocked now, without printing any of them:

```sql
SELECT count(*) FROM lookup_attempts WHERE failures >= 5 AND closes_at > now();
```

A block lifts by itself when its window closes, an hour after the first wrong try. A settle that failed writes
`lookup.count_not_settled cause=…` at WARN; that try holds its place for 30 seconds at most. The purge deletes closed
windows an hour after start-up, then every hour, and writes one INFO line with how many it deleted:

```text
lookup_attempts.purged count=3
```

<a id="did-the-48-hour-purge-run"></a>

## Did the retention purge run?

Staging and production keep every booking until its appointment is over, then delete it, with its messages and idempotency
keys ([SECURITY.md](../SECURITY.md#personal-information-popia)). A booking for a future date is never touched. The retention job does it: a minute after start-up, then
every hour. Each run writes one INFO line with what it deleted, and nothing else:

```text
retention.purged appointments=12 messages=12 keys=3
```

`appointments=0 messages=0 keys=0` means no appointment had ended since the last run. No such line at all means the job is not running:
`branchbook.retention.enabled` is not true in that deployment. `keys` is usually lower than `appointments`: the
hourly key purge has normally removed a key, 24 hours after its booking, by the time the appointment is over. A cancelled booking is never
counted here: the cancel deleted it at once. A purged booking is gone for its customer too: a lookup or a cancel of it is `404 APPOINTMENT_NOT_FOUND`, even when the purge deletes it while
the cancel runs, never a `500` ([errors.md](errors.md)).

A WARN line means a batch of up to 500 appointments could not be deleted and was rolled back:

```text
retention.batch_failed appointments=500 messages=500 keys=12 cause=UncategorizedSQLException
```

The counts are what the run had already deleted, and stay deleted; the rest wait for the next run, an hour later.
A batch fails when another transaction holds one of its message or key rows for longer than the 2-second
`lock_timeout`, or when one of its statements runs past the 5-second `statement_timeout`; `cause` is the exception's
type, and a lock timeout shows as `UncategorizedSQLException`. The dispatcher is an unlikely culprit: it locks only
PENDING messages, and a booking's messages have long been sent or failed by the time its appointment is over. One WARN
needs nothing. If the same WARN comes back hour after hour, look at what is holding locks or running long while the
job runs:

```sql
SELECT pid, state, wait_event_type, now() - xact_start AS open_for, left(query, 80) AS query
FROM pg_stat_activity
WHERE datname = current_database() AND state <> 'idle'
ORDER BY xact_start;
```

An appointment that another transaction held at that moment is skipped without a WARN and taken on the next run.

To see what the next run will delete, on the database:

```sql
SELECT count(*) FROM appointments WHERE ends_at <= now();
```

Right after a run it is 0, apart from what a failed batch or a held appointment left for the next run.

### Switch it on

It is off unless the deployment sets `BRANCHBOOK_RETENTION_ENABLED=true`, which staging's and production's
configuration files do (`appEnvironment` in [infra/config](../infra/config)). `BRANCHBOOK_RETENTION_AFTER_END` keeps
a booking for longer after its appointment ends, as an ISO-8601 duration (`PT0S` by default); a negative value stops start-up. The query above then needs that delay
subtracted from `now()`. To watch the job locally, start the API with the variable set and look for the line a minute later:

```bash
BRANCHBOOK_RETENTION_ENABLED=true make dev-api
```

`make up` leaves it off, so a local stack keeps its bookings until you delete them.

## Follow one request

Every response carries `X-Correlation-Id`, and every problem body carries `correlationId`. The same id prefixes
every log line written for that request, and the dispatcher's lines for its message carry it too, so a booking and
its delivery read as one story:

```bash
docker compose logs app | grep '<correlation id>'
```

In staging and production the lines are in the log group `/<prefix>/app`, one JSON object each under the `prod`
profile; CloudWatch Logs Insights finds a request's lines with `filter @message like '<correlation id>'`.

Log lines name appointments and messages by id. They never hold a name, a number in any form, or a reference
([BR-19](domain-rules.md#br-19)), so a log can be shared to diagnose a problem.

## Read the metrics

`/actuator/prometheus` serves every meter in Prometheus's text format, with no personal data in it. Locally it is on
the app's own port. Under the `prod` profile the actuator moves to port 8081, which the load balancer never routes,
and staging and production expose only `health` and `info` there; their meters reach CloudWatch instead, in the
namespace `BranchBook/<environment>`, where the [alarms](#alarms) and each environment's dashboard read them
([infra/README.md](../infra/README.md#observability-and-fault-injection)). Two are BranchBook's own (FR-30; the full mapping is in [api/README.md](../api/README.md#metrics)):

```bash
curl -s localhost:8080/actuator/prometheus | grep -E '^(outbox_pending|appointments_booked_total)'
```

**`outbox_pending`** is how many messages were still PENDING at the end of the dispatcher's last tick, counted once
per tick, every 2 seconds. It includes messages backing off after a refusal. Only the dispatcher's ticks move it, so
it says nothing about a dispatcher that is not running: the SQL count below is the ground truth.

| Reading | Means | Do |
|---|---|---|
| `NaN` | No tick has run since the app started: the dispatcher is switched off, and every confirmation stays PENDING. | Check that `BRANCHBOOK_DISPATCHER_ENABLED` is not `false`, and the SQL count below. |
| 0, or back to 0 between bursts of bookings | Delivery is keeping up. | Nothing. |
| A few that clear within 30 seconds | Messages backing off after a refusal (2, 4, 8, 16 seconds). A number ending 0000 does this on purpose, so the demo data's third booking shows here briefly after `make up`. | Nothing; a message refused five times, or once for good, turns FAILED and leaves the count. |
| Climbing, and never back down | The dispatcher runs but is not keeping up. | Check the log for `outbox.tick_failed` or `outbox.claim_failed`. The SQL below says which messages wait and why. |
| Frozen, at 0 or any other value, while `appointments_booked_total{outcome="confirmed"}` rises | The dispatcher's thread has stopped after at least one tick, or only the count fails: then the log has `outbox.pending_not_counted` and the gauge keeps its last value. | Compare it with the SQL count below; then as for a climbing count. |

```sql
SELECT status, count(*) AS messages, min(next_attempt_at) AS next_due
FROM notification_messages
GROUP BY status;
```

**`appointments_booked_total{outcome}`** counts every booking request by how it ended. Read it as a rate, such as
`rate(appointments_booked_total[5m])`, not as a total: the counters start at 0 when the app starts.

- `slot_full` rising: customers lose seats to each other while booking, so demand is outrunning the seats.
- `try_again` rising: bookings wait more than 2 seconds for a lock, 3 for a connection or 5 for a statement, or hit a
  deadlock or serialization conflict twice. Look for `Temporary database failure` in the log, whose SQLSTATE says
  which (`55P03` a lock, `57014` a statement, `40P01` or `40001` a conflict), and `Database unavailable` for a
  connection.
- `validation` rising while `confirmed` falls: a client is sending requests the API refuses;
  [errors.md](errors.md) explains each code.
- A 500 is not an outcome and is not counted here, unless the booking had already committed, when it counts as
  `confirmed`: look for `status="500"` in `http_server_requests_seconds` and for `Unexpected failure` in the log.

## Alarms

Staging and production each have these alarms, on the environment's SNS topic, which e-mails the `ALERT_EMAIL` of
that GitHub environment where one is set. What each watches, its threshold and how missing data counts are in
[infra/README.md](../infra/README.md#observability-and-fault-injection); the thresholds themselves are in the
`alarms` and `monitoring` sections of [infra/config/](../infra/config). Each environment's dashboard,
`branchbook` or `branchbook-staging` in CloudWatch, shows every alarm beside the figures it reads. The logs are in
`/<prefix>/app` ([Follow one request](#follow-one-request)).

| Alarm | What it means | First three checks | The fix |
|---|---|---|---|
| `ServerErrors` | The app answered 5xx to 5 or more requests in 5 minutes. During a deployment it also rolls the deployment back. | 1. The ECS service's events: is a deployment running or rolling back? 2. The log for `Unexpected failure`, `Database unavailable` and `Temporary database failure`. 3. The database alarms and the RDS instance's events (a failover?). | A deployment that caused it is rolled back by ECS. A database cause: see its alarm. Otherwise [roll back by hand](#roll-back-by-hand). |
| `NoHealthyTask` | No task has passed `/readyz` for 3 minutes: nothing is serving. | 1. The service's events and the stopped tasks' reasons. 2. The newest task's start-up log (a refused IAM database sign-in, a setting `ProductionProfileGuard` refuses). 3. The RDS instance's status: `/readyz` needs the database. | A bad image: [roll back by hand](#roll-back-by-hand). A database outage: wait for the failover, or [restore](#restore-the-database-to-a-point-in-time). |
| `DatabaseStorage` | The database has less than 2 GB free. Storage grows by itself up to `maxAllocatedStorageGiB`. | 1. Free storage on the dashboard, and whether it fell suddenly. 2. That the retention purge runs (`retention.purged` lines; [above](#did-the-retention-purge-run)). 3. The current allocation against `maxAllocatedStorageGiB` in the config. | Raise `database.maxAllocatedStorageGiB` and deploy; fix the purge if it stopped. |
| `OutboxBacklogAge` | A message has waited more than 300 seconds to be sent. | 1. `SmsCircuitOpen` and `DispatcherStalled`. 2. The log's `outbox.retry` codes ([the AWS codes](#what-the-aws-codes-mean)). 3. The SQL count in [Read the metrics](#read-the-metrics). | As for the alarm or code it points to. Nothing is lost: the messages stay PENDING. |
| `OutboxNewFailures` | One or more messages turned FAILED in 5 minutes and will not be sent without an operator. The simulated gateway's deliberate failures, for numbers ending 0000, are left out. | 1. The log's `outbox.failed` lines and their codes. 2. One number or many? 3. For `RECIPIENT_DAILY_CAP`, [the ledger](#the-daily-caps-ledger). | Fix the cause the code names, then [replay](#replay-a-failed-message). |
| `DispatcherStalled` | No dispatcher tick for over 60 seconds, for 2 minutes; or the app stopped exporting metrics, since missing data counts. | [Below](#dispatcher-stalled). | Restart one task. |
| `SmsCircuitOpen` | The SMS circuit breaker is open: the provider is failing or slow, and the dispatcher holds messages back. | [Below](#sms-circuit-open). | Usually none: it closes by itself. |
| `ResponseTimeP99` | The slowest 1% of requests took over 1.5 seconds for 15 minutes. | 1. `DatabaseCpu` and `DatabaseConnections`. 2. `ServiceCpu` and the running task count. 3. Requests and WAF blocks on the dashboard: a surge, or one address? | Let autoscaling add tasks; raise `service.maxTasks` within the connection budget, or `database.instanceClass`. |
| `DatabaseCpu` | The database's CPU averaged over 80% for 15 minutes. | 1. Requests on the dashboard. 2. `try_again` bookings ([Read the metrics](#read-the-metrics)). 3. A retention batch failing hour after hour ([above](#did-the-retention-purge-run)). | A larger `database.instanceClass`; in production the change is applied through the Multi-AZ standby. |
| `DatabaseConnections` | More than 80% of the connection budget (60 of 75) is in use. | 1. Running tasks against `service.maxTasks`. 2. A migration task still running. 3. A deployment with old and new tasks side by side. | `config.ts` keeps the most the service can open inside the budget, so a breach means something else connects: find and stop it. |
| `DatabaseMemory` | The database had under 128 MiB of freeable memory for 15 minutes. | 1. `DatabaseConnections`. 2. `DatabaseCpu`. 3. The trend since the last deployment. | A larger `database.instanceClass`. |
| `ServiceCpu` | The tasks' CPU averaged over 85% for 15 minutes, beyond what autoscaling absorbed. | 1. Is the service at `service.maxTasks`? 2. Requests per task on the dashboard. 3. WAF's per-address blocks: one client driving it? | Raise `service.maxTasks` within the connection budget, or `service.cpu`. |
| `ServiceMemory` | A task used over 85% of its memory for 15 minutes. | 1. Tasks restarting? 2. Since which deployment? 3. The same on the other tasks? | Raise `service.memoryMiB`, or roll back the deployment that started it. |
| `CanaryFailing` | The outside-in canary failed two runs in a row: `/readyz` or the availability API did not answer 200 with a sane body. | 1. The canary's last run in the CloudWatch Synthetics console. 2. `EXPECT_PROD_PROFILE=1 EXPECT_CAPTCHA=1 scripts/smoke.sh <address>` from your machine. 3. `NoHealthyTask` and `ServerErrors`. | As for the alarm it points to; if only the canary fails, the domain, the certificate or WAF. |

### SMS circuit open

The dispatcher sends through a circuit breaker named `sms`. It opens when half of the last 10 calls failed (once at
least 5 were made) or all of them took over 8 seconds; a retryable refusal or a gateway that throws is a failure, a
permanent refusal is not ([configuration.md](configuration.md#notifications)). It logs one WARN line:

```text
outbox.circuit from=CLOSED to=OPEN failureRate=60.0 slowCallRate=0.0
```

While it is open the dispatcher claims no message, so every message stays PENDING and **no attempt is spent**. After
30 seconds it lets two messages through as probes; it closes if they pass, logged as `outbox.circuit from=HALF_OPEN
to=CLOSED`, and the waiting messages are sent. A provider outage needs no action.

1. **AWS's side.** The AWS Health Dashboard for AWS End User Messaging in af-south-1, and the `outbox.retry` codes in
   the log: `AWS_THROTTLED`, `AWS_INTERNAL` and `AWS_QUOTA_*` are AWS; `AWS_UNREACHABLE` is the task's route to AWS
   (the NAT gateways) or its credentials.
2. **The account's SMS limits.** The spend limit and quotas in the AWS End User Messaging console. A spent monthly
   limit fails messages at once (`AWS_QUOTA_MONTHLY_SPEND_LIMIT_REACHED_FOR_TEXT`), so it shows as
   `OutboxNewFailures` rather than here.
3. **How long it has been open.** `OutboxBacklogAge` follows after 5 minutes. A breaker that opens and closes over
   and over means calls are slow rather than failing.

Leave the gateway as it is: switching to `simulated` would mark the waiting messages SENT without sending them.

### Dispatcher stalled

`outbox.last.tick.age` is the time since the dispatcher last finished a tick, and the alarm also fires when a task
stops exporting it.

1. **Is a task running?** `NoHealthyTask` and the service's running count.
2. **The dispatcher's own lines.** Search the log for `outbox.`: `outbox.tick_failed` and `outbox.claim_failed` are
   its errors, and no `outbox.` line since bookings were last made means its thread has stopped.
3. **The export.** If ticks are logged and the alarm stays raised, the metrics are not reaching CloudWatch: look for
   CloudWatch errors in the same log, and check the task role may still call `cloudwatch:PutMetricData`.

The fix is to restart one task: in the ECS console, open the environment's service and stop one task; ECS starts a
replacement, and the other tasks keep serving. The PENDING messages are sent once a dispatcher ticks again.

## Run a fault-injection experiment

Each environment has two AWS FIS experiment templates: `<prefix>-stop-one-task`, and in production
`<prefix>-database-failover`. What each does, what to expect and the commands to start one are in
[infra/README.md](../infra/README.md#observability-and-fault-injection); what each rehearses is in
[resilience.md](resilience.md).

1. **Before.** Check that `ServerErrors` and `NoHealthyTask` are OK: they are the experiments' stop conditions, so
   FIS refuses to start, or stops, while either is raised. Run the smoke test against the environment
   ([Is it healthy?](#is-it-healthy)) and open its dashboard.
2. **During.** Start the experiment, then watch healthy tasks, 5xx and response times on the dashboard, and
   `aws fis get-experiment` for its state.
3. **After.** Run the smoke test again, note when the service was whole again, and record the date and the measured
   recovery in [resilience.md](resilience.md).

Run them on staging first. Staging runs one task, so stopping it shows recovery from zero rather than continuity.

## Restore the database to a point in time

RDS keeps automated backups for `database.backupRetentionDays` (14 days in production, 3 in staging) and can restore
to any second in that window, up to about five minutes ago. A restore always makes a **new** instance; the live one
is left as it was. This procedure has not been rehearsed yet: the restore drill in [resilience.md](resilience.md)
will time it.

1. **Find the live instance and its settings** (production shown; staging's stack is `BranchbookStagingData`):

   ```bash
   aws cloudformation describe-stack-resources --stack-name BranchbookData --region af-south-1 \
     --profile branchbook-admin --query "StackResources[?ResourceType=='AWS::RDS::DBInstance'].PhysicalResourceId"
   aws rds describe-db-instances --db-instance-identifier <id> --region af-south-1 --profile branchbook-admin \
     --query "DBInstances[0].{subnets:DBSubnetGroup.DBSubnetGroupName,groups:VpcSecurityGroups[].VpcSecurityGroupId,params:DBParameterGroups[0].DBParameterGroupName,latest:LatestRestorableTime}"
   ```

2. **Restore to a new instance** in the same subnet group, security group and parameter group, with IAM
   authentication on, so the tasks could reach it:

   ```bash
   aws rds restore-db-instance-to-point-in-time --region af-south-1 --profile branchbook-admin \
     --source-db-instance-identifier <id> --target-db-instance-identifier <id>-restored \
     --restore-time <UTC instant, e.g. 2026-11-02T09:15:00Z> \
     --db-subnet-group-name <subnets> --vpc-security-group-ids <groups> --db-parameter-group-name <params> \
     --enable-iam-database-authentication --multi-az --no-publicly-accessible --deletion-protection
   ```

   Leave out `--multi-az` and `--deletion-protection` for staging. The restored instance keeps the source's
   encryption key and master credentials.

3. **Point the stack at it.** The stack has no setting for this today: the tasks' endpoint, the master secret and the
   task role's `rds-db:connect` grant, which names the instance's resource ID and so changes with a restore, all come
   from the instance [data-stack.ts](../infra/lib/data-stack.ts) defines. Switching is therefore a change to that
   stack, deployed through the pipeline, and is what the drill is to settle. Where only some rows were lost, copying
   them back from the restored instance into the live one avoids the switch.

4. **Afterwards,** delete the instance that is no longer used, once nothing needs it; with deletion protection on,
   turn it off first.

## Roll back by hand

The pipeline rolls production back by itself when a deployment or its smoke test fails, after the same approval as a
deployment ([delivery.md](delivery.md#rollback-production-environment-production)). To roll back by hand, for example
when a release that passed its smoke test turns out to be wrong, redeploy the previous release: its commit's CDK app
with its image digest, as the pipeline does.

1. **Find the previous release.** `/<prefix>/release` names the commit and `/<prefix>/release-image` the digest of
   the release now recorded; their history holds the earlier ones:

   ```bash
   aws ssm get-parameter-history --name /branchbook/release-image --region af-south-1 --profile branchbook-admin \
     --query "Parameters[].[LastModifiedDate,Value]" --output text
   aws ssm get-parameter-history --name /branchbook/release --region af-south-1 --profile branchbook-admin \
     --query "Parameters[].[LastModifiedDate,Value]" --output text
   ```

2. **Deploy that commit with that digest,** from a checkout of the commit (a separate worktree keeps yours):

   ```bash
   git worktree add ../branchbook-rollback <commit> && cd ../branchbook-rollback/infra && npm ci
   AWS_PROFILE=branchbook-admin BRANCHBOOK_ENV=production BRANCHBOOK_IMAGE_DIGEST=<digest> \
   BRANCHBOOK_DEMO_ALERT_EMAIL=<the address in the environment's ALERT_EMAIL> \
     npx cdk deploy BranchbookData BranchbookApp
   ```

   Without `BRANCHBOOK_DEMO_ALERT_EMAIL` the deployment removes the alarms' e-mail subscription, which is the last
   thing wanted during an incident.

3. **Check it.** `EXPECT_PROD_PROFILE=1 EXPECT_CAPTCHA=1 scripts/smoke.sh https://branchbook.click`.

A hand deployment writes no release record, so `/<prefix>/release` still names the release it replaced, and the next
push to `main` deploys `main` over it: revert the bad commit on `main` too. The previous image runs against the
schema as the newer release left it, which is why every migration must be one
[a rollback can survive](#write-migrations-a-rollback-can-survive).

## Write migrations a rollback can survive

A rollback redeploys the previous image, but never undoes a migration: the schema stays as the newer release left it.
The previous image's migration task finds the newer migrations already applied and ignores them (Flyway's default
`ignoreMigrationPatterns` is `*:future`), and its service then validates its entities against that schema
(`ddl-auto: validate`). So every migration must be backward-compatible with the release before it:

- **Expand first.** Add a table, a nullable column, or a column with a default; add an index. The previous release
  never reads them, so it runs unchanged.
- **Contract a release later.** Drop or rename a column, tighten a constraint or change a type only once no release
  that a rollback could return to still reads it. A rename is an add, a copy and, later, a drop.
- **Keep data changes additive.** A backfill the previous release does not depend on is safe; one it would misread
  is not.
- **Grants come with the table.** The runtime role `branchbook_app` (V9) gets row access to new tables through default
  privileges; check a new migration leaves it no more than row access.

Before merging a migration, ask: would the release now in production start and work against this schema? If not,
split it.
