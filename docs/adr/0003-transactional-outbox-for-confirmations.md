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
