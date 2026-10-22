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
