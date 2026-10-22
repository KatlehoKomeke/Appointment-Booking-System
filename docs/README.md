# Documentation map

Start with the [project README](../README.md). This folder holds everything that explains the system in more depth.

| Document | What it answers | Status |
|---|---|---|
| [architecture.md](architecture.md) | How the system is built and why: containers, modules, the booking transaction, the confirmation, the data model, time and personal information. | Written |
| [adr/](adr/README.md) | Each significant decision, the alternatives rejected and the consequences accepted. | Six records written |
| [domain-rules.md](domain-rules.md) | Every business rule, where it is enforced and the test that proves it. | Written; tests land with the milestones |
| [errors.md](errors.md) | Every error code the API returns, when, and what the customer sees. | Written |
| openapi.yaml | The API contract, generated from the code and pinned by a test. | Arrives in M2 |
| runbook.md | Operating the system: health checks, replaying a failed message, tracing a request. | Arrives in M5 |
| deployment/aws.md | The hosted demo in AWS Cape Town: CDK stacks, first deploy, guardrails and teardown. | Arrives with the deployment |

Project-wide policies live at the repository root:

- [SECURITY.md](../SECURITY.md): how secrets and personal information are protected, and how to report a vulnerability.
- [CONTRIBUTING.md](../CONTRIBUTING.md): the workflow, commit conventions and documentation standards every change must meet.
