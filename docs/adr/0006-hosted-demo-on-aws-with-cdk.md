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
  sends nothing; the first two arrive with the next backend change, not merged yet. The simulated inbox is off
  (`BRANCHBOOK_INBOX_ENABLED=false`), because with no sign-in it would list every booking's reference, and a demo
  number's last four digits are public ([infra/README.md](../../infra/README.md#the-hosted-demo)).

The decision otherwise stands.
