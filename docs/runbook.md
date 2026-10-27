# Runbook

How to look after a running BranchBook: whether it is healthy, what happened to a message, what an AWS refusal
means and how to let the hosted demo send real SMS, how to replay one that failed, what the daily cap's ledger holds,
whether the hosted demo's retention purge ran, and how to follow one request through the logs. The commands
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
([BR-17](domain-rules.md#br-17)). The simulated inbox lists the newest messages of every booking, recipients redacted,
and references too, as `APT-****-****`, where `BRANCHBOOK_INBOX_REDACT_REFERENCES` is on (it is off in `make up`);
each message's `id` identifies it either way:

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

Where `BRANCHBOOK_SMS_GATEWAY=aws`, as on the hosted demo, every number outside the published demo block, 082 555 0000
to 0199, is sent through AWS End User Messaging SMS, and the start-up log has one line naming the gateway, the
region and whether a protect configuration and a daily cap are set. A refusal is retried like any other, so a
message refused five times is FAILED with `DELIVERY_FAILED`, and `lastError` holds the last code. Each code is the
exception's type and, where AWS gives one, its reason; AWS's message is never kept, because it can quote the number.

| `lastError` | What happened | What to do |
|---|---|---|
| `AWS_CONFLICT_DESTINATION_PHONE_NUMBER_NOT_VERIFIED` | The account is in the SMS sandbox and the number is not a verified destination. | Verify the number, or leave the sandbox (below). |
| `AWS_CONFLICT_DESTINATION_COUNTRY_BLOCKED_BY_PROTECT_CONFIGURATION` | The protect configuration does not allow the destination's country. | Nothing, for a number outside South Africa; otherwise check the protect configuration. |
| `AWS_CONFLICT_DESTINATION_PHONE_NUMBER_OPTED_OUT` | The recipient replied STOP, or is on the account's opt-out list. | Nothing: AWS will not send to them. |
| `AWS_QUOTA_MONTHLY_SPEND_LIMIT_REACHED_FOR_TEXT` | The account's monthly SMS spend limit is used up; nothing more is sent this month. | Raise the limit through the Support case below, or wait for the next month. |
| `AWS_QUOTA_` and another reason | Another AWS quota is reached. | The reason names it. |
| `AWS_VALIDATION_PRICE_OVER_THRESHOLD` | The message would cost more than `BRANCHBOOK_SMS_AWS_MAX_PRICE` (US$0.20 by default). | Raise the setting only if the price is expected. |
| `AWS_VALIDATION_` and another reason | AWS refused the request, for example a setting it does not accept. | The reason names it; check the `BRANCHBOOK_SMS_AWS_*` settings. |
| `AWS_ACCESS_DENIED` | The task role may not call `sms-voice:SendTextMessage`, or the account is disabled for SMS. | Check the task role's policy in the CDK app. |
| `AWS_RESOURCE_NOT_FOUND` | A protect configuration, configuration set or origination identity named in the settings does not exist. | Correct the setting. |
| `AWS_THROTTLED`, `AWS_INTERNAL` | AWS is busy or failed; the next attempt usually succeeds. | Nothing, unless every message shows it. |
| `AWS_UNREACHABLE` | The SDK could not complete the call: the network, a 10-second timeout, or no credentials or region. | Check the task's network route to AWS and its role. |
| `AWS_ERROR` | Any other AWS error. | Search the AWS Health Dashboard; the code carries nothing more. |

A message AWS accepted is SENT with AWS's `MessageId` as its provider id. SENT means AWS took it, not that the phone
received it: South Africa supports no sender IDs, so with no origination identity AWS sends over a shared route, best
effort.

### Let the hosted demo send real SMS

The account owner does these steps in the AWS console, in Cape Town (af-south-1); the CDK app creates the protect
configuration, the task role's permission and the settings, but not these.

1. **In the sandbox, verify each number that should receive SMS.** A new account is in the SMS sandbox: it sends only
   to verified destination numbers, at most 10, and spends at most US$1 a month. In AWS End User Messaging SMS, open
   *Phone numbers > Verified destination numbers* (or the sandbox page), add the number, and enter the code AWS texts
   to it. A booking for any other number is FAILED with `AWS_CONFLICT_DESTINATION_PHONE_NUMBER_NOT_VERIFIED` after
   five attempts.
2. **To text any South African number, open a Support case for production access.** In the same console, request
   production access (a case with AWS Support) for af-south-1, naming the use (transactional appointment
   confirmations), the expected volume and South Africa as the only destination. In the same case, or another, ask
   for the monthly SMS spend limit the demo needs; the account's own limit can then be set up to it. Until AWS
   answers, the sandbox's limits stand.
3. **Check the start-up log** after the next deployment: `SMS gateway: AWS End User Messaging SMS in af-south-1 ...`,
   with a protect configuration and a daily cap set.

To stop real SMS at once, set `BRANCHBOOK_SMS_GATEWAY=simulated` and redeploy; every message then goes to the
simulated gateway.

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
fail again, by design.

## What a cancel leaves behind

A cancel deletes the booking in its own transaction, the moment the customer cancels
([BR-14](domain-rules.md#br-14)): the appointment and every confirmation SMS of it, whatever its status. No cancelled
booking stays in the database, and no cancellation SMS stays once it is final. Two things outlive the booking for a
while, each with no appointment:

- its cancellation SMS, PENDING, which holds the number and the text until the dispatcher sends it or gives up on
  it, within about 35 seconds while the dispatcher runs, and then deletes it;
- its idempotency key, which holds no personal data, until the hourly key purge removes it 24 hours after the
  booking, so a retry of that booking is `422 IDEMPOTENCY_KEY_REUSED` rather than a new booking;
- where the daily cap is on, one row in the cap's ledger for each SMS the booking was written, which holds a keyed
  fingerprint of the number and nothing that names the booking, until the ledger's purge removes it 24 hours after
  its SMS ([below](#the-daily-caps-ledger)).

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

Where `BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP` is above 0, as on the hosted demo, every SMS written to a number outside
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

**The key.** With no `BRANCHBOOK_SMS_RECIPIENT_CAP_KEY`, as on the hosted demo today, the application makes a random
key at each start, and while the cap is on the start-up log says so once, at WARN:

```text
Recipient daily cap: branchbook.sms.recipient-cap-key is not set, so the ledger's fingerprints use a random key made at start-up, and every number's count starts again from zero whenever the application restarts.
```

The rows from before a restart then match no number, so they stop counting, and the purge removes them within a day.
To keep counts across restarts, set the variable from a secret store to at least 32 characters, such as the output
of `openssl rand -base64 48`; a shorter one stops start-up. The key is never logged or printed. Changing it has the
same effect as a restart: every count starts again.

<a id="did-the-48-hour-purge-run"></a>

## Did the retention purge run?

The hosted demo keeps every booking until its appointment is over, then deletes it, with its messages and idempotency
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

It is off unless the deployment sets `BRANCHBOOK_RETENTION_ENABLED=true`, which only the hosted demo's ECS task
definition does. `BRANCHBOOK_RETENTION_AFTER_END` keeps a booking for longer after its appointment ends, as an
ISO-8601 duration (`PT0S` by default); a negative value stops start-up. The query above then needs that delay
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

Log lines name appointments and messages by id. They never hold a name, a number in any form, or a reference
([BR-19](domain-rules.md#br-19)), so a log can be shared to diagnose a problem.

## Read the metrics

`/actuator/prometheus` serves every meter in Prometheus's text format, on the app's own port, with no personal data
in it. Two are BranchBook's own (FR-30; the full mapping is in [api/README.md](../api/README.md#metrics)):

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
| A few that clear within 30 seconds | Messages backing off after a refusal (2, 4, 8, 16 seconds). A number ending 0000 does this on purpose, so the demo data's third booking shows here briefly after `make up`. | Nothing; a message refused five times turns FAILED and leaves the count. |
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
