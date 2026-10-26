# Runbook

How to look after a running BranchBook: whether it is healthy, what happened to a message, how to replay one that
failed, and how to follow one request through the logs. The commands are for the local stack that `make up` starts;
`make psql` opens a SQL prompt on its database. Every number in this file is a synthetic demo number.

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
the message up within 2 seconds. A message to 082 555 0000 will fail again, by design.

## Follow one request

Every response carries `X-Correlation-Id`, and every problem body carries `correlationId`. The same id prefixes
every log line written for that request, and the dispatcher's lines for its message carry it too, so a booking and
its delivery read as one story:

```bash
docker compose logs app | grep '<correlation id>'
```

Log lines name appointments and messages by id. They never hold a name, a number in any form, or a reference
([BR-19](domain-rules.md#br-19)), so a log can be shared to diagnose a problem.
