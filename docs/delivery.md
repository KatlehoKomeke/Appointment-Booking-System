# Delivery: from a commit to Cape Town

*Status: Built: two git hooks, five workflows, Dependabot with a 14-day cooldown, and a pipeline that builds one ARM64
image per commit, tests, scans and attests it, then promotes its digest from staging to production, behind a
reviewer's approval, with an automatic rollback. Every action is pinned to a commit SHA and every tool image to a
digest. The GitHub environments, variables and required checks it relies on are listed in
[GitHub settings](#github-settings-this-relies-on). Not built: strict checks, a review on pull requests and
`CODEOWNERS` ([Known gaps](#known-gaps)). `BranchbookAccount` and `BranchbookGuardrails` are deployed by the
administrator by hand ([infra/README.md](../infra/README.md#who-can-change-what-in-aws)).*

This page is the one home for how code moves from a laptop to AWS: the hooks, how changes reach `main`, what each
workflow proves, which workflow runs when, the delivery to staging and production, the supply chain, Dependabot, the
GitHub settings the pipeline relies on, and the known gaps. AWS resources and their trust are in
[infra/README.md](../infra/README.md); the secrets policy is in [SECURITY.md](../SECURITY.md#secrets).

## At a glance

![From a commit to Cape Town, all built, in three lanes: Laptop, GitHub and AWS. On the laptop, the opt-in pre-commit hook refuses secret files and runs gitleaks, failing closed (1); push protection refuses known provider secrets. In GitHub, main's ruleset requires every check before a push or merge (2), and codeql, dependency-review and secret-scan run beside ci, whose checks have no AWS access: api, web, infra, workflow-lint, and image, which builds the ARM64 image once (3), then stack, which tests that same image. On a push to main, publish pushes it to ECR and attests it (4); deploy-staging deploys its digest to staging and tests it there (5); and, after a person's approval, deploy-production verifies its provenance and deploys the same digest to production (6), with rollback-production if it fails. Every delivery job signs in through OpenID Connect as its environment's deploy role; the administrator deploys BranchbookAccount and BranchbookGuardrails by hand (A).](assets/diagrams/delivery-pipeline.svg)

The numbered steps in the image:

1. A commit passes the opt-in **pre-commit hook** (gitleaks), and, on push, GitHub's **push protection**.
2. **`main`**: the `Protect main` ruleset requires every check of **`ci`**, **`codeql`**, **`dependency-review`** and
   **`secret-scan`** before a push or merge, and refuses a force-push.
3. `ci`'s **`image`** job builds the ARM64 image once; **`stack`** runs that exact build; nothing rebuilds it later.
4. On a push to `main`, once every `ci` check is green, **`publish`** pushes the image to ECR as `branchbook:<commit>`
   and attests it; its digest is what every later job deploys.
5. **`deploy-staging`** deploys the digest to https://stg.branchbook.click, smoke- and end-to-end-tests it, then
   records it.
6. **`deploy-production`** waits for a person's approval, verifies the attestation, deploys the same digest to
   https://branchbook.click, smoke-tests it, then records it. If the deployment or the smoke test fails after
   production changed, **`rollback-production`** redeploys the previous release.

A. **Administrator**: `branchbook-admin`, `aws login`, `BRANCHBOOK_ALERT_EMAIL` or `BRANCHBOOK_GUARDRAILS=true`
set; `cdk deploy BranchbookAccount` and `BranchbookGuardrails`, by hand.

Also drawn: Dependabot's weekly pull requests, on which the checks run and nothing is delivered, the manual
`aws-connection-check.yml`, and every delivery job's sign-in through OpenID Connect.

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
[Run the same checks locally](#run-the-same-checks-locally)), then each change is merged from a short-lived branch
through a pull request, as a merge commit (since pull request
[#10](https://github.com/KatlehoKomeke/Appointment-Booking-System/pull/10)). GitHub requires no pull request, but it
does require the checks: the `Protect main` ruleset refuses a push or merge to `main` until every required check has
passed on the commit ([GitHub settings](#github-settings-this-relies-on)), so a red job keeps a change off `main`. The
checks are not strict, so a branch behind `main` can still merge ([Known gaps](#known-gaps)). Commits follow
Conventional Commits with a module scope ([CONTRIBUTING.md](../CONTRIBUTING.md#workflow)).

Actions builds the head of each push, not every commit. The six-commit M2 push
([6bd3330](https://github.com/KatlehoKomeke/Appointment-Booking-System/commit/6bd3330) to
[c5feafe](https://github.com/KatlehoKomeke/Appointment-Booking-System/commit/c5feafe)) produced one run of each
workflow, all on c5feafe. Nothing on GitHub enforces this order; the first row of [Known gaps](#known-gaps) says
what that means and what would close it.

### With a team

Done: pull requests into `main`, using [.github/pull_request_template.md](../.github/pull_request_template.md); the
required checks, which is why every check runs on every pull request rather than behind a path filter, since a
required check that a path filter skips waits for ever; a person's approval before production; and delivery only
after every `ci` check is green. Still to do:

- One approving review on pull requests, and linear history.
- A `CODEOWNERS` file covering `.github/` and `infra/`. There is no `CODEOWNERS` file today.
- Strict checks, so a pull request must be up to date with `main` before it merges.

## The workflows

| Workflow | Runs on | Jobs | What it proves | AWS access | Blocks |
|---|---|---|---|---|---|
| [`ci`](../.github/workflows/ci.yml) | Push to `main`; every pull request; a manual run | Checks: `api`, `web`, `infra`, `workflow-lint` and `image` in parallel, then `stack` (`needs: image`). Delivery, on a push to `main` only: `publish`, `deploy-staging`, `deploy-production`, `rollback-production` | The checks are listed [below, by step](#the-checks-in-each-ci-job). Delivery: the image the checks passed reaches staging, passes the smoke and end-to-end tests there, and, once a reviewer approves, reaches production with the same digest and passes the smoke test there; each environment's record then names the commit and the digest | Delivery jobs only: OIDC as `branchbook-github-deploy-staging` (`publish`, `deploy-staging`, in `staging`) or `branchbook-github-deploy-production` (`deploy-production`, `rollback-production`, in `production`). The checks have none | Every check is required on `main`, and `publish` `needs` all six, so a red check means no merge and no delivery |
| [`codeql`](../.github/workflows/codeql.yml) | Push to `main`; every pull request; Mondays 03:17 UTC | `analyze`, once per language: `java-kotlin`, `javascript-typescript`, `actions` | CodeQL's `security-extended` queries ran over the Java API, every TypeScript project and the workflows, without a build (`build-mode: none`); findings become code-scanning alerts | None | Its three checks are required; a finding blocks a merge only through the ruleset's code-scanning rule ([GitHub settings](#github-settings-this-relies-on)) |
| [`dependency-review`](../.github/workflows/dependency-review.yml) | Every pull request | `dependency-review` | No dependency the pull request adds or changes (Maven, npm, Docker, Actions) has a known advisory of high or critical severity, in runtime, development or unknown scope | None | Required on `main` |
| [`secret-scan`](../.github/workflows/secret-scan.yml) | Every push (any branch or tag) and pull request | `gitleaks` | No commit in the full history (`fetch-depth: 0`) holds a secret that gitleaks or [.gitleaks.toml](../.gitleaks.toml) recognises | None | Required on `main`, so a finding blocks the merge; the pushed commits are already public |
| [`aws-connection-check`](../.github/workflows/aws-connection-check.yml) | Manual run only, choosing `staging` or `production` | `assume-deploy-role` | GitHub can assume that environment's deploy role without a stored key; it prints the caller ARN with every 12-digit run masked. A run for `production` waits for its reviewer | OIDC as that environment's role | Nothing |

Every workflow sets `permissions: {}` and each job asks for what it needs: `contents: read` for a check;
`security-events: write` for `codeql`; `id-token: write` only in the delivery jobs and `aws-connection-check`;
`attestations: write` in `publish` and `attestations: read` in `deploy-production`. The default `GITHUB_TOKEN` is
read-only. Every `uses:` is pinned to a full commit SHA with its version in a comment, and every checkout sets
`persist-credentials: false`, so no later step or artifact holds a git token ([Supply chain](#supply-chain)).

### The checks in each ci job

By step name, set-up steps left out:

- **`api`**: *Build and run every check* (`./mvnw -B -ntp verify`; what it enforces is in
  [api/README.md](../api/README.md#what-the-build-enforces)), *Unit tests and API tests in America/New_York*, then
  *Check that the tests the README and the proven domain rules cite exist* ([scripts/check-test-refs.sh](../scripts/check-test-refs.sh)).
  A failed run keeps its test reports for 7 days.
- **`web`**: *Install the pinned dependencies*, *Check that the API types match docs/openapi.yaml*, *Lint, including
  TSDoc and accessibility rules*, *Type-check*, *Check formatting*, *Unit tests in UTC*, *Unit tests in
  America/New_York*, *Production build* ([web/README.md](../web/README.md#quality-gates)).
- **`infra`**: *Install the pinned dependencies*, *Lint, including TSDoc on every export*, *Type-check* (`npm run
  build`), *Unit-test the synthesised templates* (`npm test -- --ci`), *Synthesise staging* (`npx cdk synth --quiet`
  with `BRANCHBOOK_ENV=staging`), *Synthesise production, with the account and guardrails stacks*
  (`BRANCHBOOK_ENV=production`, `BRANCHBOOK_ALERT_EMAIL=alerts@example.com`, `BRANCHBOOK_GUARDRAILS=true`). Both use
  the placeholder `BRANCHBOOK_IMAGE_DIGEST=sha256:` followed by 64 zeros. It needs no AWS credentials: the tests
  assert on the generated CloudFormation, and synth never calls AWS.
- **`workflow-lint`**: *Lint the workflows and the shell in them (actionlint)*, which runs shellcheck on every `run`
  step, and *Audit the workflows and Dependabot's configuration (zizmor)*, with the job's read-only token so that
  zizmor also checks that each pinned SHA belongs to the action and version it claims and has no known advisory. Any
  finding fails the job.
- **`image`**, on `ubuntu-24.04-arm`: *Build the ARM64 image once* (`docker build --platform linux/arm64`, labelled
  with the commit, without a layer cache), *Scan the image for known vulnerabilities (Trivy)* (`--severity
  HIGH,CRITICAL --ignore-unfixed --exit-code 1`: any vulnerability with a fixed version fails the build), *Write the
  software bill of materials (CycloneDX)*, then keeps the image (`branchbook-image`, 7 days) and the SBOM (`sbom`, 90
  days) as artifacts. Its output is the image's ID.
- **`stack`**, on `ubuntu-24.04-arm`, `needs: image`: *Load that image as branchbook:local* (and check its ID is the
  `image` job's), *Generate the database password* (`openssl rand -hex 24`, masked), *Start the stack from that image,
  as make up does* (`docker compose up --no-build --detach --wait --wait-timeout 300`), *Smoke-test the running
  application* ([scripts/smoke.sh](../scripts/smoke.sh)), *Run the end-to-end tests* (Playwright's Chromium at 390 and
  1280 px, [e2e/README.md](../e2e/README.md); the browser cache key includes the runner's architecture), *Smoke-test
  the running application again*, *Race twenty bookings for the last seat (make demo-409)*
  ([scripts/demo-409.sh](../scripts/demo-409.sh)), *Load-test bookings briefly (k6)* ([load/booking.js](../load) and
  its thresholds, at most 5 minutes), *Scan the running application (OWASP ZAP baseline)*, *Check that the logs hold
  no credentials* (the `app` and `db` logs must hold neither the generated password nor the word `password`). The ZAP
  report is kept for 30 days; a failed run also keeps the end-to-end report and traces (`e2e-report`) and the stack's
  logs (`compose-logs`).

The ZAP baseline spiders the running app for a minute and scans every response passively.
[.github/zap/rules.tsv](../.github/zap/rules.tsv) lists, with the reason for each, the alerts that do not fail the
build: three informational ones it reports (10027, 10049, 10109) and two it ignores (10116, ZAP is out of date; 90004,
cross-origin isolation). Any other alert fails the job.

## Which workflow runs when

| Event | `pre-commit` | `post-commit` | `ci` checks | `ci` delivery | `codeql` | `dependency-review` | `secret-scan` | `aws-connection-check` |
|---|---|---|---|---|---|---|---|---|
| Commit, hooks enabled | yes | yes | no | no | no | no | no | no |
| Commit with `--no-verify` | no | yes | no | no | no | no | no | no |
| Push to a branch other than `main` | no | no | no | no | no | no | yes | no |
| Pull request | no | no | yes | no: skipped by its `if` | yes | yes | yes | no |
| Dependabot pull request | no | no | once (`pull_request`) | no | once | once | twice (`push` and `pull_request`) | no |
| Push to `main` | no | no | yes | yes, once every check passes | yes | no | yes | no |
| Push a `v*` tag | no | no | no | no | no | no | yes | no |
| Monday 03:17 UTC | no | no | no | no | yes, on `main` | no | no | no |
| Manual run | no | no | yes, from any ref | no: skipped by its `if` | no | no | no | from `main`, see note 3 |

1. Dependabot pushes its branches into this repository, so each of its pull requests also fires `push` events.
   `secret-scan` runs on both events; `ci` and `codeql` on `pull_request` only, because their push trigger names
   `main` alone.
2. No tag push starts `ci`, so no tag is ever built or deployed. Pushing `v1.0.0` on 2026-10-27 confirmed it: only
   `secret-scan` ran.
3. A manual run of `ci` runs the checks on whatever ref it starts from, and never delivers: every delivery job
   requires a `push` event on `main`. `aws-connection-check` declares the environment chosen, and both environments
   admit `main` only, so from any other ref it is refused before a token is issued.
4. No job that runs on a pull request reaches AWS: the delivery jobs are skipped there by their `if`, and no other job
   uses a secret or an environment, so a pull request from a fork cannot reach AWS.

## One push, measured

![Timeline of the push of c5feafe, measured before the deploy job joined ci: ci's web, stack and api jobs, gitleaks and deploy's test job all start within 3 seconds. deploy then runs from 30 to 87 seconds and finishes at 12:41:40Z, 13 seconds before ci's 97-second api job, because it did not wait for ci; infra did not run.](assets/diagrams/push-timeline.svg)

The push of c5feafe, the head of the six M2 commits, on 25 October 2026, measured when `deploy` was a workflow of its
own that ran beside `ci`. These are the only pipeline timings in the documentation, and they predate two changes:
delivery now waits for every check, and it deploys twice, to staging and then, after an approval, to production. A
push to `main` therefore takes at least the `image` and `stack` jobs end to end, plus two deployments and the time a
reviewer takes. The timings are re-measured, and the picture redrawn, after the first run of the pipeline as it now
stands.

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

Every push to `main` runs [ci.yml](../.github/workflows/ci.yml)
([ADR-0007](adr/0007-continuous-deployment-from-main.md)). Delivery starts only once `api`, `web`, `infra`,
`workflow-lint`, `image` and `stack` have passed, and it deploys the image those checks passed, by digest: there is no
build at deploy time. Each environment's settings come from the files the CDK app reads itself, through
[read-config.sh](../.github/scripts/read-config.sh), which reads them with `node -p` and accepts only plain names:
from `infra/config/<environment>.json` the address (`domain.name`), the stacks' names (`stackIdPrefix` followed by
`Data`, `App` and `Release`) and the prefix of the resources' names (`resourcePrefix`), and from
`infra/config/registry.json` the image repository (`repositoryName`). The workflow holds no domain, stack or
repository name of its own.

### publish (environment `staging`, `ubuntu-24.04-arm`)

1. ***Sign in to AWS through OpenID Connect***: `aws-actions/configure-aws-credentials` assumes the role in the
   environment secret `AWS_DEPLOY_ROLE_ARN`, in the region in the environment variable `AWS_REGION`, with
   `mask-aws-account-id: true`.
2. ***Deploy the image registry (BranchbookRegistry)***: without `BRANCHBOOK_ENV`, the CDK app holds only the
   registry, the ECR repository (`branchbook`) that both environments read: immutable tags, scan on push.
3. ***Load it, and check that it is the build the checks passed***: the `branchbook-image` artifact, whose image ID
   must equal the `image` job's output.
4. ***Push it to ECR as branchbook:\<commit\>***: the tag is the full commit SHA; the job's output is the pushed
   image's digest.
5. ***Attest the image's build provenance*** and ***its software bill of materials*** with `actions/attest`: a SLSA
   provenance statement and the CycloneDX SBOM, both signed through Sigstore and stored by GitHub against the digest.
   The attestations of a public repository are public, so their subject is named `branchbook`, not by the registry's
   host name, which holds the AWS account ID; they are not pushed to ECR for the same reason.

### deploy-staging (environment `staging`)

With `BRANCHBOOK_ENV` from the environment variable (`staging`) and `BRANCHBOOK_IMAGE_DIGEST` from `publish`:

1. ***Check that this commit is not older than the release staging runs*** ([release.sh](../.github/scripts/release.sh)
   `is-newer`): if `/branchbook-staging/release` names a commit that this one does not contain, the job deploys
   nothing, says so in a notice, and production is not offered.
2. ***Deploy staging***: `npx cdk deploy BranchbookStagingData BranchbookStagingApp --require-approval never --ci
   --progress events`, with the environment secret `ALERT_EMAIL`, if set, for the alarms.
3. ***Read the synthetic-traffic token*** from Secrets Manager (`<prefix>/synthetic-traffic-token`) and mask it.
   Staging's CAPTCHA rule lets requests that carry it in `x-branchbook-synthetic` through; production has no such
   token.
4. ***Smoke-test staging***: `scripts/smoke.sh` with `EXPECT_PROD_PROFILE=1`, `EXPECT_CAPTCHA=1`,
   `EXPECT_DEMO_NUMBERS_ONLY=1` and `SMOKE_SYNTHETIC_TOKEN`.
5. ***Run the end-to-end tests against staging***, with `E2E_SYNTHETIC_TOKEN`; the tests that need the simulated inbox
   skip themselves, because the `prod` profile turns it off.
6. ***Record the release*** (`npx cdk deploy BranchbookStagingRelease`) and ***verify*** it:
   `/branchbook-staging/release` must equal `GITHUB_SHA` and `/branchbook-staging/release-image` the digest.

### deploy-production (environment `production`)

The environment requires a reviewer's approval before the job starts. Then, with the same digest:

1. ***Verify the image's build provenance***: `gh attestation verify oci://<registry>/branchbook@<digest> --repo
   <this repository> --signer-workflow <this repository>/.github/workflows/ci.yml --source-ref refs/heads/main
   --deny-self-hosted-runners`. Production runs only an image this workflow built from `main` on a GitHub-hosted
   runner.
2. ***Check that this commit is not older than the release production runs***, as in staging.
3. ***Capture the release to roll back to***: `/branchbook/release` and `/branchbook/release-image` as they stand.
4. ***Deploy production***: `npx cdk deploy BranchbookData BranchbookApp`. The new task must pass its health check
   before the old one stops; one that never does is rolled back by the deployment circuit breaker, and the job fails.
5. ***Smoke-test production***: `scripts/smoke.sh` with `EXPECT_PROD_PROFILE=1` and `EXPECT_CAPTCHA=1`: an unsolved
   booking must be challenged, and the refusals that would need a token are skipped.
6. ***Record the release*** (`npx cdk deploy BranchbookRelease`) and ***verify*** both parameters.
7. ***Decide whether production must be rolled back***: if step 4 or 5 failed or was interrupted, the job's output
   `rollback` is `true`.

### rollback-production (environment `production`)

Runs only when `deploy-production` failed with `rollback` set. It checks out the previous release's commit beside
this one and deploys *its* CDK app with *its* digest, the pair that last passed the smoke test, so a fault in either
the infrastructure or the image is undone. First it checks that `/branchbook/release-image` still names that digest:
if a newer deployment recorded itself while this job waited, there is nothing to roll back. Then it smoke-tests
production with the previous release's smoke test and verifies that the record still names the previous release.
If nothing was recorded before (the first deployment by this pipeline), the job fails at once and says so, and
production is restored by hand ([infra/README.md](../infra/README.md)).

**What the record proves:** the commit and digest in `/<prefix>/release` and `/<prefix>/release-image` are live in
that environment and passed its smoke test, after every `ci` check passed on that commit; for production, a person
approved it and its provenance was verified. **What it does not prove:** that nothing has changed since. An
administrator can still run `cdk deploy` by hand from their own machine; nothing records that, and the next
deployment replaces it. How the trust works, and who can change what, is in
[infra/README.md](../infra/README.md#how-github-reaches-aws-without-keys) and
[Who can change what in AWS](../infra/README.md#who-can-change-what-in-aws).

**Evidence of the record's value.** The first deploy of the single-environment pipeline,
[3a31516](https://github.com/KatlehoKomeke/Appointment-Booking-System/actions/runs/39855902718), failed exactly at
*Verify the deployed commit*, with `AccessDeniedException` on `ssm:GetParameter`;
[3f842b1](https://github.com/KatlehoKomeke/Appointment-Booking-System/commit/3f842b1) granted the read, and every
deploy since passed. The job's runs are those of
[ci](https://github.com/KatlehoKomeke/Appointment-Booking-System/actions/workflows/ci.yml).

### Concurrency

| Group | Workflow or job | `cancel-in-progress` | Effect |
|---|---|---|---|
| `ci-<ref>` | `ci`, on a branch or pull request | `true` | A newer push to the same branch or pull request cancels the run in progress. |
| `ci-<run id>` | `ci`, on `main` | `false` | Each run on `main` is a group of its own, so a run that waits for a production approval never holds up the next commit's checks. |
| `publish` | `publish` | `false` | One push to ECR and one `BranchbookRegistry` deployment at a time. |
| `deploy-staging` | `deploy-staging` | `false` | One staging deployment at a time; one in progress is never cancelled. |
| `deploy-production` | `deploy-production` and `rollback-production` | `false` | One production change at a time, rollbacks included, so a rollback never runs beside a deployment. |
| `codeql-<ref>` | `codeql` | `true` on a pull request | A newer push to a pull request cancels its analysis. |
| `dependency-review-<ref>`, `secret-scan-<ref>` | those workflows | `true` | A newer push cancels the run in progress. |

GitHub keeps at most one job waiting in a group; a newer one replaces it ([Known gaps](#known-gaps)). Because runs on
`main` overlap, a slower run can reach a deployment group after a newer one; the order check then makes it deploy
nothing rather than replace a newer release. `aws-connection-check` has no concurrency group.

## Supply chain

| Control | Where | Proven by |
|---|---|---|
| Every action pinned to a full commit SHA, with its version in a comment, each version at least 14 days old when pinned | every workflow | zizmor's `unpinned-uses` and, online, `impostor-commit` and `ref-version-mismatch` audits in `workflow-lint` |
| Every tool image pinned by version and digest: Trivy, k6, OWASP ZAP, actionlint, zizmor, gitleaks | [.github/tools/compose.yaml](../.github/tools/compose.yaml) | Read by hand; Dependabot's `docker-compose` updates move tag and digest together |
| No token permission by default; each job asks for what it needs | every workflow | zizmor's `excessive-permissions` audit |
| No git credentials left in a checkout | every checkout (`persist-credentials: false`) | zizmor's `artipacked` audit |
| No dependency cache in a job that holds credentials or publishes | the delivery jobs (`package-manager-cache: false`); the image is built without a layer cache | zizmor's `cache-poisoning` audit |
| The image is built once and what was tested is what ships | `image`'s output ID, checked by `stack` and `publish`; delivery by digest | The ID checks in `stack` and `publish` |
| Known vulnerabilities with a fix fail the build | `image` (Trivy, HIGH and CRITICAL); ECR scans again on push | The Trivy step's exit code |
| A software bill of materials for every build, attested for every published image | `image` (CycloneDX, kept 90 days); `publish` (`actions/attest`, SBOM mode) | The `sbom` artifact; the attestation on the run's summary |
| Build provenance, verified before production | `publish` attests; `deploy-production` verifies against this repository, `ci.yml`, `refs/heads/main` and GitHub-hosted runners | `gh attestation verify` in `deploy-production` |
| New dependencies with high or critical advisories are refused | `dependency-review` | Its required check |
| Static analysis of the code and the workflows | `codeql` | Its required checks and code-scanning alerts |
| Dynamic scan of the running app | `stack` (OWASP ZAP baseline) | The ZAP step's exit code |
| Workflows lint-clean, shell included | `workflow-lint` (actionlint with shellcheck, zizmor) | Its required check |

## Keeping secrets out

Secrets meet three checks: the pre-commit hook and push protection, which refuse them, and `secret-scan`, which
reports them after the push. The pipeline holds no AWS key of its own, because it signs in through OIDC; the only
secret it reads at run time, staging's synthetic-traffic token, is masked before it is used, and the database password
of the `stack` job is generated per run and masked. [SECURITY.md](../SECURITY.md#secrets) owns the policy, the
layered picture and what to do if a secret is committed.

## Dependency updates

[.github/dependabot.yml](../.github/dependabot.yml) checks every ecosystem weekly, and waits 14 days after a release
before proposing it (`cooldown: default-days: 14`), the same supply-chain rule the repository applies to every pin by
hand. For Maven, npm and GitHub Actions, minor and patch updates are grouped into one pull request per directory; the
two Docker ecosystems have no group, so each image gets its own pull request. A major version always gets its own pull
request. For a SHA-pinned action, Dependabot moves the SHA and the version comment together; for an image pinned by
tag and digest, both.

| Ecosystem | Directory | Group | Majors ignored |
|---|---|---|---|
| `maven` | `/api` | `api-minor-and-patch` | none |
| `npm` | `/web` | `web-minor-and-patch` | `typescript`, `@types/node` |
| `npm` | `/infra` | `infra-minor-and-patch` | `typescript`, `@types/node` |
| `npm` | `/e2e` | `e2e-minor-and-patch` | `typescript`, `@types/node` |
| `docker` | `/` (the Dockerfile) | none: one pull request per image | `eclipse-temurin`, `node` |
| `docker-compose` | `/` (compose.yaml) and `/.github/tools` (the CI tool images) | none | `postgres` |
| `github-actions` | `/` | `actions-minor-and-patch` | none |

The ignored majors are deliberate: Java and Node.js stay on their LTS lines, TypeScript stays on 6.x for
typescript-eslint, and PostgreSQL stays on 17, the version the Amazon RDS database runs
([ADR-0006](adr/0006-hosted-demo-on-aws-with-cdk.md)). Dependabot alerts and security updates are also on
([GitHub settings](#github-settings-this-relies-on)).

Three pins are invisible to Dependabot and are updated by hand:

- `postgres:17.11-alpine` in [TestcontainersConfiguration.java](../api/src/test/java/za/co/fynbos/branchbook/support/TestcontainersConfiguration.java).
  Dependabot updates the same image in compose.yaml, so move this one in the same pull request.
- gitleaks `v8.30.1` in [.githooks/pre-commit](../.githooks/pre-commit) and the [Makefile](../Makefile). Dependabot
  moves the one in [.github/tools/compose.yaml](../.github/tools/compose.yaml); move these two in the same pull
  request.
- The Dockerfile's build frontend, `# syntax=docker/dockerfile:1`, which BuildKit resolves by its floating tag at
  build time.

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

The pipeline above needs the settings in this table. Re-run every check once they are in place, and after any change
to them, and record the date and commit below the table.

| Setting | Required | Check with | Readable by |
|---|---|---|---|
| Default branch | `main` | `gh api $R --jq .default_branch` | anyone |
| Rulesets | `Protect main`, on the default branch: refuses force-pushes and deletion, with no bypass; requires the status checks `api`, `web`, `infra`, `workflow-lint`, `image` and `stack` (`ci`), `analyze (java-kotlin)`, `analyze (javascript-typescript)` and `analyze (actions)` (`codeql`), `dependency-review` and `gitleaks` (`secret-scan`), all from GitHub Actions, not strict; and a code-scanning rule that blocks a merge on a CodeQL security alert of high severity or more | `gh api $R/rules/branches/main --jq '[.[].type]'` lists `non_fast_forward`, `deletion`, `required_status_checks` and `code_scanning`; `gh api $R/rules/branches/main --jq '.[] \| select(.type=="required_status_checks") \| .parameters'` lists the eleven contexts | anyone |
| Environment `staging` | Deployment branches: `main` only (no tags); no required reviewers | `gh api $R/environments/staging --jq '{rules: [.protection_rules[].type], can_admins_bypass}'` and `gh api $R/environments/staging/deployment-branch-policies --jq '.branch_policies[] \| {name, type}'` | anyone |
| Environment `production` | Deployment branches: `main` only; one required reviewer; administrators may not bypass | The same two calls for `production`; `rules` includes `required_reviewers` and `can_admins_bypass` is `false` | anyone |
| Environment secrets | In each: `AWS_DEPLOY_ROLE_ARN` (that environment's role, `branchbook-github-deploy-staging` or `-production`), and `ALERT_EMAIL` if the alarms should e-mail someone. No repository-level Actions secrets | `gh api $R/environments/staging/secrets --jq '.secrets[].name'`, the same for `production`; `gh api $R/actions/secrets --jq .total_count` prints `0` | owner |
| Environment variables | In each: `AWS_REGION` (`af-south-1`) and `BRANCHBOOK_ENV` (`staging` or `production`) | `gh api $R/environments/staging/variables --jq '.variables[] \| {name, value}'`, the same for `production` | owner |
| Environment `aws-demo` | Deleted, with its secret, once `BranchbookAccount` no longer trusts it | `gh api $R/environments --jq '[.environments[].name]'` prints `["production","staging"]` | anyone |
| OIDC subject | GitHub's immutable subject, with numeric owner and repository IDs | `gh api $R/actions/oidc/customization/sub --jq .use_immutable_subject` prints `true` | anyone |
| Secret scanning and push protection | On; non-provider patterns and validity checks off | `gh api $R --jq .security_and_analysis` | owner |
| Dependency graph, Dependabot alerts and security updates | On (the dependency graph is what `dependency-review` reads) | `gh api $R/vulnerability-alerts --silent && echo on`; `gh api $R/automated-security-fixes --jq .enabled` | owner |
| Code scanning | Advanced setup through `codeql.yml`; default setup off, or the two would conflict | `gh api $R/code-scanning/default-setup --jq .state` prints `not-configured` | owner |
| Artifact attestations | Available on public repositories without a setting; `publish` writes them | `gh api $R/attestations/<digest> --jq '.attestations \| length'` | anyone |
| Private vulnerability reporting | On | `gh api $R/private-vulnerability-reporting --jq .enabled` | anyone |
| Actions | All actions allowed; actions must be pinned to a full-length commit SHA | `gh api $R/actions/permissions --jq '{allowed_actions, sha_pinning_required}'` | owner |
| Default `GITHUB_TOKEN` | Read-only; cannot approve pull requests | `gh api $R/actions/permissions/workflow` | owner |

Not yet read back after this change. "Anyone" means the endpoint is public, so any GitHub account can run the check;
the others answer only the repository's administrators.

## Known gaps

Each gap is stated here once, with what limits it today and what would close it. Closed by this pipeline: an image
rebuilt at deploy time rather than the one tested; actions pinned by a movable tag; one environment, so every commit
went straight to the public address; no human gate before it; no automatic rollback; no static analysis, dependency
review, image scan, SBOM, provenance or dynamic scan; and a manual run from a `v*` tag that would deploy that tag.

| Gap | Effect today | What would close it |
|---|---|---|
| Only the head of each push is built | A broken commit in the middle of a push goes unnoticed by `ci`. `secret-scan` still reads every commit. | Run `make test` before each push; with a team, one pull request per change. |
| The required checks are not strict | A pull request whose checks passed on a branch behind `main` can merge without being rebuilt on the merge result. The push to `main` then builds the merge, and a red check there means no delivery, but the commit is already on `main`. | Strict checks in the ruleset, so a pull request must be up to date with `main` before it merges. |
| A rollback waits for an approval | GitHub applies an environment's required reviewers to every job that enters it, and the production role trusts that environment alone, so `rollback-production` waits for the same reviewer. Until then production runs whatever the failed deployment left: after a failed `cdk deploy`, CloudFormation has already restored the previous template; after a failed smoke test, the new image. | A separate rollback environment without reviewers, whose role may only redeploy a digest already recorded; or the rollback as steps inside `deploy-production`. |
| A waiting deployment can be replaced | GitHub keeps one job waiting per concurrency group. With three pushes in quick succession, the third's job replaces the second's while it waits, so the second is never deployed; the newest always is, and none is cancelled once started. | Accept it: the newest commit contains the replaced one. |
| The first production deployment cannot roll back automatically | `/branchbook/release-image` is first written by this pipeline, so until one production deployment succeeds there is no digest to return to, and `rollback-production` stops and says so. | Nothing after the first success. |
| The trust subject names no workflow | Any workflow run from `main` that declares `staging` or `production` and requests an OIDC token can assume that environment's role: today `ci.yml`'s delivery jobs and `aws-connection-check.yml`. | `CODEOWNERS` and a required review on `.github/`, or `job_workflow_ref` in the OIDC subject and the trust policy. |
| The pipeline's self-access guard lives in code | `BranchbookAccount` and `BranchbookGuardrails` stay out of the pipeline's reach because the CDK app omits them unless `BRANCHBOOK_ALERT_EMAIL` or `BRANCHBOOK_GUARDRAILS` is set, and only the credential-free `infra` job sets them. IAM does not forbid it: the bootstrap roles a deploy role may assume can update any stack, and a change to `ci.yml` needs no review. | The options in [infra/README.md](../infra/README.md#who-can-change-what-in-aws). |
| Attestations are verified online only | They are stored by GitHub, not pushed to ECR, because the registry's host name holds the account ID and attestations of a public repository are public. Verifying one needs GitHub's API. | A registry host without the account ID in it, then `push-to-registry` and offline verification in AWS. |
| The ZAP baseline sees what its spider finds | A passive scan of the app shell, its assets and `robots.txt`; the single-page app's API calls are not crawled, and nothing is attacked actively. The smoke test checks the API's headers and refusals instead. | Proxy the end-to-end tests through ZAP, or an API scan from `docs/openapi.yaml` against a disposable stack. |
| Unfixed vulnerabilities do not fail the build | Trivy fails only on a HIGH or CRITICAL vulnerability with a fixed version; the rest are visible in ECR's scan of the published image. | A reviewed exceptions file and a failing gate on all of them. |
| A code-scanning alert blocks only through the ruleset | The `analyze` jobs pass whatever CodeQL finds; the ruleset's code-scanning rule is what blocks a merge. | Keep the rule in the ruleset ([GitHub settings](#github-settings-this-relies-on)). |
| `secret-scan` reports after publication | In a public repository, a secret that reaches GitHub is already exposed when the scan finds it; as a required check it stops the merge, not the push. | The pre-commit hook and push protection can stop a secret before it is published; a finding means rotating first ([SECURITY.md](../SECURITY.md#if-a-secret-is-committed)). |
| Push protection knows provider patterns only | The two custom rules in [.gitleaks.toml](../.gitleaks.toml), a PostgreSQL URL with a password and an ARN with an account ID, are checked only by the hook and `secret-scan`. | Custom secret-scanning patterns that mirror those rules, with push protection on for them. |
| Three pins Dependabot cannot see | The Testcontainers PostgreSQL image, the hook's and Makefile's gitleaks, and the Dockerfile's build frontend fall behind silently. | Update them by hand ([Dependency updates](#dependency-updates)); pin the frontend by digest. |

## Run the same checks locally

| CI job | Local equivalent | Difference |
|---|---|---|
| `ci / api` | `make test-api` | The same, including the New York pass. |
| `ci / web` | `make test-web` | The tests run once, in your time zone. |
| The test-reference check in `ci / api` | `make test-docs` | The same script. |
| `ci / infra` | `cd infra && npm ci && npm run lint && npm run build && npm test` | There is no make target. `ci` also synthesises both environments ([The checks in each ci job](#the-checks-in-each-ci-job)). |
| `ci / workflow-lint` | `docker compose --file .github/tools/compose.yaml run --rm actionlint`, then the same with `zizmor` (`GH_TOKEN="$(gh auth token)"` for the online audits) | The same images. |
| `ci / image` | `docker build --platform linux/arm64 --tag branchbook:ci .`, `docker save --output branchbook-image.tar branchbook:ci`, then the two `trivy` commands of the job through `.github/tools/compose.yaml` | The first scan downloads Trivy's databases, which can take minutes. |
| `ci / stack` | `make up && make smoke && make e2e && make demo-409` | `make up` builds the image rather than loading the `image` job's, the smoke test runs once, a failed end-to-end test is not retried, and k6, ZAP and the log check do not run. |
| `secret-scan / gitleaks` | `make secrets-scan-history` | The same command. `make secrets-scan` scans the working tree instead, untracked files included. |
| `ci` delivery | None. The nearest is the administrator's `cdk deploy` from `infra/` ([infra/README.md](../infra/README.md#deploying-and-updating-it)). | A hand deployment runs no smoke test, verifies no attestation and writes no release record; only the pipeline records what is live. |

`make test` runs `test-api`, `test-web` and `test-docs` in turn ([Makefile](../Makefile)).

## Keeping this page true

This page and [delivery-pipeline.svg](assets/diagrams/delivery-pipeline.svg) own these paths: `.github/**` (the
workflows, `dependabot.yml`, `tools/compose.yaml`, `scripts/` and `zap/rules.tsv`), `.githooks/**`,
`.gitleaks.toml`, the `hooks`, `secrets-scan` and `secrets-scan-history` targets in the [Makefile](../Makefile),
[scripts/major-commits.sh](../scripts/major-commits.sh), and what the delivery jobs run: [scripts/smoke.sh](../scripts/smoke.sh),
the e2e suite's switches, and the settings [read-config.sh](../.github/scripts/read-config.sh) reads from
`infra/config/`. A commit that
changes any of them updates this page and the diagram in the same commit. The ruleset's required checks name the jobs
by id (and `codeql`'s by matrix entry), so a job renamed or added in a workflow is renamed or added in the ruleset in
the same change, or `main` waits for a check that never reports. A change to a GitHub setting produces no commit, so
the checks above are re-run at the end of every milestone. The diagram index is in
[docs/assets/diagrams/README.md](assets/diagrams/README.md).
