# Runbook

How to look after a running BranchBook: whether it is healthy, what happened to a message, how to replay one that
failed, whether the hosted demo's 48-hour purge ran, and how to follow one request through the logs. The commands
are for the local stack that `make up` starts; `make psql` opens a SQL prompt on its database. Every number in this
file is a synthetic demo number.

## Is it healthy?

| Check | Command | Healthy |
|---|---|---|
| The process | `curl -s localhost:8080/livez` | `{"status":"UP"}`. It ignores the database on purpose, so a database outage never restarts the app. |
| Ready for traffic | `curl -s localhost:8080/readyz` | `{"status":"UP"}`: started, and PostgreSQL answers. Docker and the load balancer use it. |
| The whole stack | `make smoke` | `All checks passed`. It writes nothing. |
| The race for the last seat | `make demo-409` | `Exactly one booking won the seat`. It books one appointment, so only on a local stack. |

## What happened to a message?

The dispatcher delivers PENDING messages every 2 seconds, on its own thread, `outbox-1`
([BR-17](domain-rules.md#br-17)). The simulated inbox lists the newest messages of every booking, recipients redacted:

```bash
curl -s 'localhost:8080/api/v1/messages?limit=20'
```

A message is in one of three states:

- **PENDING**: waiting for its next attempt at `nextAttemptAt`. A refused attempt is retried after 2, 4, 8 and 16
  seconds; `attempts` and `lastError` say how far it got.
- **SENT**: the gateway accepted it at `sentAt`.
- **FAILED**: it will not be tried again on its own. `failureReason` says why:
  - `DELIVERY_FAILED`: the gateway refused all five attempts. The simulated gateway refuses every number ending in
    `0000`, such as 082 555 0000, so this is how a failed delivery is shown.
  - `DISPATCH_ERROR` with `lastError` `MARK_FAILED`: the gateway was called, but the outcome could not be recorded,
    so the message was set FAILED rather than risk sending it again and again. It may have been delivered once.
  - `SUPERSEDED_BY_CANCELLATION`: a confirmation that was still PENDING or FAILED when the customer cancelled. The
    cancellation SMS replaces it, and it is not sent from then on ([BR-24](domain-rules.md#br-24)). Its `lastError`
    is kept: if it is `MARK_FAILED`, the confirmation was handed to the gateway once before the cancel and may have
    been delivered.

An appointment never depends on its messages: a FAILED confirmation leaves the booking CONFIRMED.

The same facts in SQL:

```sql
SELECT id, kind, status, attempts, next_attempt_at, failure_reason, last_error, sent_at
FROM notification_messages
ORDER BY created_at DESC
LIMIT 20;
```

## Replay a failed message

Until the retry endpoint ships (Tier 2), this guarded update is how a FAILED message is replayed. It resets only a
message that could be cured, and never a confirmation of an appointment that has been cancelled or has already
started. Put the message's id in place of the example:

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
fail again, by design.

## Did the 48-hour purge run?

The hosted demo deletes every booking 48 hours after it was made, with its messages and idempotency keys
([SECURITY.md](../SECURITY.md#personal-information-popia)). The retention job does it: a minute after start-up, then
every hour. Each run writes one INFO line with what it deleted, and nothing else:

```text
retention.purged appointments=12 messages=12 keys=3
```

`appointments=0 messages=0 keys=0` means nothing was old enough. No such line at all means the job is not running:
`branchbook.retention.enabled` is not true in that deployment. `keys` is usually lower than `appointments`: the
hourly key purge has normally removed a key by the time its booking is 48 hours old.

A WARN line means a batch of up to 500 appointments could not be deleted and was rolled back:

```text
retention.batch_failed appointments=500 messages=500 keys=12 cause=UncategorizedSQLException
```

The counts are what the run had already deleted, and stay deleted; the rest wait for the next run, an hour later.
A batch fails when another transaction holds one of its message or key rows for longer than the 2-second
`lock_timeout`, or when one of its statements runs past the 5-second `statement_timeout`; `cause` is the exception's
type, and a lock timeout shows as `UncategorizedSQLException`. The dispatcher is an unlikely culprit: it locks only
PENDING messages, and a booking's messages have long been sent or failed by the time it is 48 hours old. One WARN
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
SELECT count(*) FROM appointments WHERE created_at <= now() - interval '48 hours';
```

Right after a run it is 0, apart from what a failed batch or a held appointment left for the next run.

### Switch it on

It is off unless the deployment sets `BRANCHBOOK_RETENTION_ENABLED=true`, which only the hosted demo's ECS task
definition does. `BRANCHBOOK_RETENTION_MAX_AGE` changes the 48 hours (`PT48H`); anything under `PT24H` stops
start-up. To watch the job locally, start the API with the variable set and look for the line a minute later:

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

Log lines name appointments and messages by id. They never hold a name, a number in any form, or a reference
([BR-19](domain-rules.md#br-19)), so a log can be shared to diagnose a problem.
