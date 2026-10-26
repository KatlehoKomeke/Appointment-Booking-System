# Delivery: from a commit to Cape Town

*Status: Built: two git hooks, five workflows, Dependabot, the `Protect main` ruleset, continuous deployment of the release record. Not built:
required checks on `main` and ci gating the deploy ([Known gaps](#known-gaps)). Planned (after M6): the application stacks.*

This page is the one home for how code moves from a laptop to AWS: the hooks, how changes reach `main`, what each
workflow proves, which workflow runs when, the deployment, Dependabot, the GitHub settings the pipeline relies on,
and the known gaps. AWS resources and their trust are in [infra/README.md](../infra/README.md); the secrets policy
is in [SECURITY.md](../SECURITY.md#secrets).

## At a glance

![From a commit to Cape Town: an opt-in pre-commit hook refuses secret files and runs gitleaks, push protection checks the push, and main, which requires no checks, starts ci, secret-scan, infra (on infra changes) and deploy side by side. deploy does not wait for ci. It passes the aws-demo gate, exchanges an OIDC token at STS, deploys BranchbookRelease through the bootstrap roles and verifies the commit; an administrator deploys BranchbookAccount.](assets/diagrams/delivery-pipeline.svg)

The numbered steps in the image:

1. `git commit`.
2. **pre-commit hook**: refuses `.env` files, key files, `.claude/` and `cdk.context.json`, then gitleaks checks the
   staged diff. It fails closed: the commit is refused on a finding, or when no scanner can run.
3. **post-commit hook**: a reminder after a major commit; it never blocks.
4. `git push`.
5. **push protection**: refuses known provider secrets, and the push is rejected.
6. **`main`**: force-pushes and deletion refused, no required checks. On every push to `main` the workflows start together:
   `ci.yml` (`api`, `web`, `stack`: application checks, no AWS access), `secret-scan.yml` (`gitleaks`: full
   history, after the push, blocks nothing), `infra.yml` (`test-and-synth`, only if `infra/**` or `infra.yml`
   changed) and `deploy.yml` (`test`, then `deploy`, joined by `needs: test`).
7. **`test`** in `deploy.yml`: CDK lint, tsc, Jest.
8. **environment `aws-demo`**: `main` and `v*` tags only.
9. **AWS STS**: `AssumeRoleWithWebIdentity` with an OIDC token from `token.actions.githubusercontent.com`; `aud`
   and `sub` must match the trust policy; credentials for at most 1 hour as `branchbook-github-deploy`.
10. **CDK bootstrap roles** (file-publishing, deploy), then **AWS CloudFormation** as the cfn-exec role.
11. **`BranchbookRelease`**: `/branchbook/release = GITHUB_SHA`.
12. **verify**: must equal `GITHUB_SHA`, else the job fails.

A. **Administrator**: `branchbook-admin`, `aws login`, `BRANCHBOOK_ALERT_EMAIL` set; `cdk deploy BranchbookAccount`,
by hand.

Also drawn: Dependabot's weekly pull requests, the manual `aws-connection-check.yml`, the gap between ci and deploy,
the stacks planned after M6, and `BranchbookAccount` in us-east-1, which holds the GitHub OIDC provider, the deploy
role and the `branchbook-monthly` budget.

## On your machine: the hooks

[`make hooks`](../Makefile) points `core.hooksPath` at [.githooks/](../.githooks). Nothing else installs the hooks,
so a fresh clone has none until it runs.

| Hook | Runs | Does | Blocks the commit | Skipped by |
|---|---|---|---|---|
| [`pre-commit`](../.githooks/pre-commit) | Before each commit | 1. Refuses staged `.env` and `.env.*` files (except `.env.example`), `*.pem`, `*.key`, `*.p12`, `*.pfx`, `*.jks`, `*.keystore`, `id_rsa*`, `id_ed25519*`, anything under `.claude/`, `CLAUDE.local.md` and `cdk.context.json`. This catches `git add -f`. 2. Pipes the staged diff to gitleaks with [.gitleaks.toml](../.gitleaks.toml), using a local `gitleaks` binary or else the `ghcr.io/gitleaks/gitleaks` image in Docker. | Yes, and it fails closed: no gitleaks and no running Docker means no commit. | A clone without `make hooks`; `git commit --no-verify` |
| [`post-commit`](../.githooks/post-commit) | After each commit | Runs [scripts/major-commits.sh](../scripts/major-commits.sh) and, after a major commit, prints the reminder to re-check the architecture page. Silent while a rebase replays commits. | Never | A clone without `make hooks`. `--no-verify` does not skip it. |

There is no `commit-msg` or `pre-push` hook, so Conventional Commits are a convention, not a check. If the
pre-commit hook was skipped, push protection still checks the push for provider secrets, and `secret-scan` reports
what gitleaks finds, but only after the push ([Known gaps](#known-gaps)).

## How changes reach main

One maintainer works trunk-based: `make test` first (the checks of `ci`'s `api` and `web` jobs, see
[Run the same checks locally](#run-the-same-checks-locally)), then the commits are pushed straight to `main`.
Commits follow Conventional Commits with a module scope ([CONTRIBUTING.md](../CONTRIBUTING.md#workflow)).

Actions builds the head of each push, not every commit. The six-commit M2 push
([6bd3330](https://github.com/KatlehoKomeke/Appointment-Booking-System/commit/6bd3330) to
[c5feafe](https://github.com/KatlehoKomeke/Appointment-Booking-System/commit/c5feafe)) produced one run of each
workflow, all on c5feafe. Nothing on GitHub enforces this order; the first row of [Known gaps](#known-gaps) says
what that means and what would close it.

### With a team

- Pull requests into `main`, using [.github/pull_request_template.md](../.github/pull_request_template.md).
- Required checks `ci / api`, `ci / web`, `ci / stack` and `secret-scan / gitleaks`. `infra / test-and-synth` cannot
  be required as it stands: when its path filter skips it, a required check waits for ever.
- One approving review, linear history, and a `CODEOWNERS` file covering `.github/` and `infra/`. There is no
  `CODEOWNERS` file today.
- `deploy` started only after a green `ci`.

## The five workflows

| Workflow | Runs on | Jobs | What it proves | AWS access | Blocks |
|---|---|---|---|---|---|
| [`ci`](../.github/workflows/ci.yml) | Push to `main`; every pull request | `api`, `web`, `stack`, in parallel | The API builds and passes every check, and its unit and API tests pass again in America/New_York; the web app matches the contract, passes lint, type and format checks and its tests in two time zones, and builds; the Docker image starts healthy with PostgreSQL and passes the smoke test with no credential in its logs | None | Nothing on `main` |
| [`secret-scan`](../.github/workflows/secret-scan.yml) | Every push (any branch or tag) and pull request | `gitleaks` | No commit in the full history (`fetch-depth: 0`) holds a secret that gitleaks or [.gitleaks.toml](../.gitleaks.toml) recognises | None | Nothing; it reports after the push |
| [`infra`](../.github/workflows/infra.yml) | Push or pull request changing `infra/**` or `infra.yml` | `test-and-synth` | The CDK app lints, type-checks and passes Jest, and `cdk synth --quiet` succeeds for every stack. With the placeholder `BRANCHBOOK_ALERT_EMAIL=alerts@example.com` the app includes `BranchbookAccount`; no other workflow synthesises the app with that stack in it | None | Nothing |
| [`deploy`](../.github/workflows/deploy.yml) | Push to `main`; manual run | `test`, then `deploy` (`needs: test`) | The CDK app passes lint, tsc and Jest; then `BranchbookRelease` deploys in af-south-1 and records this commit | `deploy` job only: OIDC as `branchbook-github-deploy`, in `aws-demo` | `test` blocks the `deploy` job |
| [`aws-connection-check`](../.github/workflows/aws-connection-check.yml) | Manual run only | `assume-deploy-role` | GitHub can assume the deploy role in us-east-1 without a stored key; it prints the caller ARN with every 12-digit run masked | OIDC as the same role, in `aws-demo` | Nothing |

Every workflow grants `contents: read`, only the two AWS jobs add `id-token: write`, and the default `GITHUB_TOKEN`
is read-only. Actions are pinned by
major tag (`actions/checkout@v7`, `aws-actions/configure-aws-credentials@v6`). The checks in each `ci` job, by step
name (set-up steps left out):

- **`api`**: *Build and run every check* (`./mvnw -B -ntp verify`; what it enforces is in
  [api/README.md](../api/README.md#what-the-build-enforces)), *Unit tests and API tests in America/New_York*, then
  *Check that the tests the README and the proven domain rules cite exist* ([scripts/check-test-refs.sh](../scripts/check-test-refs.sh)).
  A failed run keeps its test reports for 7 days.
- **`web`**: *Install the pinned dependencies*, *Check that the API types match docs/openapi.yaml*, *Lint, including
  TSDoc and accessibility rules*, *Type-check*, *Check formatting*, *Unit tests in UTC*, *Unit tests in
  America/New_York*, *Production build* ([web/README.md](../web/README.md#quality-gates)).
- **`stack`**: *Build the image and start the stack, as make up does* (`docker compose up --build --detach --wait
  --wait-timeout 300`), *Smoke-test the running application* ([scripts/smoke.sh](../scripts/smoke.sh)), *Check that
  the logs hold no credentials* (the `app` and `db` logs must match neither `password` nor `local-dev-only`).

## Which workflow runs when

| Event | `pre-commit` | `post-commit` | `ci` | `secret-scan` | `infra` | `deploy` | `aws-connection-check` |
|---|---|---|---|---|---|---|---|
| Commit, hooks enabled | yes | yes | no | no | no | no | no |
| Commit with `--no-verify` | no | yes | no | no | no | no | no |
| Push to a branch other than `main` | no | no | no | yes | if infra changed | no | no |
| Pull request | no | no | yes | yes | if infra changed | no | no |
| Dependabot pull request | no | no | once (`pull_request`) | twice (`push` and `pull_request`) | twice, if infra changed | no | no |
| Push to `main` | no | no | yes | yes | if infra changed | yes | no |
| Push a `v*` tag | no | no | no | yes | yes, see note 3 | no | no |
| Manual run | no | no | no | no | no | yes, see note 4 | yes, see note 4 |

1. "Infra changed" means a change under `infra/` or to `.github/workflows/infra.yml`.
2. Dependabot pushes its branches into this repository, so each of its pull requests also fires `push` events.
   The first Dependabot run shows it: `secret-scan` ran on both events, `ci` on `pull_request` only.
3. `infra.yml`'s push trigger has only a `paths` filter, and GitHub does not evaluate path filters for tag pushes,
   so a tag push runs it. No tag has been pushed yet; check this at the first release tag (M7).
4. A manual run can start from any branch or tag. The `aws-demo` environment admits only `main` and `v*` tags, so
   from any other ref `deploy`'s `test` job runs and its `deploy` job is refused before a token is issued, and
   `aws-connection-check` is refused outright. No workflow deploys on a tag by itself.
5. No workflow that runs on pull requests uses a secret or the `aws-demo` environment, so a pull request from a fork
   cannot reach AWS.

## One push, measured

![Timeline of the push of c5feafe: ci's web, stack and api jobs, gitleaks and deploy's test job all start within 3 seconds. deploy then runs from 30 to 87 seconds and finishes at 12:41:40Z, 13 seconds before ci's 97-second api job, because it never waits for ci; infra did not run.](assets/diagrams/push-timeline.svg)

The push of c5feafe, the head of the six M2 commits, on 25 October 2026. These are the only pipeline timings in
the documentation; they are re-measured only when the job structure changes.

- **Start.** GitHub created all three runs at 12:40:13Z: [ci](https://github.com/KatlehoKomeke/Appointment-Booking-System/actions/runs/40194135208),
  [secret-scan](https://github.com/KatlehoKomeke/Appointment-Booking-System/actions/runs/40194135188) and
  [deploy](https://github.com/KatlehoKomeke/Appointment-Booking-System/actions/runs/40194135199). `infra` did not run,
  because nothing under `infra/` changed.
- **deploy.** `test` ran from 2 to 27 seconds and `deploy` from 30 to 87 seconds; the run finished at 12:41:40Z.
- **ci.** `web` took 25 seconds, `stack` 60 and `api` 97; `api` finished at 12:41:53Z, 13 seconds after deploy.
  `gitleaks` took 8 seconds.

To re-measure, look the runs up by the full commit hash (`gh run list --commit` does not match a short one), then
read each run's job times:

```bash
gh run list --commit "$(git rev-parse c5feafe)"
gh api repos/KatlehoKomeke/Appointment-Booking-System/actions/runs/40194135199/jobs \
  --jq '.jobs[] | "\(.name) \(.started_at) \(.completed_at)"'
```

## Continuous deployment

Every push to `main` runs [deploy.yml](../.github/workflows/deploy.yml)
([ADR-0007](adr/0007-continuous-deployment-from-main.md)). After `test` passes, the `deploy` job:

1. **Enters `aws-demo`.** The environment's branch policy admits only `main` and `v*` tags. A run from any other ref
   is refused here, before GitHub issues an OIDC token.
2. ***Sign in to AWS through OpenID Connect.*** `aws-actions/configure-aws-credentials@v6` assumes the role in the
   environment secret `AWS_DEPLOY_ROLE_ARN`, with session name `deploy-<run id>`, in af-south-1, with
   `mask-aws-account-id: true`.
3. ***Deploy the workload stacks.*** `npx cdk deploy BranchbookRelease --require-approval never --ci --progress events`.
   CDK assumes the bootstrap roles, and CloudFormation applies the template as the bootstrap execution role.
4. ***Verify the deployed commit.*** `aws ssm get-parameter --name /branchbook/release` must equal `GITHUB_SHA`;
   otherwise the job exits 1. On success the log says `AWS af-south-1 now records commit <sha>`, which anyone can
   read on the run page.

**What the read-back proves:** CloudFormation applied this commit's template in af-south-1. **What it does not
prove:** that an application is serving (none is hosted until after M6), or that the application's tests passed
(`ci` runs alongside and is not consulted). The pipeline never sets `BRANCHBOOK_ALERT_EMAIL`, so the CDK app it
runs does not contain `BranchbookAccount`, the stack that defines its own role. How the trust works, and who can
change what, is in [infra/README.md](../infra/README.md#how-github-reaches-aws-without-keys) and
[Who can change what in AWS](../infra/README.md#who-can-change-what-in-aws).

**Evidence.** The first deploy,
[3a31516](https://github.com/KatlehoKomeke/Appointment-Booking-System/actions/runs/39855902718), failed exactly at
*Verify the deployed commit*, with `AccessDeniedException` on `ssm:GetParameter`.
[3f842b1](https://github.com/KatlehoKomeke/Appointment-Booking-System/commit/3f842b1) added the `ReadReleaseRecordOnly`
statement to the role in `BranchbookAccount`, which only the administrator can deploy, and every deploy since has
passed ([deploy runs](https://github.com/KatlehoKomeke/Appointment-Booking-System/actions/workflows/deploy.yml)).

### Concurrency

| Group | Workflow | `cancel-in-progress` | Effect |
|---|---|---|---|
| `deploy-aws` | `deploy` | `false` | One run at a time, across all refs, and at most one pending. A newer run replaces a pending one, so a superseded commit may never be recorded; a running deploy is never cancelled. |
| `ci-<ref>`, `secret-scan-<ref>`, `infra-<ref>` | `ci`, `secret-scan`, `infra` | `true` | A newer push to the same branch, tag or pull request cancels the run in progress. |

`aws-connection-check` has no concurrency group.

## Keeping secrets out

Secrets meet three checks: the pre-commit hook and push protection, which refuse them, and `secret-scan`, which
reports them after the push. The pipeline holds no AWS key of its own, because it signs in through OIDC. [SECURITY.md](../SECURITY.md#secrets) owns the policy, the
layered picture and what to do if a secret is committed.

## Dependency updates

[.github/dependabot.yml](../.github/dependabot.yml) checks every ecosystem weekly. For Maven, npm and GitHub
Actions, minor and patch updates are grouped into one pull request per directory; the two Docker ecosystems have no
group, so each image gets its own pull request. A major version always gets its own pull request.

| Ecosystem | Directory | Group | Majors ignored |
|---|---|---|---|
| `maven` | `/api` | `api-minor-and-patch` | none |
| `npm` | `/web` | `web-minor-and-patch` | `typescript`, `@types/node` |
| `npm` | `/infra` | `infra-minor-and-patch` | `typescript`, `@types/node` |
| `docker` | `/` (the Dockerfile) | none: one pull request per image | `eclipse-temurin`, `node` |
| `docker-compose` | `/` (compose.yaml) | none | `postgres` |
| `github-actions` | `/` | `actions-minor-and-patch` | none |

The ignored majors are deliberate: Java and Node.js stay on their LTS lines, TypeScript stays on 6.x for
typescript-eslint, and PostgreSQL stays on 17, the version the planned Amazon RDS database targets
([ADR-0006](adr/0006-hosted-demo-on-aws-with-cdk.md)). Dependabot alerts and security updates are also on
([GitHub settings](#github-settings-this-relies-on)).

Two pins are invisible to Dependabot and are updated by hand:

- `postgres:17.11-alpine` in [TestcontainersConfiguration.java](../api/src/test/java/za/co/fynbos/branchbook/support/TestcontainersConfiguration.java).
  Dependabot updates the same image in compose.yaml, so move this one in the same pull request.
- gitleaks `v8.30.1` in [.githooks/pre-commit](../.githooks/pre-commit), the [Makefile](../Makefile) and
  [secret-scan.yml](../.github/workflows/secret-scan.yml).

**History.** The first run opened pull requests
[#1 to #7](https://github.com/KatlehoKomeke/Appointment-Booking-System/pulls?q=is%3Apr+author%3Aapp%2Fdependabot),
all major bumps. All seven were closed unmerged, and
[cc07871](https://github.com/KatlehoKomeke/Appointment-Booking-System/commit/cc07871) added the ignores above.

## GitHub settings this relies on

A setting change produces no commit, so each one is listed with the read-only `gh api` call that shows it. None
prints an AWS account ID. Set `R` first:

```bash
R=repos/KatlehoKomeke/Appointment-Booking-System
```

| Setting | Today | Check with | Readable by |
|---|---|---|---|
| Default branch | `main` | `gh api $R --jq .default_branch` | anyone |
| Branch protection on `main` | No classic protection rule; the ruleset below applies | `gh api $R/branches/main --jq .protected` prints `true`, because of the ruleset | anyone |
| Rulesets | `Protect main`, on the default branch: refuses force-pushes and deletion, with no bypass; requires no pull request, review or check | `gh api $R/rules/branches/main --jq '[.[].type]'` prints `["non_fast_forward","deletion"]` | anyone |
| Environment `aws-demo` | Deployment branches `main` (branch) and `v*` (tag); no required reviewers or wait timer; administrators may bypass | `gh api $R/environments/aws-demo --jq '{rules: [.protection_rules[].type], can_admins_bypass}'` and `gh api $R/environments/aws-demo/deployment-branch-policies --jq '.branch_policies[] \| {name, type}'` | anyone |
| `aws-demo` secrets and variables | One secret, `AWS_DEPLOY_ROLE_ARN`; no variables. Repository-level Actions secrets and variables: none | `gh api $R/environments/aws-demo/secrets --jq '.secrets[].name'` and `gh api $R/environments/aws-demo/variables --jq .total_count`; `gh api $R/actions/secrets --jq .total_count` | owner |
| OIDC subject | GitHub's immutable subject, with numeric owner and repository IDs | `gh api $R/actions/oidc/customization/sub --jq .use_immutable_subject` prints `true` | anyone |
| Secret scanning and push protection | On; non-provider patterns and validity checks off | `gh api $R --jq .security_and_analysis` | owner |
| Dependabot alerts | On | `gh api $R/vulnerability-alerts --silent && echo on` | owner |
| Dependabot security updates | On | `gh api $R/automated-security-fixes --jq .enabled` | owner |
| Code scanning | Not configured | `gh api $R/code-scanning/default-setup --jq .state` | owner |
| Private vulnerability reporting | On | `gh api $R/private-vulnerability-reporting --jq .enabled` | anyone |
| Actions | All actions allowed; SHA pinning not required | `gh api $R/actions/permissions` | owner |
| Default `GITHUB_TOKEN` | Read-only; cannot approve pull requests | `gh api $R/actions/permissions/workflow` | owner |

Read by the owner on 26 October 2026, at commit 85cd726. "Anyone" means the endpoint is public, so any GitHub
account can run the check; the others answer only the repository's administrators.

## Known gaps

Each gap is stated here once, with what limits it today and what would close it.

| Gap | Effect today | What would close it |
|---|---|---|
| `main` requires no checks | The `Protect main` ruleset refuses force-pushes and deletion, but no pull request, review or passing check is required, so a red check blocks nothing. On the push of [e8950df](https://github.com/KatlehoKomeke/Appointment-Booking-System/commit/e8950df), [ci failed](https://github.com/KatlehoKomeke/Appointment-Booking-System/actions/runs/40211944802) in *Build and run every check* and [deploy passed](https://github.com/KatlehoKomeke/Appointment-Booking-System/actions/runs/40211944830); [80e4c83](https://github.com/KatlehoKomeke/Appointment-Booking-System/commit/80e4c83) fixed it. Leaving `main` open is a deliberate choice while one person works on the repository. | Before the application is hosted (after M6): protect `main` with the settings under [With a team](#with-a-team), required checks first. |
| `deploy` does not wait for `ci` | Harmless while only `BranchbookRelease` deploys: the release record can name a commit whose `ci` failed, as e8950df's did. | Before `BranchbookApp` joins the job: start `deploy` with `workflow_run` after a successful `ci`, call `ci` as a reusable workflow, or rely on required checks. Not yet decided in an ADR. |
| Only the head of each push is built | A broken commit in the middle of a push goes unnoticed by `ci`. `secret-scan` still reads every commit. | Run `make test` before each push; with a team, one pull request per change. |
| `secret-scan` reports after publication | In a public repository, a secret that reaches GitHub is already exposed when the scan finds it. | The pre-commit hook and push protection can stop a secret before it is published; a finding means rotating first ([SECURITY.md](../SECURITY.md#if-a-secret-is-committed)). |
| Push protection knows provider patterns only | The two custom rules in [.gitleaks.toml](../.gitleaks.toml), a PostgreSQL URL with a password and an ARN with an account ID, are checked only by the hook and `secret-scan`. | Custom secret-scanning patterns that mirror those rules, with push protection on for them. |
| No workflow builds or deploys on a tag | Nothing today: there is no tag. A `v*` tag push would run only `secret-scan` and `infra`. | Dispatch `deploy` on the M7 tag, or add a tag trigger when releases start. |
| The trust subject names no workflow | Any workflow run from `main` or a `v*` tag that declares `aws-demo` and requests an OIDC token can assume the deploy role: today `deploy.yml` and `aws-connection-check.yml`. | `CODEOWNERS` and a required review on `.github/`, or `job_workflow_ref` in the OIDC subject and the trust policy. |
| The pipeline's self-access guard lives in code | `BranchbookAccount` stays out of the pipeline's reach because the CDK app omits it and `deploy.yml` never sets `BRANCHBOOK_ALERT_EMAIL`. IAM does not forbid it: the bootstrap roles the deploy role may assume can update any stack. | The options in [infra/README.md](../infra/README.md#who-can-change-what-in-aws). |
| Two pins Dependabot cannot see | The Testcontainers PostgreSQL image and gitleaks fall behind silently. | Update them by hand ([Dependency updates](#dependency-updates)). |

## Run the same checks locally

| CI job | Local equivalent | Difference |
|---|---|---|
| `ci / api` | `make test-api` | The same, including the New York pass. |
| `ci / web` | `make test-web` | The tests run once, in your time zone. |
| The test-reference check in `ci / api` | `make test-docs` | The same script. |
| `ci / stack` | `make up && make smoke` | No credential check of the logs. |
| `secret-scan / gitleaks` | `make secrets-scan-history` | The same command. `make secrets-scan` scans the working tree instead, untracked files included. |
| `infra / test-and-synth` and `deploy / test` | `cd infra && npm ci && npm run lint && npm run build && npm test` | There is no make target. `infra` also runs `BRANCHBOOK_ALERT_EMAIL=alerts@example.com npx cdk synth --quiet`. |

`make test` runs `test-api`, `test-web` and `test-docs` in turn ([Makefile](../Makefile)).

## Keeping this page true

This page and [delivery-pipeline.svg](assets/diagrams/delivery-pipeline.svg) own these paths:
`.github/workflows/**`, `.github/dependabot.yml`, `.githooks/**`, `.gitleaks.toml`, the `hooks`, `secrets-scan` and
`secrets-scan-history` targets in the [Makefile](../Makefile), and [scripts/major-commits.sh](../scripts/major-commits.sh). A commit that
changes any of them updates this page and the diagram in the same commit. A change to a GitHub setting produces no
commit, so the checks above are re-run at the end of every milestone. The diagram index is in
[docs/assets/diagrams/README.md](assets/diagrams/README.md).
