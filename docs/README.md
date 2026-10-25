# Documentation map

Start with the [project README](../README.md). This folder holds everything that explains the system in more depth.

| Document | What it answers | Status |
|---|---|---|
| [Architecture page](https://claude.ai/artifact/X4b1pc9RSCboanuXBSn6oX) | The whole design on one page, including the parts not built yet: which sections are built, and where the build departs from the plan and why. | Living; checked against the code at the end of every milestone, and its header names the commit |
| [architecture.md](architecture.md) | How the system is built and why: containers, modules, the booking transaction, the confirmation, the data model, time and personal information. | Written |
| [adr/](adr/README.md) | Each significant decision, the alternatives rejected and the consequences accepted. | Seven records written |
| [domain-rules.md](domain-rules.md) | Every business rule, where it is enforced and the test that proves it. | Written; each rule says whether it is proven or planned, and CI checks that every test a proven rule cites exists |
| [errors.md](errors.md) | Every error code the API returns, when, and what the customer sees. | Written |
| [openapi.yaml](openapi.yaml) | The API contract: every path, parameter, response and error. Swagger UI shows it at `/swagger-ui.html` on a running instance. | Generated from the code; `OpenApiContractIT` fails when it is out of date, and `make contract` regenerates it |
| runbook.md | Operating the system: health checks, replaying a failed message, tracing a request. | Arrives in M5 |
| [../infra/README.md](../infra/README.md) | The AWS CDK app: what exists in AWS, how GitHub Actions connects without keys, and the first-time setup. | Written; grows with each stack |

Project-wide policies live at the repository root:

- [SECURITY.md](../SECURITY.md): how secrets and personal information are protected, and how to report a vulnerability.
- [CONTRIBUTING.md](../CONTRIBUTING.md): the workflow, commit conventions and documentation standards every change must meet.
