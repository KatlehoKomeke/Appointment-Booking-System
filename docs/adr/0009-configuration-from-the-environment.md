# ADR-0009: Configuration comes from the environment, validated

- **Status:** Accepted
- **Date:** 2026-10-30

## Context

With two environments ([ADR-0008](0008-two-environments-promoted-by-digest.md)) the same image and the same CDK code
must run with different sizes, limits and switches. Before this decision some values were literals in the stacks or
in the code, the hosted demo was configured by environment variables written into the task definition one by one, and
nothing stopped a production deployment with a setting that suits only a demo: a Single-AZ database, an inbox that
lists references, or a CAPTCHA bypass. A wrong value must fail before anything is deployed, not show up in public.

## Decision

- **The application.** Every tunable value in `application.yml` reads a named environment variable with a safe
  default (`${NAME:default}`). [docs/configuration.md](../configuration.md) lists each setting with its variable and
  its value in each environment, and `ConfigurationReferenceTest` fails the build when a property or its variable is
  missing from that page.
- **The `prod` profile fails closed.** Staging and production run `SPRING_PROFILES_ACTIVE=prod`
  ([application-prod.yml](../../api/src/main/resources/application-prod.yml)). `ProductionProfileGuard` refuses to
  start, before anything connects or listens, if an environment variable undoes a safe value: the inbox on without
  redacted references, demo data on, a public base URL that is not `https`, the SMS cap on without its key, or the
  actuator on the public port.
- **The infrastructure.** One JSON file per environment, [infra/config/staging.json](../../infra/config/staging.json)
  and [production.json](../../infra/config/production.json), plus
  [account.json](../../infra/config/account.json) and [registry.json](../../infra/config/registry.json).
  [lib/config.ts](../../infra/lib/config.ts) checks every field of the environment and registry files, and
  [lib/account-stack.ts](../../infra/lib/account-stack.ts) those of the account file, before any stack is built:
  present, of the right type, in range, and no unknown field, so a misspelling never falls back to a default. The
  stacks hold no size,
  count, limit, threshold, retention period or name of their own.
- **Production-only rules in the validator.** Production must run the database Multi-AZ with deletion protection,
  keep at least two tasks, have one NAT gateway per zone, and let no synthetic traffic past the CAPTCHA.
- **Secrets are never configuration.** They come from Secrets Manager or IAM. The validator refuses an
  `appEnvironment` name that looks like a secret (ending in `PASSWORD`, `SECRET`, `TOKEN`, `CREDENTIALS` or `_KEY`)
  and any variable the stack computes itself.

Where each rule lives: [docs/configuration.md](../configuration.md) for the application, and
[infra/README.md](../../infra/README.md#configuration) for the infrastructure.

## Alternatives considered

- **A runtime configuration service** (AWS AppConfig, SSM Parameter Store reads at start-up, Spring Cloud Config). It
  adds moving parts on the start-up path, and a value changed in a console has no history in the repository and goes
  through no pull request.
- **CDK context.** Unvalidated, and mixed with CDK's own feature flags in `cdk.json`.
- **Constants in code.** A change of size or limit would be a code change, and the two environments could not differ.

## Consequences

- Every value an operator can change is in one file per environment, versioned with the code, and goes through the
  same checks as a code change.
- A wrong or unsafe value fails `cdk synth` in the `infra` job, naming the file and the field, or fails the task's
  start-up under `prod`, which the deployment's circuit breaker then rolls back.
- A new setting needs three edits in one commit: the property with its variable and comment, its row in
  [docs/configuration.md](../configuration.md), and, if it differs per environment, the environment files. The
  build fails if one is forgotten.
- Changing a value takes a deployment; nothing can be changed live. That is intended.
- Tests: `ConfigurationReferenceTest`, `ProductionProfileGuardTest`, `ProdProfileIT` and
  [config.test.ts](../../infra/test/config.test.ts).
