# ADR-0007: Continuous deployment from main

- **Status:** Accepted. Supersedes the delivery trigger in [ADR-0006](0006-hosted-demo-on-aws-with-cdk.md).
- **Date:** 2026-10-22

## Context

[ADR-0006](0006-hosted-demo-on-aws-with-cdk.md) planned to deploy only on release tags. The project owner wants every
commit to `main` to reach AWS automatically, so the hosted environment always shows the latest work and a broken
deployment is noticed on the commit that caused it.

## Decision

- **Every push to `main` deploys.** The `deploy` workflow runs the infrastructure tests, then deploys the workload
  stacks with `cdk deploy`, then reads back the recorded release to prove the pushed commit is live.
- **Access is keyless and narrow.** The deploy job signs in through OpenID Connect as `branchbook-github-deploy`.
  That role trusts only this repository, by its immutable IDs, and only jobs in the `aws-demo` environment, which
  accepts deployments from `main` and `v*` tags only.
- **The pipeline cannot change its own access.** `BranchbookAccount`, the stack that defines the deploy role, is only
  part of the CDK app when an administrator supplies `BRANCHBOOK_ALERT_EMAIL`. The pipeline never does, so it cannot
  synthesise that stack, let alone deploy it.
- **Deployments queue.** A second push waits for the first deployment to finish; nothing is cancelled half-way.
- **First workload stack:** `BranchbookRelease` records the deployed commit in the SSM parameter `/branchbook/release`
  in `af-south-1`. The application stacks join the same job when the application exists.

## Alternatives considered

- **Release tags only.** Safer for a production bank, where a release would also pass a change review, but slower
  feedback for a one-person assessment. Tags remain allowed in `aws-demo`, so this can be reinstated by changing the
  trigger.
- **Deploy from a pull request.** Pull requests from forks must never receive AWS credentials; deploying only from
  `main` avoids the question entirely.
- **Let the pipeline manage `BranchbookAccount` too.** A pipeline that can rewrite the trust policy of its own role
  can widen its own access; an administrator deploys that stack instead.

## Consequences

- `main` must always be deployable: a red test blocks the deployment, and a failed deployment fails the commit's checks.
- The public deploy logs show which commit went live but never the AWS account ID.
- When the application stacks are added, their deployment and smoke test extend the same job, and the release record
  moves to the end of it so it only changes once the app is healthy.

## Notes

**2026-10-26.** Facts that need stating more precisely since the record was accepted. The decision stands; the gaps
below are recorded with what would close them in [docs/delivery.md](../delivery.md#known-gaps).

- **Which tests block a deployment.** *No longer applies; see the note of 2026-10-27.* The `test` job in
  `deploy.yml` runs only the CDK app's lint, type check and tests, so "a red test
  blocks the deployment" holds for those. `ci.yml` runs separately and does not gate `deploy` yet.
- **How deployments queue.** The concurrency group `deploy-aws` has `cancel-in-progress: false`, and GitHub keeps
  at most one pending run per group: a running deployment is never cancelled, but a newer push replaces a deployment
  that is still waiting.
- **What deploys today.** *The first sentence no longer applies; see the note of 2026-10-27.* The only workload
  stack is `BranchbookRelease`. No workflow deploys on a tag push, and
  `aws-demo` has no required reviewers: its only rule is the branch and tag policy (`main` and `v*`).
- **The trust names no workflow.** The role's subject is the repository and the `aws-demo` environment, so any
  workflow job in that environment, run from `main` or a `v*` tag, can assume it; `aws-connection-check.yml` does,
  by hand.
- **"The pipeline cannot change its own access"** is enforced by the CDK app and `deploy.yml`, not by IAM: the
  role's permissions do not themselves stop a `BranchbookAccount` deployment
  ([infra/README.md](../../infra/README.md#who-can-change-what-in-aws)).

**2026-10-27.** The application stacks joined the job, as the Consequences foresaw. `deploy` is now a job of
[ci.yml](../../.github/workflows/ci.yml) that `needs` `api`, `web`, `stack` and `infra`; it builds the ARM64 image on
an ARM runner, deploys `BranchbookData` and `BranchbookApp`, smoke-tests https://branchbook.click, and only then
deploys `BranchbookRelease` and reads the record back, so the record names a commit that is live and smoke-tested.
The `Protect main` ruleset requires those four checks and `gitleaks` before a push or merge, so "a red test blocks the
deployment" now holds for every test in the repository. `deploy.yml` and `infra.yml` are deleted. Of the notes of
2026-10-26: *Which tests block a deployment* and the first sentence of *What deploys today* no longer apply; *How
deployments queue* holds for the `deploy` job (group `deploy-aws`), and `ci` as a whole now queues on `main` too;
*The trust names no workflow* holds, with `ci.yml` in place of `deploy.yml`; and *"The pipeline cannot change its own
access"* holds unchanged, `ci.yml` never setting `BRANCHBOOK_ALERT_EMAIL`. The gaps that remain, among them that the
checks are not strict and that a manual run from a `v*` tag would deploy it, are in
[docs/delivery.md](../delivery.md#known-gaps).
