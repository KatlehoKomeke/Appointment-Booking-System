# ADR-0006: An optional hosted demo in AWS Cape Town, owned by AWS CDK

- **Status:** Accepted. The delivery trigger is superseded by [ADR-0007](0007-continuous-deployment-from-main.md): every push to `main` deploys.
- **Date:** 2026-10-22

## Context

`make up` on a reviewer's machine is the primary way to run BranchBook. A hosted demo is an optional extra, and
it must not break the design. The hosting must keep the outbox dispatcher running between requests
([ADR-0003](0003-transactional-outbox-for-confirmations.md)). It must offer real PostgreSQL 17 with `btree_gist`
([ADR-0002](0002-double-booking-prevention.md)), and Flyway must reach the database without a transaction pooler.
The public address must be 28 characters or fewer, so the confirmation SMS fits one segment. A fictional bank on
the public internet must not collect real personal information.

## Decision

- **Region:** AWS `af-south-1` (Cape Town), so data, backups and logs stay in South Africa.
- **Runtime:** one always-on Fargate task (ARM64, 0.5 vCPU, 1 GB) behind an HTTPS Application Load Balancer. It
  talks directly to RDS for PostgreSQL 17 on `db.t4g.micro`, with no RDS Proxy.
- **Edge guardrails:** AWS WAF gates reviewers with basic-auth credentials, rate-limits bookings per IP and blocks
  `/actuator`.
- **Infrastructure as code:** every resource is defined in AWS CDK (TypeScript) under `infra/`, in four stacks
  split by lifecycle: account, DNS, data and app. Jest assertions prove the key properties before each deploy.
- **Delivery:** GitHub Actions deploys on a release tag through an OIDC role, so no AWS keys are stored anywhere.
- **Profile:** the hosted app runs `prod,demo`. The demo profile accepts only published demo numbers, each locked to
  a fictional persona, and purges data older than 48 hours.

## Alternatives considered

- **Free tiers (Render, Neon, Koyeb, Fly.io trial).** They sleep or run out of quota, and the dispatcher stops.
- **AWS App Runner.** Closed to new customers, and it throttles CPU when no request is in flight.
- **RDS Proxy or a transaction-mode pooler.** They reject or pin the JDBC startup options that set lock and
  statement timeouts, and they break Flyway's session lock.
- **Amazon EKS.** A control plane costing about $73 a month buys nothing for one task. EKS is the documented
  production path instead.
- **Terraform.** A fine choice, and widely used. CDK was chosen so the infrastructure is typed and unit-tested with
  the same tooling as the web app. The resources map one-to-one, so moving to Terraform modules is mechanical.

## Consequences

- About $82 a month while it runs; new-account credits cover the review window. A budget alarm and a teardown date
  are part of the deployment.
- The hosted demo ships only after the core plan is green and never blocks the submission.
- Two things are verified on the first deploy: the PostgreSQL 17 minor version that Cape Town offers, and the
  browser's sign-in prompt behind the load balancer. An in-app basic-auth filter is the fallback.

## Notes

**2026-10-26.** Facts that have changed since the record was accepted.

- **Five stacks, not four.** [ADR-0007](0007-continuous-deployment-from-main.md) added `BranchbookRelease`, which
  records the deployed commit. `BranchbookAccount` and `BranchbookRelease` are built and deployed;
  `BranchbookDns`, `BranchbookData` and `BranchbookApp` come after M6
  ([infra/bin/branchbook.ts](../../infra/bin/branchbook.ts)).
- **Not every stack is in Cape Town.** `BranchbookAccount` is in `us-east-1`: IAM is global, AWS Budgets lives
  there, and the stack holds no customer data, so the data-residency decision is unaffected
  ([infra/README.md](../../infra/README.md#what-exists-today)).
- **The 48-hour purge is the retention job,** behind `branchbook.retention.enabled`, not a `demo` profile. The hosted
  demo's ECS task definition sets `BRANCHBOOK_RETENTION_ENABLED=true`; everywhere else it is off. Every hour it deletes
  each appointment booked 48 hours ago or earlier, with its messages and idempotency keys
  ([docs/runbook.md](../runbook.md#did-the-retention-purge-run)). It empties the live database only: a deleted booking
  stays in the automated backups until they expire ([SECURITY.md](../../SECURITY.md#personal-information-popia)).
- **Demo numbers are a block, not personas,** behind `branchbook.booking.demo-numbers-only`, not a `demo` profile.
  The hosted demo's task definition sets `BRANCHBOOK_BOOKING_DEMO_NUMBERS_ONLY=true`, so bookings take only
  082 555 0000 to 0199 and any other number is `422 DEMO_NUMBER_REQUIRED`, before anything is written. Names stay
  free text, so a number is never locked to a fictional persona
  ([docs/errors.md](../errors.md#codes)).
- **The budget alarm already exists,** in `BranchbookAccount`. The teardown date will be recorded in
  [infra/README.md](../../infra/README.md) when the app stacks are first deployed.

**2026-10-26, the hosted demo deployed.** `BranchbookData` and `BranchbookApp` were first deployed to `af-south-1`
on this date. Where they depart from the decision above and the notes before this one
([infra/README.md](../../infra/README.md#the-hosted-demo)):

- **The domain is `branchbook.click`,** registered through Route 53 on 2026-10-26, not `branchbook.co.za`.
  `https://branchbook.click` is 24 characters, inside the 28 the SMS allows.
- **There is no DNS stack.** Route 53 created the hosted zone when the domain was registered, so the stacks are four:
  `BranchbookAccount`, `BranchbookRelease`, `BranchbookData` and `BranchbookApp`. The zone's ID is in
  `infra/cdk.json`.
- **The administrator deploys the demo by hand,** with `cdk deploy BranchbookData BranchbookApp`, not the pipeline.
  The two stacks are in the CDK app only when `BRANCHBOOK_DEMO_GATE_PASSWORD` is set, which `deploy.yml` never does,
  so the pipeline still deploys `BranchbookRelease` alone. The image is built for ARM64 on the administrator's machine
  during the deploy.
- **There are no `prod` or `demo` Spring profiles.** The task sets environment variables instead: the database URL
  with `sslmode=require`, the credentials from Secrets Manager, `PUBLIC_BASE_URL`, the simulated inbox on, and
  `BRANCHBOOK_RETENTION_ENABLED`, which deletes every booking 48 hours after it was made.
- **Demo numbers are not restricted to personas.** The demo gate limits who can book, and the 48-hour deletion limits
  how long anything entered stays.
- **The release record does not record the hosted app's version.** Only the pipeline writes `/branchbook/release`.
- **The PostgreSQL minor version is 17.11,** the version `make up` runs, which Cape Town offers.

The decision stands.

**2026-10-26, the demo gate removed.** The project owner removed it on the same day.

- **No sign-in, and the inbox off.** A sign-in prompt in front of a demo is a poor experience for everyone who opens
  it, reviewers included, so WAF no longer asks for basic-auth credentials, and no in-app filter replaces it. WAF
  still blocks `/actuator`, rate-limits bookings per IP and applies AWS's managed rules, and the load balancer now
  redirects plain HTTP to HTTPS. The two stacks are in the CDK app when `BRANCHBOOK_HOSTED_DEMO=true`, which replaces
  `BRANCHBOOK_DEMO_GATE_PASSWORD`. With no gate, the application keeps real data out: only the published demo
  numbers, 082 555 0000 to 082 555 0199, may book, every booking is deleted after 48 hours, and the simulated gateway
  sends nothing; the first two are live since 058e6fd. The simulated inbox is off
  (`BRANCHBOOK_INBOX_ENABLED=false`), because with no sign-in it would list every booking's reference, and a demo
  number's last four digits are public ([infra/README.md](../../infra/README.md#the-hosted-demo)).

The decision otherwise stands.

**2026-10-27, real SMS on the hosted demo.** The project owner chose to let the demo send real SMS.

- **Any South African mobile may book,** and a number outside the published demo block is sent a real SMS through AWS
  End User Messaging SMS in af-south-1 (`BRANCHBOOK_SMS_GATEWAY=aws`); the task definition switches
  `BRANCHBOOK_BOOKING_DEMO_NUMBERS_ONLY` off. The block, 082 555 0000 to 0199, stays simulated, because it is published
  for anyone to try. The CDK app adds a protect configuration that allows South Africa only, `sms-voice:SendTextMessage`
  on the task role, and the `BRANCHBOOK_SMS_*` settings ([api/README.md](../../api/README.md#configuration)).
- **Real numbers are now stored,** for at most 48 hours: the retention job still deletes every booking, with its
  messages, 48 hours after it is made.
- **Delivery is best effort.** South Africa supports no sender IDs, so with no origination identity AWS sends over a
  shared route; until the owner opens a Support case for production access, the account is in the SMS sandbox, which
  texts only verified numbers and spends at most US$1 a month ([docs/runbook.md](../runbook.md#let-the-hosted-demo-send-real-sms)).
- **The cost and abuse controls** are the per-recipient daily cap, WAF's per-address booking limit, `MaxPrice` and
  AWS's monthly spend limit; with no one-time code, anyone can make the demo text any South African number, up to the
  cap a day ([SECURITY.md](../../SECURITY.md#known-risks)).
- **South Africa only, and one way to send.** A protect configuration in `BranchbookApp` blocks SMS to every one of
  the 245 countries AWS lists except South Africa, and every voice and MMS destination. The task role may call
  `sms-voice:SendTextMessage` through that protect configuration and nothing else.
- **Fewer messages.** At most five SMS a day go to one number (`BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP=5`), and WAF
  allows 10 booking and cancel `POST`s per IP in 5 minutes, down from 30.
- **WAF compares the decoded path.** Its path rules decode and lowercase the path first, so `/%61ctuator` is blocked
  too, and the task serves only `health` and `info` (`MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info`).
- **No delivery events are logged,** because they carry the destination number (BR-19).
- **Unchanged:** the retention job stays on and the simulated inbox stays off.

The decision otherwise stands.

**2026-10-27, retention after the appointment.** Facts that have changed since the notes above. The decision stands.

- **A booking is deleted once its appointment is over, not 48 hours after it was made.** Bookings open 30 days
  ahead, so the 48-hour rule deleted appointments that had not happened yet. The hourly retention job now removes a
  booking, with its messages and idempotency keys, within the hour after its appointment ends, and a cancelled one
  after the time it would have ended. The notes above that say "48 hours" describe the rule as it first shipped.

**2026-10-27, a cancel deletes the booking.** Facts that have changed since the notes above. The decision stands.

- **A cancelled booking is deleted at once,** not kept until the time it would have ended: the cancel deletes it with
  its confirmations, and only its cancellation SMS outlives it, until the dispatcher has sent it or given up on it
  ([BR-14](../domain-rules.md#br-14), [SECURITY.md](../../SECURITY.md#personal-information-popia)).
- **The daily cap has a stable key.** `BranchbookData` generates `branchbook/sms-cap-key` into Secrets Manager, and
  the task receives it as `BRANCHBOOK_SMS_RECIPIENT_CAP_KEY`, so a new task keeps every number's count
  ([infra/README.md](../../infra/README.md#real-sms)).
