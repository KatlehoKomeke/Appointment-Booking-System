# ADR-0008: Two environments, the image built once and promoted by digest

- **Status:** Accepted. Supersedes in part [ADR-0006](0006-hosted-demo-on-aws-with-cdk.md), whose single hosted demo
  becomes production, and [ADR-0007](0007-continuous-deployment-from-main.md), whose push to `main` now deploys to
  staging, and to production only after a person approves it.
- **Date:** 2026-10-30

## Context

Under [ADR-0007](0007-continuous-deployment-from-main.md) every push to `main` went straight to the one public
address, and the `deploy` job built the image during `cdk deploy`. So the image that ran in AWS was not the image the
tests had run, nothing stood between a green check and the public address, and a deployment that broke after it
passed CloudFormation stayed broken until someone pushed a fix. A bank releases an artifact it has tested, to a
staging environment first, with a person's approval before production and a way back.

## Decision

- **Two environments in one AWS account:** staging at https://stg.branchbook.click and production at
  https://branchbook.click, both in `af-south-1`. `BRANCHBOOK_ENV` selects one, and only its stacks exist in the CDK
  app ([infra/README.md](../../infra/README.md#environments-staging-and-production)).
- **Built once.** `ci`'s `image` job builds the ARM64 image once; the `stack` job runs the end-to-end tests against
  that exact build, and Trivy and an SBOM cover it. Nothing rebuilds it later.
- **Promoted by digest.** `publish` pushes it to ECR (`branchbook`, immutable tags) as `branchbook:<commit>` and
  attests its build provenance and SBOM. Each environment deploys it by digest (`BRANCHBOOK_IMAGE_DIGEST`).
- **Staging first, production on approval.** `deploy-staging` deploys the digest and runs the smoke and end-to-end
  tests there. `deploy-production` waits for a person to approve it in the `production` GitHub environment, verifies
  the provenance with `gh attestation verify`, and deploys the same digest.
- **Recorded only once proven.** Each environment's release record (`/<prefix>/release` and
  `/<prefix>/release-image`) is written only after its smoke test passes, and in staging its end-to-end tests too.
- **Automatic rollback.** If production's deployment or smoke test fails, `rollback-production` redeploys the last
  recorded release: its commit's CDK app and its digest.
- **In-account separation.** Each environment has its own CDK bootstrap qualifier (production `bbprod`, staging
  `bbstg`; never CDK's default, which other CDK apps in the account share), its own CloudFormation execution policy, a deploy-scope deny on the other environment's stacks, and its
  own deploy role, which trusts only its own GitHub environment (`branchbook-github-deploy-staging`,
  `branchbook-github-deploy-production`). Every role the application stacks create carries a permissions boundary.

The steps of each job are in [docs/delivery.md](../delivery.md#continuous-deployment).

## Alternatives considered

- **Rebuilding per environment.** Production would run an artifact no test ran.
- **Separate AWS accounts per environment.** The strongest isolation, and the right answer for a real bank. Rejected
  for this demo account's cost and setup time; the per-environment bootstrap, execution policy, deploy scope and
  deploy role above are the in-account mitigation, and the residual risk is in
  [SECURITY.md](../../SECURITY.md#known-risks).
- **CDK Pipelines or CodePipeline.** GitHub Actions with OpenID Connect was already in place and needs no extra AWS
  service.

## Consequences

- What production runs is exactly what staging tested: the same digest, verified as built by `ci.yml` from `main` on
  a GitHub-hosted runner.
- A push to `main` takes longer: two deployments, the end-to-end tests against staging, and the time a person takes
  to approve production.
- The automatic rollback waits for the same approval, because the production role trusts only the `production`
  environment. During a deployment, ECS's circuit breaker and the 5xx alarm roll back on their own.
- The first production deployment by this pipeline has no recorded digest to roll back to; the rollback job says so
  and stops.
- Attestations are verified online and not stored in ECR, because the registry's host name holds the account ID.
- Staging costs money every month it runs. Both environments share one account, which is recorded as a residual
  risk in [SECURITY.md](../../SECURITY.md#known-risks). The controls and their evidence are in
  [production-readiness.md](../production-readiness.md), and the failure modes a rollback covers in
  [resilience.md](../resilience.md).

## Notes

**2026-11-01, the first run stops at staging.** The pipeline's first run, at `ab9aa7f`, passed every check, and
`publish` pushed the image to `BranchbookRegistry`. `deploy-staging` created `BranchbookStagingData`, then
`BranchbookStagingApp` failed and rolled back: staging's CloudFormation execution role lacked
`cloudformation:DescribeStacks`, which CloudFormation needs to resolve the App stack's `Fn::GetStackOutput` references
to the Data stack, because [cdk.json](../../infra/cdk.json) makes cross-stack references weak. `deploy-production` was
skipped; production was not touched.

**2026-11-02, the fix is merged.** [Pull request
#43](https://github.com/KatlehoKomeke/Appointment-Booking-System/pull/43) lets each execution policy describe its own
environment's stacks (`ReadOwnStackOutputs`), with a test. It takes effect when an administrator redeploys
`BranchbookGuardrails`; the next run then deploys staging and, after a person's approval, production. That first
production deployment has no recorded release to roll back to, and narrowing production's bootstrap to its execution
policy follows it ([infra/README.md](../../infra/README.md#hardening-the-account-that-already-runs-production), step 5;
[deployment status](../production-readiness.md#deployment-status)).

**2026-11-02, both environments deployed.** The administrator redeployed `BranchbookGuardrails`, so #43 took effect.
Two more problems surfaced and were fixed: the app could not create Tomcat's temporary directory on the `/tmp` volume
Fargate mounts root-owned ([pull request #48](https://github.com/KatlehoKomeke/Appointment-Booking-System/pull/48),
`d6471c0`), and a failed first create of `BranchbookStagingApp` left its log group behind, which was deleted by hand
([runbook](../runbook.md#a-first-deployment-fails-the-log-group-already-exists)). Pipeline run 41498174936 then
deployed one digest to staging at 18:42Z, where the smoke and end-to-end tests passed, and, after a person's approval,
to production at 19:15Z, where the smoke test passed with the CAPTCHA expected. Production has a recorded release to
roll back to. The administrator then narrowed production's bootstrap to `branchbook-cdk-cfn-exec-production`; the
first production deployment through it is #49's (`38d0a4d`)
([deployment status](../production-readiness.md#deployment-status)).
