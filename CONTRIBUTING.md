# Contributing

This guide sets the standard for every change to BranchBook. Two rules are non-negotiable: **no sensitive
information ever enters the repository**, and **every change is documented as well as it is tested**.

## Set up once per clone

```bash
make hooks   # every commit is scanned for secrets, and a major one reminds you to re-check the architecture page
```

## Workflow

- **Branches:** `feat/<topic>`, `fix/<topic>`, `docs/<topic>` or `chore/<topic>`, merged to `main` through a pull request.
- **Commits** follow [Conventional Commits](https://www.conventionalcommits.org/) with a module scope, for example
  `feat(booking): reserve the idempotency key before the seat lock`. Each commit builds and passes its tests on
  its own. History is never squashed into one commit, and never contains "wip" commits.
- **Pull requests** use the template in `.github/pull_request_template.md`: intent, proof, risk and what to review closely.
- **Releases** are tagged `vX.Y.Z` on a green `main`.

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

### Project documents

| When you... | Also update |
|---|---|
| Add or change a business rule | [docs/domain-rules.md](docs/domain-rules.md) and the proving test |
| Add or change an error code | [docs/errors.md](docs/errors.md) and `ProblemCode` |
| Change the API: a path, parameter or response | Run `make contract`, and commit [docs/openapi.yaml](docs/openapi.yaml) and `web/src/api/schema.d.ts` with the change |
| Make a decision with a rejected alternative | A new record in [docs/adr](docs/adr/README.md) |
| Change how the system is built or connected | [docs/architecture.md](docs/architecture.md) |
| Change how to run, test or deploy it | [README.md](README.md) |
| Make a major commit, finish a milestone, or build something differently from the design | The [architecture page](#the-architecture-page) |

### The architecture page

The [architecture page](https://claude.ai/artifact/X4b1pc9RSCboanuXBSn6oX) is the design's source of truth, so it must
never fall behind the code. Re-check it after every major commit and at the end of every milestone.

- **A major commit** adds or changes behaviour (a `feat`, or a `fix` that changes what the system does), adds a
  migration, or changes the API, the infrastructure, CI or deployment, or a decision record. Formatting, test-only,
  dependency-update and typo commits are not major.
- **Re-checking** means comparing each section with the code and updating its status (built, partly built or
  planned), adding a row to "Where the build departs from the plan" for anything built differently, then stamping the
  commit in the page header and adding an entry to "Changes to this page".
- **When nothing on the page is affected,** no section changes. The next re-check still stamps the newer commit.
- **You are reminded.** `scripts/major-commits.sh [<range>]` lists the major commits in a range, and the post-commit
  hook that `make hooks` enables runs it after every commit and prints a reminder when the commit is major.

## Security rules

- **Never commit** credentials, tokens, keys, `.env` files, real personal data, AWS account IDs or personal tool
  settings. The hook and CI block the common cases; review your staged diff for the rest.
- **Use synthetic data only:** made-up names and the published demo numbers. Never a real person's details.
- **Never log personal data.** Log the correlation id and the booking reference instead.
- **If a secret is ever committed,** follow [SECURITY.md](SECURITY.md): rotate first, then clean the history.

## Definition of done

- [ ] Tests prove the change, at the lowest layer that can.
- [ ] CI is green: build, tests, lint, documentation lint and the secret scan.
- [ ] Javadoc or TSDoc is written for every new public type and exported function.
- [ ] The project documents in the table above are updated.
- [ ] After a major commit, the architecture page has been re-checked against the code.
- [ ] The staged diff has been read for secrets and personal data.
