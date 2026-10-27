# Contributing

This guide sets the standard for every change to BranchBook. Two rules are non-negotiable: **no sensitive
information ever enters the repository**, and **every change is documented as well as it is tested**.

## Set up once per clone

```bash
make hooks   # every commit is scanned for secrets, and a major one reminds you to re-check the architecture page
```

Nothing installs the hooks for you: until you run `make hooks`, your commits are not scanned on your machine.

## Workflow

- **How changes land today.** This is a one-person repository: commits are pushed straight to `main`, with
  `make test` run first, and each push runs the workflows described in [docs/delivery.md](docs/delivery.md). `main`
  refuses force-pushes and deletion but requires no passing checks, so a red check blocks nothing; [docs/delivery.md](docs/delivery.md#known-gaps) states
  that gap and what would close it.
- **With a team.** `main` would be protected, requiring the `api`, `web` and `stack` jobs of `ci` and the `gitleaks`
  job of `secret-scan`, one approving review, linear history, and a CODEOWNERS file covering `.github/` and `infra/`.
  Work would arrive on `feat/<topic>`, `fix/<topic>`, `docs/<topic>` or `chore/<topic>` branches, merged through a
  pull request that uses [.github/pull_request_template.md](.github/pull_request_template.md): intent, proof, risk and
  what to review closely.
- **Commits** follow [Conventional Commits](https://www.conventionalcommits.org/) with a module scope, for example
  `feat(booking): reserve the idempotency key before the seat lock`. No `commit-msg` hook enforces this; it is a
  convention. Keep every commit buildable on its own: CI builds only the head of each push, so run `make test` before
  pushing. History is never squashed into one commit, and never contains "wip" commits.
- **Releases.** A `vX.Y.Z` release tag is planned for M7 ([Milestones](README.md#milestones)). No workflow deploys
  on a tag push; the `aws-demo` environment admits `v*` tags, so `deploy` can be started by hand from one.

## Documentation standards

Documentation is part of the change, not a follow-up. A pull request that changes behaviour and not its
documentation is incomplete.

### Java (`api/`)

- **Every package has a `package-info.java`.** It states the module and layer's responsibility and what may
  depend on it. `PackageDocumentationTest` fails the build when one is missing.
- **Every public class, interface, record and enum has Javadoc.** It explains the responsibility and, where one
  applies, the business rule it implements, for example `{@code BR-7}`.
- **Every public method whose contract is not obvious from its signature has Javadoc.** It covers preconditions,
  the exceptions thrown and why, side effects, and whether it opens or requires a transaction.
- **Comments explain why, never what.** No commented-out code. A `TODO` must name an issue.
- **Enforced in CI:** Javadoc linting (`-Xdoclint`) fails the build on missing or broken documentation of public
  types, from milestone M0.

### TypeScript (`web/` and `infra/`)

- **Every exported component, hook and function has a TSDoc comment.** It covers purpose, parameters or props,
  and side effects such as network calls or storage.
- **Accessibility decisions** (focus moves, live regions, keyboard handling) are commented where they are made.
- **Enforced in CI:** `eslint-plugin-jsdoc` requires documentation on exports, from milestone M0.

### Database (`db/migration/`)

- **Each Flyway migration starts with a header comment** stating its purpose.
- **Every non-obvious constraint is commented with its reason.** Examples: why an `EXCLUDE` constraint and not a
  unique index, and why a partial predicate.

### Tests

- **Test names read as requirements,** for example `twentyRacersForOneSeatProduceExactlyOne201`.
- **A test that proves a business rule cites it** (`BR-n`) in its Javadoc or display name. The rule's entry in
  [docs/domain-rules.md](docs/domain-rules.md) names the test, and its status says whether the rule is proven or
  planned. `scripts/check-test-refs.sh` (`make test-docs`, and in CI) fails if a rule has no status or no proof, or
  if a proven rule or the README cites a test that does not exist. Only methods annotated as tests count.

### Configuration, scripts and workflows

- **Every property in `application*.yml` has a comment** explaining what it controls and why it has its value.
- **Every script, Makefile target and GitHub Actions workflow starts with a comment** giving its purpose and usage.

### Diagrams

[docs/assets/diagrams/README.md](docs/assets/diagrams/README.md) is the index and the rulebook; these are the rules
that matter most.

- **Two formats, chosen by how often the picture changes.** Stable big pictures are hand-written SVG in
  `docs/assets/diagrams/`, each started from [`_template.svg`](docs/assets/diagrams/_template.svg). Anything that
  changes each milestone is a Mermaid block in the Markdown, limited to `flowchart`, `sequenceDiagram`,
  `stateDiagram-v2` and `erDiagram`, with no colours.
- **Status is drawn, not implied.** Built is solid; planned is dashed with its milestone, and a planned SVG element
  carries `class="planned"` and `data-milestone`. Every SVG has a legend.
- **No values that drift:** no patch versions, counts of tests or runs, dates, durations, SHAs or account IDs.
  [push-timeline.svg](docs/assets/diagrams/push-timeline.svg) is the one dated exception.
- **Check before committing** with the commands in
  [Editing a diagram](docs/assets/diagrams/README.md#editing-a-diagram) (`xmllint --noout` and the forbidden-content
  greps), look at it in light and dark, and update its row in the diagram index.

### Project documents

| When you... | Also update |
|---|---|
| Add or change a business rule | [docs/domain-rules.md](docs/domain-rules.md) and the proving test |
| Add or change an error code | [docs/errors.md](docs/errors.md) and `ProblemCode` |
| Change the API: a path, parameter or response | Run `make contract`, and commit [docs/openapi.yaml](docs/openapi.yaml) and `web/src/api/schema.d.ts` with the change |
| Make a decision with a rejected alternative | A new record in [docs/adr](docs/adr/README.md) |
| Change how the system is built or connected | [docs/architecture.md](docs/architecture.md) |
| Change how to run or test it | [README.md](README.md) |
| Make a major commit, finish a milestone, or build something differently from the design | The [architecture page](#the-architecture-page) |
| Change a workflow, a hook, Dependabot or a GitHub setting | [docs/delivery.md](docs/delivery.md) and [delivery-pipeline.svg](docs/assets/diagrams/delivery-pipeline.svg); [secret-layers.svg](docs/assets/diagrams/secret-layers.svg) if a secret layer changes |
| Change `infra/` or what runs in AWS | [infra/README.md](infra/README.md) and [aws-footprint.svg](docs/assets/diagrams/aws-footprint.svg); [access-boundary.svg](docs/assets/diagrams/access-boundary.svg) if access changes |
| Add a migration | [data-model.svg](docs/assets/diagrams/data-model.svg) and [docs/architecture.md, section 7](docs/architecture.md#7-data-model) |
| Add or rename a module, port or adapter | The module map and the request flow in [docs/architecture.md](docs/architecture.md#3-modules-and-the-dependency-rule), and the [glossary](docs/README.md#glossary) |
| Change the `Dockerfile`, `compose.yaml`, the probes or the `make up` and `dev-*` targets | [local-runtime.svg](docs/assets/diagrams/local-runtime.svg) |
| Finish a milestone | The [checklist](#at-the-end-of-a-milestone) below |

### The architecture page

The [architecture page](https://claude.ai/artifact/X4b1pc9RSCboanuXBSn6oX) holds the full design, including planned
work. The repository records what is built; where they disagree, the code and the repository win and the page is
corrected. Re-check it after every major commit and at the end of every milestone.

- **A major commit** adds or changes behaviour (a `feat`, or a `fix` that changes what the system does), adds a
  migration, or changes the API, the infrastructure, CI or deployment, or a decision record. Formatting, test-only,
  dependency-update and typo commits are not major.
- **Re-checking** means comparing each section with the code and updating its status (built, partly built or
  planned), adding a row to "Where the build departs from the plan" for anything built differently, then stamping the
  commit in the page header and adding an entry to "Changes to this page". It also covers every diagram whose source
  paths the commit changed: the diagram index lists each one's paths.
- **When nothing on the page is affected,** no section changes. The next re-check still stamps the newer commit.
- **You are reminded.** `scripts/major-commits.sh [<range>]` lists the major commits in a range, and the post-commit
  hook that `make hooks` enables runs it after every commit and prints a reminder when the commit is major. It does
  not cover every path a diagram is drawn from (for example `.githooks/`, `.gitleaks.toml` or the `Makefile`), nor
  GitHub settings, which produce no commit; the
  [diagram index](docs/assets/diagrams/README.md#keeping-each-diagram-true) lists what to check by hand.

### At the end of a milestone

- [ ] The README's Stage row and its Milestones table.
- [ ] The status paragraph at the top of [docs/architecture.md](docs/architecture.md) and each section's status line.
- [ ] Flip what is now built, found by search (here for M7, the milestone under way; the steps are in
  [When a milestone lands](docs/assets/diagrams/README.md#when-a-milestone-lands)):

  ```sh
  grep -rn --exclude=_template.svg 'data-milestone="M7"' docs/assets/diagrams
  grep -rn 'M7' README.md SECURITY.md docs infra/README.md api/README.md web/README.md
  ```

- [ ] The **Status.** line of each rule in [docs/domain-rules.md](docs/domain-rules.md) the milestone proves.
- [ ] The statuses in [docs/README.md](docs/README.md).
- [ ] Re-run the `gh api` checks in [docs/delivery.md](docs/delivery.md#github-settings-this-relies-on): a settings
  change produces no commit, so nothing else notices it.
- [ ] `Verified at` in the [diagram index](docs/assets/diagrams/README.md#index) and in the header comment of each
  changed SVG.
- [ ] Re-check the [architecture page](#the-architecture-page).

## Security rules

- **Never commit** credentials, tokens, keys, `.env` files, real personal data, AWS account IDs or personal tool
  settings. The hook and CI block the common cases; review your staged diff for the rest.
- **Use synthetic data only:** made-up names and the published demo numbers. Never a real person's details.
- **Never log personal data or a reference.** Log the correlation id and the appointment's id instead: a
  reference and the last four digits open an appointment, so a reference in a log is half a credential (BR-19).
  `MobileNumber`, `CustomerName` and `AppointmentReference` print a redacted form if logged by mistake, and
  `LogRedactionIT` fails if a name, number or reference reaches the application's or the database's log.
- **If a secret is ever committed,** follow [SECURITY.md](SECURITY.md): rotate first, then clean the history.

## Definition of done

- [ ] Tests prove the change, at the lowest layer that can.
- [ ] CI is green: build, tests, lint, documentation lint and the secret scan.
- [ ] Javadoc or TSDoc is written for every new public type and exported function.
- [ ] The project documents in the table above are updated.
- [ ] Every diagram that draws the changed part still matches it, checked in light and dark.
- [ ] After a major commit, the architecture page has been re-checked against the code.
- [ ] The staged diff has been read for secrets and personal data.
