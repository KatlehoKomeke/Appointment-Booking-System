# Documentation map

Start with the [project README](../README.md). This folder holds everything that explains the system in more depth,
and this page is the one map of it.

**Which source wins.** The code first, then the repository's documents, which record what is built and are versioned
with the code, then the architecture page, which holds the full design including planned work and is re-checked at
each milestone. Where two disagree, the earlier one is right and the later one is corrected.

| Document | What it answers | Status |
|---|---|---|
| [Architecture page](https://claude.ai/artifact/X4b1pc9RSCboanuXBSn6oX) | The whole design on one page, including the parts not built yet: which sections are built, and where the build departs from the plan and why. | Living; checked against the code at the end of every milestone, and its header names the commit. The repository wins where they disagree |
| [architecture.md](architecture.md) | How the system is built and why: containers, modules, the booking transaction, the confirmation, the data model, time and personal information. | Written |
| [adr/](adr/README.md) | Each significant decision, the alternatives rejected and the consequences accepted. | Seven records written |
| [domain-rules.md](domain-rules.md) | Every business rule, where it is enforced and the test that proves it. | Written; each rule says whether it is proven or planned, and CI checks that every test a proven rule cites exists |
| [errors.md](errors.md) | Every error code the API returns, when, and what the customer sees. | Written |
| [openapi.yaml](openapi.yaml) | The API contract: every path, parameter, response and error. Swagger UI shows it at `/swagger-ui.html` on a running instance. | Generated from the code; `OpenApiContractIT` fails when it is out of date, and `make contract` regenerates it |
| [runbook.md](runbook.md) | Operating the system: health checks, what happened to a message, replaying a failed message, what a cancel leaves behind, the daily cap's ledger, the retention purge, tracing a request, reading the metrics. | Built in M5; the metrics in M6 |
| [../infra/README.md](../infra/README.md) | The AWS CDK app: what exists in AWS, how GitHub Actions connects without keys, and the first-time setup. | Written; grows with each stack |
| [../web/README.md](../web/README.md#ui-design) | The UI design: the main Tier 1 screens and states as mockups, the colour, type and spacing tokens, the components, the accessibility decisions and the copy rules. Its images are in [assets/ui/](assets/ui/). | Designed; built in M4 and tested end to end in M6 |
| [delivery.md](delivery.md) | How code gets from a laptop to AWS: the git hooks, the three workflows, continuous deployment of the hosted demo from `main`, Dependabot, the GitHub settings it relies on and the known gaps. | Written |
| [production-notes.md](production-notes.md) | What a bank deployment would add or change, each beside where the repository stops today: identity and OTP, a real SMS provider, the database, more than one task, the pipeline, observability, retention under POPIA, and the hosted demo's open access. | Written in M7; nothing in it is planned for a milestone |
| [porting.md](porting.md) | How the same design would be built on .NET and on Vue 3: what carries over unchanged (the schema, the contract, the rules and their tests) and what is rewritten. | Written in M7; a guide, not a plan |
| [assets/diagrams/](assets/diagrams/README.md) | Every diagram: where it is shown, what it is drawn from and the commit it was last checked against, plus the rules for drawing one. | Written |

Project-wide policies live at the repository root:

- [SECURITY.md](../SECURITY.md): how secrets and personal information are protected, and how to report a vulnerability.
- [CONTRIBUTING.md](../CONTRIBUTING.md): the workflow, commit conventions and documentation standards every change
  must meet, and which document and diagram to update for which change.

## Glossary

Terms that several documents use, defined once.

| Term | Meaning |
|---|---|
| Built, Partly built, Planned | The three status words used everywhere. Planned always names its milestone, for example *Planned (M5)*, *Planned (Tier 2)* or *Planned (after M6)*. |
| Milestone | One of M0 to M7, the order the system is built in. The README's [Milestones](../README.md#milestones) table lists what each delivers and its status. |
| Tier | Tier 1 is never cut, Tier 2 is built if time allows, and Tier 3 is cut by default ([Scope](../README.md#scope)). |
| Major commit | A commit after which the architecture page is re-checked ([CONTRIBUTING](../CONTRIBUTING.md#the-architecture-page)). |
| Port, adapter | A port is an interface a module declares for something it needs; an adapter implements it, often in another module, so the dependency points the right way. See the table below. |
| Workload stack | A CDK stack `ci`'s `deploy` job deploys on every push to `main`: `BranchbookData`, `BranchbookApp` and, after the smoke test, `BranchbookRelease`. |
| Release record | The SSM parameter `/branchbook/release` in `af-south-1`, holding the commit the pipeline last deployed and smoke-tested. The `deploy` job reads it back to prove the push is live. |
| `aws-demo` | The GitHub environment that `ci`'s `deploy` job, and the manual `aws-connection-check`, declare. The deploy role trusts only jobs in it, and it admits only `main` and `v*` tags. |
| `branchbook-github-deploy` | The IAM role the pipeline assumes through OpenID Connect, defined in the `BranchbookAccount` stack, which an administrator deploys. |
| CDK bootstrap | The `CDKToolkit` stack that `cdk bootstrap` creates in a Region, here `us-east-1` and `af-south-1`, with the default qualifier `hnb659fds`. `cdk deploy` works through its `cdk-hnb659fds-*` roles. |

The ports that cross a module line, and the catalogue's read port, with their adapters. Ports used only inside one
module, such as booking's `AppointmentRepository`, are left out.

| Port | Declared in | Adapter | Status |
|---|---|---|---|
| `CatalogueReadPort` | `catalogue` | `JdbcCatalogue` (`catalogue`) | Built (M1) |
| `BookedIntervalsPort` | `scheduling` | `JdbcBookedIntervals` (`booking`) | Built (M2) |
| `ConfirmationOutbox` | `booking` | `NotificationOutbox` (`notification`), which writes the PENDING confirmation SMS, and on a cancel deletes the appointment's confirmations and writes the cancellation SMS | Built (M3; the cancel in M6) |
| `AppointmentNotifications` | `booking` | `MessageQueries` (`notification`), which reads a booking's SMS | Built (M3) |
| `NotificationEraser` | `booking` | `MessageEraser` (`notification`), which deletes a purged booking's messages in the purge's transaction | Built |
| `MessageGateway` | `notification` | `SimulatedSmsGateway`, which sends nothing; and, where `branchbook.sms.gateway` is `aws`, `RoutingSmsGateway`, which sends every number outside the demo block a real SMS through `AwsSmsGateway` (AWS End User Messaging SMS) | Built (M5; AWS after M6) |
| `LookupAttemptStore` | `booking` | `JdbcLookupAttemptStore` (`booking`), which locks a reference's row in `lookup_attempts` with `SELECT ... FOR UPDATE`, so every instance shares the lookup limit's counts (BR-13) | Built (V8, after M6) |
