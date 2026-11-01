# BranchBook in ten minutes

*For anyone with ten minutes: what it is, how to run it, the six decisions that shape it, and where to look.
Everything here is said at more length elsewhere; the links go there.*

## What it is

Customers of a fictional South African bank book a branch appointment and receive an SMS confirmation. One Spring
Boot jar (Java 25) serves a React 19 app and a REST API over PostgreSQL 17. Run locally it texts nothing; production
texts the number you book with.

## Run it

Docker is the only prerequisite.

```bash
git clone https://github.com/KatlehoKomeke/Appointment-Booking-System.git
cd Appointment-Booking-System
make up       # build the image, start the app and PostgreSQL, wait until both are healthy
make smoke    # check the running app over HTTP
```

Open http://127.0.0.1:8080, book with a demo number such as `082 555 0101`, and watch the confirmation page follow
the SMS from Sending to Sent. The number `082 555 0000` always fails delivery, so the page shows that path too. The
simulated inbox at `/messages` shows every message. `make demo-409` races twenty bookings for one seat and prints
`Booked: 1, refused with 409: 19`. `make down` deletes everything.

The same image runs in AWS Cape Town twice, promoted by digest: staging, at https://stg.branchbook.click, takes only
the demo numbers and texts nothing; production, at https://branchbook.click, sends any South African number outside
the demo block `082 555 0000` to `0199` a real SMS. Both keep the inbox off, ask for a CAPTCHA before a booking or a
cancel, and delete each booking once its appointment is over.

## The design in six sentences

1. Four modules, `catalogue`, `scheduling`, `booking` and `notification`, each with `api`, `application`, `domain`
   and `infrastructure` layers, in one deployable; ArchUnit fails the build on a cycle, a controller that reaches a
   database adapter, or Spring in the domain.
2. A booking is one `READ COMMITTED` transaction with ten numbered steps: claim the `Idempotency-Key`, validate the
   slot, lock the customer's mobile number, pre-check their other bookings, lock the service's row at the branch,
   take the lowest free seat, write the appointment, write its confirmation SMS, complete the key.
3. Two `btree_gist` exclusion constraints make an overlapping seat, or an overlapping booking for one customer,
   impossible from any code path; the transaction's lock order makes the loser's `409` deterministic.
4. The SMS is a row in an outbox table, written in the booking transaction, so it commits with the booking or not
   at all; a dispatcher thread delivers it in its own transaction per message, with backoff, and gives up as
   `FAILED` after five attempts, or at once on a refusal that sending again cannot cure.
5. There is no login and no ID number: a reference plus the last four digits of the mobile number opens a booking,
   every mismatch is the same `404`, five wrong tries block the reference for an hour, and no log holds a name,
   a number or a reference.
6. On AWS, CDK defines everything from one validated file per environment: WAF with a CAPTCHA and per-address
   limits in front of an HTTPS load balancer, ARM64 Fargate tasks in private subnets that scale out, RDS PostgreSQL
   (Multi-AZ in production) in subnets with no internet route, which the tasks sign in to with IAM rather than a password,
   migrations run as a deployment step, and GitHub signing in through OpenID Connect rather than stored keys.

## Six decisions and what they cost

| Decision | Why | What it costs | Recorded in |
|---|---|---|---|
| A modular monolith, not services or a message broker | One team, one bounded context, one database: the isolation that matters is transaction and lock scope, not network hops | A second consumer of booking events needs a broker added at the outbox, where it would attach | [ADR-0001](adr/0001-modular-monolith.md), [ADR-0003](adr/0003-transactional-outbox-for-confirmations.md) |
| Double booking is stopped in PostgreSQL, with a fixed lock order in code | A constraint holds whatever writes the row; the lock order turns a race into one `201` and a `409`, never a deadlock | Depends on `btree_gist`; per-service capacity assumes dedicated desks, which the README states | [ADR-0002](adr/0002-double-booking-prevention.md), [BR-7](domain-rules.md#br-7), [BR-8](domain-rules.md#br-8), [BR-22](domain-rules.md#br-22) |
| A transactional outbox with one dispatcher thread, one transaction per message | No booking without its SMS, no SMS without its booking; one bad row never holds up the rest | At-least-once: a crash between the send and its mark can repeat one SMS | [ADR-0003](adr/0003-transactional-outbox-for-confirmations.md), [BR-16](domain-rules.md#br-16), [BR-17](domain-rules.md#br-17) |
| No login and no one-time PIN; a reference and the last four digits open a booking, and a CAPTCHA guards each booking and cancel | A sign-in in front of a demo turns away the people it is for; it collects the least the brief allows; a PIN costs an SMS and invites SMS pumping | A demo number's last four digits are public, so deployments keep the inbox off; a person who solves the CAPTCHA can still book, up to the per-address limit | [ADR-0004](adr/0004-restraint-no-login-no-id-number.md), [ADR-0011](adr/0011-waf-captcha-instead-of-a-one-time-pin.md), [BR-11](domain-rules.md#br-11), [BR-13](domain-rules.md#br-13) |
| Each booking in AWS is deleted once its appointment is over; a cancelled one at once | Personal data with no further use is not kept | No history and no no-show analysis; the SMS daily cap keeps a keyed fingerprint of each number for a day, never the number | [BR-14](domain-rules.md#br-14), [BR-17](domain-rules.md#br-17), the privacy notice at `/privacy` |
| Staging, then production after a person approves, from one image built once; private subnets with a NAT gateway per zone; a Multi-AZ database the app signs in to with IAM | Production runs exactly what staging tested, and losing a task, a zone or the database's primary leaves it serving | An estimated US$380 to 450 a month for both environments, against about $102 for the single demo; one AWS account, so the two are kept apart by IAM, not by account ([production-readiness.md](production-readiness.md)) | [ADR-0008](adr/0008-two-environments-promoted-by-digest.md), [ADR-0010](adr/0010-private-network-and-iam-database-auth.md) |

## Where to look, in order

| Minutes | Open | What it shows |
|---|---|---|
| 2 | [BookAppointmentHandler.java](../api/src/main/java/za/co/fynbos/branchbook/booking/application/BookAppointmentHandler.java) | The ten steps of the booking transaction, each one line, with its lock order |
| 2 | [V2__schema.sql](../api/src/main/resources/db/migration/V2__schema.sql) | Every constraint named and explained; the two exclusion constraints |
| 1 | [ConcurrentBookingIT.java](../api/src/test/java/za/co/fynbos/branchbook/booking/application/ConcurrentBookingIT.java) | Twenty racers for one seat, released by a latch, on a real PostgreSQL |
| 2 | [NotificationDispatcher.java](../api/src/main/java/za/co/fynbos/branchbook/notification/application/NotificationDispatcher.java) | The claim with `FOR UPDATE SKIP LOCKED`, the backoff, and what happens when a mark fails |
| 2 | [app-stack.ts](../infra/lib/app-stack.ts) | One environment: the WAF rules and CAPTCHA, the private task and its permissions, the migration step, the alarms |
| 1 | [book-appointment.spec.ts](../e2e/book-appointment.spec.ts) | The customer's whole journey in a real browser, at 390 and 1280 px, with axe |

## What it does not do

- It has one AWS account for both environments, not one each, so a mistake in staging's stacks could still reach
  production's resources through what IAM allows ([SECURITY.md](../SECURITY.md#known-risks)).
- Its recovery times are targets, not yet measured: the restore drill and the fault-injection game day come after the
  first deployment ([resilience.md](resilience.md)).
- Delivery is best effort over AWS's shared route, and while the account is in the SMS sandbox only verified numbers
  receive a text; the booking stands either way.
- There are no staff screens, reminders, rescheduling or an email channel, each ruled out with its reason in the
  ADRs.

## Everything else

The [README](../README.md) maps the brief to the tests that prove it. [architecture.md](architecture.md) walks one
request through the code and the schema. [delivery.md](delivery.md) follows a commit to AWS.
[domain-rules.md](domain-rules.md) lists every rule with its status and proof, [errors.md](errors.md) every error
code, [runbook.md](runbook.md) what to do when something is wrong, [configuration.md](configuration.md) every
setting, [production-readiness.md](production-readiness.md) each production control with its evidence,
[resilience.md](resilience.md) each failure and what contains it, [threat-model.md](threat-model.md) the threats and
their controls, and [porting.md](porting.md) how the design maps to .NET or Vue. The decisions are in [adr/](adr/README.md), and the whole design, including what was planned and how the
build departed from it, is on the [architecture page](https://katlehokomeke.github.io/Appointment-Booking-System/).
