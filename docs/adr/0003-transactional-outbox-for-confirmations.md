# ADR-0003: The simulated confirmation is a transactional outbox

- **Status:** Accepted
- **Date:** 2026-10-22

## Context

The brief asks the system to communicate a simulated confirmation. Read as engineering, that means three things:

- compose the message a bank would really send;
- never lose it after the booking commits, and never send one for a booking that rolled back;
- deliver it through a channel abstraction whose last hop is simulated, with retries and a visible status.

## Decision

- The booking and cancel transactions insert `notification_messages` rows by a direct call through the
  `ConfirmationOutbox` port. The appointment and its message commit or roll back together.
- `NotificationDispatcher` runs every 2 seconds on its own single-thread scheduler. Each tick claims up to 20 due
  rows, each in its own transaction, with `FOR UPDATE SKIP LOCKED`. It sends each row through the `MessageGateway`
  port and marks it `SENT`. A failure is retried after 2, 4, 8 and 16 seconds, and the fifth failed attempt marks
  it `FAILED`, with a `failure_reason`.
- A cancellation supersedes a confirmation that has not been sent, so a customer never receives a confirmation
  after cancelling.
- `SimulatedSmsGateway` is the only adapter in v1. A recipient ending in `0000` always fails, so reviewers can
  watch the retries and the final `FAILED` state.
- An appointment's status never depends on delivery status.

## Alternatives considered

- **`@TransactionalEventListener(AFTER_COMMIT)` or a Spring event.** A crash between the commit and the send loses
  the message.
- **Sending on the request thread.** The customer waits on the provider, and a provider outage fails bookings.
- **An in-memory queue.** Lost on every restart.
- **Kafka or another broker.** There is no second consumer; the outbox table is where a relay to a broker would
  attach later.

## Consequences

- Delivery is at least once. The message id is what a real provider would deduplicate on.
- The process must stay running with CPU between requests. This rules out hosting that scales to zero or
  throttles idle CPU ([ADR-0006](0006-hosted-demo-on-aws-with-cdk.md)).
- A real SMS provider is one new `MessageGateway` implementation plus configuration.

## Notes

**2026-10-26.** Facts that have changed or need stating more precisely since the record was accepted. The decision
stands, with one change:

- **No outbox health indicator.** The plan named an `OutboxHealthIndicator` with the pending and failed counts.
  Health shows no component details in any environment, so it would show nothing; the simulated inbox (`GET
  /api/v1/messages`) and the SQL in [docs/runbook.md](../runbook.md) show the outbox instead.
- **The dispatcher's thread is private.** It runs on `outbox-1`, a single-thread scheduler owned by `DispatcherLoop`,
  not a scheduler bean: a `TaskScheduler` bean would replace Spring Boot's default scheduler and move the hourly key
  purge and the daily holiday check onto the dispatcher's thread. `branchbook.dispatcher.enabled` switches the loop
  off; the tests do, and call `NotificationDispatcher.runOnce` with the instant they want, so none sleeps.
- **What booking hands over.** `ConfirmationOutbox` takes a `Confirmation` value: the appointment's id, reference,
  start and zone, the recipient, and the branch's and the service's SMS names. So `notification` reads nothing from
  `catalogue` (M3).
- **A row whose outcome cannot be recorded** becomes `FAILED` with `DISPATCH_ERROR` and `last_error` `MARK_FAILED`,
  in a transaction of its own, so it is sent at most once more and the rest of the batch carries on
  (`NotificationDispatcherIT.poisonedRowDoesNotBlockTheBatch`). A gateway that throws counts as a refusal and is
  tried again.
- **The failing demo number is 082 555 0000.** About 30 seconds of backoff after it is booked, its confirmation is
  `FAILED` with `DELIVERY_FAILED` after five attempts, and the appointment stays `CONFIRMED`.
- **Superseding arrives with cancellation.** In v1 as built no message is ever superseded; the rule arrives with the
  Tier 2 cancel (BR-14, BR-24).

**2026-10-27, real SMS.** The outbox, the dispatcher and the port stand; the last hop is no longer always simulated.

- **A real gateway, where a deployment asks.** `branchbook.sms.gateway` is `simulated` by default, as locally and in
  the tests. Where it is `aws` (`BRANCHBOOK_SMS_GATEWAY=aws`, the hosted demo), `RoutingSmsGateway` sends every number
  outside the published demo block, 082 555 0000 to 0199, a real SMS through `AwsSmsGateway`, AWS End User Messaging
  SMS in af-south-1, which the CDK stack allows the task role to call; the block stays simulated, because anyone may
  type it. As the Consequences expected, the provider is one more `MessageGateway` and configuration, built with the
  AWS SDK for Java v2 and the JDK's own HTTP client ([architecture.md, section 5](../architecture.md#5-the-confirmation-sms)).
- **Refusals are fixed codes.** The SDK does not retry, so the dispatcher's backoff is the only retry policy, and it
  applies only to a refusal that sending again may cure; a refusal records the exception's type and typed reason,
  such as `AWS_THROTTLED`, never AWS's message
  ([docs/runbook.md](../runbook.md#what-the-aws-codes-mean)). AWS's `MessageId` is the provider id. Delivery is still
  at least once: a call that times out after AWS took the message is sent again.
- **`RECIPIENT_DAILY_CAP` is produced.** Where `branchbook.sms.recipient-daily-cap` is above 0, a message past the cap
  for its number in 24 hours is written FAILED with that reason and never sent; the booking or the cancel succeeds
  ([BR-17](../domain-rules.md#br-17), `RecipientDailyCapIT`).

**2026-10-27, a cancel deletes the booking.** A cancel no longer supersedes its booking's unsent confirmation: in one
transaction it deletes every confirmation of the appointment, writes the cancellation SMS and deletes the appointment,
so a customer still never receives a confirmation after cancelling. The dispatcher deletes the cancellation SMS once it
is sent or has failed for good, and marks a confirmation `SUPERSEDED_BY_CANCELLATION` only for an appointment
cancelled before this change, or by hand ([BR-14](../domain-rules.md#br-14), [BR-24](../domain-rules.md#br-24)).

**2026-10-27, a permanent refusal is not retried.** A refusal the gateway says sending again cannot cure, such as
AWS's for a number the SMS sandbox has not verified, marks the message `FAILED` with `DELIVERY_FAILED` on that
attempt, about 2 seconds after the booking rather than after the backoff; a refusal it may cure still backs off 2, 4,
8 and 16 seconds to a fifth-attempt `FAILED`. Each refusal says which it is, `MessageGateway.Refused`, and the rule is
recorded in [BR-17](../domain-rules.md#br-17).
