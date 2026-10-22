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
