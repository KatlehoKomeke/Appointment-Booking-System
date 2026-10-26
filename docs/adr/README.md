# Architecture decision records

Each significant decision is recorded in its own file, numbered in the order it was made. A record states the
context, the decision, the alternatives that were rejected and why, and the consequences we accept.

A record's decision is never edited. A fact that changes later, such as a name, a count or what is built so far, is
recorded as a dated entry in a `## Notes` section at the end of the record, so the decision still reads as it was
accepted. A changed decision gets a new record that supersedes the old one, and the old one's status is updated to
point at it.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-modular-monolith.md) | One deployable and one database, packaged by capability | Accepted |
| [0002](0002-double-booking-prevention.md) | Double booking prevented by exclusion constraints and a fixed lock order | Accepted |
| [0003](0003-transactional-outbox-for-confirmations.md) | The simulated confirmation is a transactional outbox | Accepted |
| [0004](0004-restraint-no-login-no-id-number.md) | No login, no ID number and no staff endpoints in v1 | Accepted |
| [0005](0005-committed-openapi-contract.md) | A committed OpenAPI contract with a generated TypeScript client | Accepted |
| [0006](0006-hosted-demo-on-aws-with-cdk.md) | An optional hosted demo in AWS Cape Town, owned by AWS CDK | Accepted; delivery trigger superseded by 0007 |
| [0007](0007-continuous-deployment-from-main.md) | Continuous deployment from main, through OpenID Connect | Accepted |

New records start from [0000-template.md](0000-template.md).
