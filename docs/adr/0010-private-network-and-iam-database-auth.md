# ADR-0010: A private network, and IAM database authentication for a least-privilege runtime role

- **Status:** Accepted
- **Date:** 2026-10-30

## Context

The hosted demo of [ADR-0006](0006-hosted-demo-on-aws-with-cdk.md) ran its tasks in public subnets with public IPs,
so they reached AWS's APIs without a NAT gateway. Each task signed in to the database as the master user, with a
password from Secrets Manager, over `sslmode=require`, which encrypts but does not check the server's certificate.
The same task ran Flyway at start-up, so the running service held the right to change the schema and to rewrite
Flyway's history. A compromised task would have had everything the database owner has.

## Decision

- **Private subnets.** The tasks run in the `app` subnets with no public IP. They admit only the load balancer, on
  8080, and may call out only on 443 (AWS's APIs) and to the database on 5432. Egress goes through one NAT gateway per
  zone in production, so losing one zone does not cut the other's egress; staging has one. The database stays in the
  `data` subnets, which have no route to the internet.
- **A runtime role with row privileges only.** Migration V9 creates `branchbook_app`, which may `SELECT`, `INSERT`,
  `UPDATE` and `DELETE` rows and nothing more: it owns no table, has no `CREATE` on the schema, and has no access to
  Flyway's history table ([V9__runtime_role.sql](../../api/src/main/resources/db/migration/V9__runtime_role.sql)).
- **IAM database authentication.** The service signs in as `branchbook_app` through the AWS Advanced JDBC Wrapper's
  `iam` plugin, with a 15-minute token its task role signs (`rds-db:connect` on that user alone). The application has
  no database password.
- **Full certificate checking.** Both connections use `sslmode=verify-full` against the RDS certificate bundle, which
  the [Dockerfile](../../Dockerfile) downloads at build time and pins by checksum.
- **Migrations are a deployment step.** A one-off ECS task, the same image with `BRANCHBOOK_MIGRATE_ONLY=true`, runs
  Flyway with the master secret. A CloudFormation custom resource starts it for every new image digest and waits; a
  non-zero exit, a task that cannot start or one that outlives the timeout fails the resource and the deployment. The
  service depends on it, so it is updated only after the migration exits 0
  ([infra/lib/schema-migration.ts](../../infra/lib/schema-migration.ts)).

The resources and the variables each task receives are in
[infra/README.md](../../infra/README.md#environments-staging-and-production).

## Alternatives considered

- **VPC interface endpoints instead of NAT.** About eight endpoints (ECR's API and Docker endpoints, CloudWatch Logs,
  Secrets Manager, SSM, STS, CloudWatch monitoring and End User Messaging SMS) in each of two zones cost more than
  NAT gateways here.
- **RDS Proxy.** It costs more and is not needed at this scale; [ADR-0006](0006-hosted-demo-on-aws-with-cdk.md) also
  records why a pooler conflicts with the session settings and Flyway's lock.
- **An application password in Secrets Manager, with rotation.** With IAM authentication there is no application
  password at all, so there is nothing to rotate or leak.

## Consequences

- A compromised task can read and write rows, but cannot change the schema, rewrite which migrations ran, or sign in
  as anyone but `branchbook_app`. `RuntimeRoleIT` proves the writes work and the DDL and history access are refused.
- IAM authentication limits the rate of new connections. A fixed Hikari pool of 5 per task stays far below it, and
  the connection budget in [infra/config](../../infra/config/production.json) is checked at synthesis.
- Tokens last 15 minutes, and the wrapper renews them. `AwsJdbcWrapperIT` proves the pool, Flyway and the session
  settings work through the wrapper on a real PostgreSQL; the `iam` plugin itself needs RDS, so staging is where it is
  first exercised.
- The NAT gateways cost money every hour, two in production: part of the roughly US$380 to 450 a month estimated
  (not measured) for both environments with their guardrails and observability, against about US$102 before.
- Every migration now runs before the new image serves, and a rollback runs the previous image against the newer
  schema, so each migration must be backward-compatible with the release before it
  ([runbook.md](../runbook.md)).
- The master secret is still not rotated; only the migration task reads it. That is a residual risk, with its reason,
  in [SECURITY.md](../../SECURITY.md#known-risks).
- Locally and in CI the application keeps connecting as the owner with a password, and `branchbook_app` goes unused;
  `make up` writes a random password to the git-ignored `.env`.
