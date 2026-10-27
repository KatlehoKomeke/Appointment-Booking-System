# infra: AWS infrastructure as code

Every resource the pipeline and the application use is defined here with the
[AWS CDK](https://docs.aws.amazon.com/cdk/v2/guide/home.html) in TypeScript. The account, its administrator, the
Cape Town opt-in, the CDK bootstrap and the GitHub environment are one-time manual steps, listed under
[First-time setup](#first-time-setup). The reasoning is in [ADR-0006](../docs/adr/0006-hosted-demo-on-aws-with-cdk.md)
and [ADR-0007](../docs/adr/0007-continuous-deployment-from-main.md).

- [What exists today](#what-exists-today)
- [Automatic deployment](#automatic-deployment)
- [How GitHub reaches AWS without keys](#how-github-reaches-aws-without-keys)
- [Who can change what in AWS](#who-can-change-what-in-aws)
- [The hosted demo](#the-hosted-demo)
- [Commands](#commands), [First-time setup](#first-time-setup) and [Removing it](#removing-it)

## What exists today

*Status: Built: four stacks deployed. The pipeline deploys the release record; the administrator deploys the rest by
hand, the hosted demo first on 2026-10-26.*

![BranchBook in AWS, all built: the administrator's account stack in us-east-1 (OIDC provider, narrow deploy role, $90 budget), the pipeline's release record in Cape Town, and the hand-deployed demo there, where Route 53, WAF and a load balancer front one Fargate task that uses RDS and texts only South African numbers through AWS End User Messaging.](../docs/assets/diagrams/aws-footprint.svg)

**A.** The administrator runs `cdk deploy` by hand from their own machine: `BranchbookAccount` in `us-east-1`, and
`BranchbookData` and `BranchbookApp` in `af-south-1`, which also pushes the ARM64 image to the bootstrap ECR
repository. The pipeline deploys none of these.

1. On every push to `main`, the `deploy` job in [deploy.yml](../.github/workflows/deploy.yml) enters the GitHub
   environment `aws-demo` and signs in to AWS through OpenID Connect, via AWS STS, as `branchbook-github-deploy`.
   No access key is stored.
2. The deploy role assumes the CDK bootstrap roles (`cdk-hnb659fds-*`) in `af-south-1`.
3. CloudFormation updates the `BranchbookRelease` stack.
4. The job reads `/branchbook/release` back and fails unless it equals `GITHUB_SHA`.

| Stack and Region | Deployed by | What it creates |
|---|---|---|
| [`BranchbookAccount`](lib/account-stack.ts)<br>`us-east-1` | Administrator, by hand | The GitHub OIDC provider, the role `branchbook-github-deploy`, the budget `branchbook-monthly` and the output `DeployRoleArn` |
| [`BranchbookRelease`](lib/release-stack.ts)<br>`af-south-1` | Pipeline, every push to `main` | The SSM parameter `/branchbook/release`: the last commit the pipeline deployed |
| [`BranchbookData`](lib/data-stack.ts)<br>`af-south-1` | Administrator, by hand | The hosted demo's VPC and its RDS for PostgreSQL 17 database ([The hosted demo](#the-hosted-demo)) |
| [`BranchbookApp`](lib/app-stack.ts)<br>`af-south-1` | Administrator, by hand | The hosted demo's certificate, WAF, load balancer, Fargate task, SMS protect configuration, logs, alarms and DNS record |
| `CDKToolkit`<br>both Regions | Administrator, once per Region | The `cdk bootstrap` stack, qualifier `hnb659fds`: the deploy, file-publishing, image-publishing, lookup and cfn-exec roles, an assets bucket and an ECR repository |

- **Cost.** `BranchbookAccount` and `BranchbookRelease` cost nothing to run; the bootstrap storage costs cents. The
  hosted demo costs about $82 a month while it runs. The budget e-mails at 50, 80 and 100 percent of $90 a month
  ($45, $72 and $90).
- **Why `us-east-1`.** IAM is global, AWS Budgets lives in `us-east-1`, and the stack holds no customer data.
  Everything that holds customer data is in Cape Town.
- **The GitHub side.** The environment `aws-demo`, which admits only the branch `main` and `v*` tags, holds the secret
  `AWS_DEPLOY_ROLE_ARN`. The [infra](../.github/workflows/infra.yml) workflow tests and synthesises this app, every
  stack included, on every change to `infra/`, with placeholder inputs and no AWS access.

## Automatic deployment

Every push to `main`, and any manual run of the workflow from `main`, runs
[.github/workflows/deploy.yml](../.github/workflows/deploy.yml)
([ADR-0007](../docs/adr/0007-continuous-deployment-from-main.md)):

1. **Test.** Lint, type-check and unit-test the CDK app; nothing reaches AWS if this fails. These are the only
   checks that gate the deploy: the application's CI ([ci.yml](../.github/workflows/ci.yml)) runs separately and is
   not consulted ([docs/delivery.md](../docs/delivery.md#known-gaps)).
2. **Deploy.** Sign in to AWS through OpenID Connect as `branchbook-github-deploy`, then `cdk deploy
   BranchbookRelease`, the only stack the pipeline deploys; the hosted demo is deployed by hand
   ([The hosted demo](#the-hosted-demo)). CDK works through the `af-south-1` bootstrap roles: the
   file-publishing role uploads the template, and the deploy role hands the change set to CloudFormation, which
   applies it as the cfn-exec role.
3. **Verify.** Read `/branchbook/release` back from AWS and fail unless it equals the pushed commit. A passing run
   ends with the log line `AWS af-south-1 now records commit <sha>`.

To see what is live, read the parameter:

```bash
aws ssm get-parameter --name /branchbook/release \
  --query Parameter.Value --output text --profile branchbook-admin
```

The deploy job step by step, what the read-back proves and what it does not, and how runs queue are in
[docs/delivery.md](../docs/delivery.md#continuous-deployment).

## How GitHub reaches AWS without keys

```mermaid
sequenceDiagram
    autonumber
    participant Job as deploy job<br/>(aws-demo)
    participant Issuer as GitHub<br/>OIDC issuer
    participant STS as AWS STS
    participant CFN as CloudFormation, through<br/>the bootstrap roles (af-south-1)
    participant SSM as /branchbook/release
    Note over Job: only main or a v* tag gets in
    Job->>Issuer: ID token for sts.amazonaws.com
    Issuer-->>Job: signed token for this repo and aws-demo
    Job->>STS: AssumeRoleWithWebIdentity
    Note over STS: aud and sub must match exactly
    STS-->>Job: branchbook-github-deploy, at most 1 hour
    Job->>CFN: cdk deploy BranchbookRelease
    CFN->>SSM: set the value to GITHUB_SHA
    Job->>SSM: ssm:GetParameter
    SSM-->>Job: the recorded commit
    Note over Job: fail unless it equals GITHUB_SHA
```

Everything in this diagram is built. Solid arrows are calls; dashed arrows are their replies.

- **No access key exists anywhere.** The job takes the ARN of `branchbook-github-deploy` from the environment secret
  `AWS_DEPLOY_ROLE_ARN`.
  GitHub proves the job's identity with a short-lived token, and AWS answers with credentials that expire within an
  hour (`maxSessionDuration` in [account-stack.ts](lib/account-stack.ts)).
- **The trust is narrow.** The trust policy requires, with `StringEquals`, the audience `sts.amazonaws.com` and
  GitHub's immutable subject
  `repo:KatlehoKomeke@88820937/Appointment-Booking-System@1423867330:environment:aws-demo`. It carries the numeric
  owner and repository IDs, so forks, other repositories and a repository recreated later under the same name cannot
  match. The IDs are public GitHub identifiers, kept in [cdk.json](cdk.json). The repository's subject setting can be
  read with `gh api repos/KatlehoKomeke/Appointment-Booking-System/actions/oidc/customization/sub`.
- **The environment decides which refs get in.** `aws-demo` admits only the branch `main` and `v*` tags. It has no
  required reviewers, and repository administrators may bypass its rules. Its branch policy is what stops a job on a
  feature branch from declaring the environment and matching the trust. Check it with
  `gh api repos/KatlehoKomeke/Appointment-Booking-System/environments/aws-demo/deployment-branch-policies`.
- **The subject names no workflow.** Any workflow on `main` that declares `aws-demo` can assume the role: today
  [deploy.yml](../.github/workflows/deploy.yml) and [aws-connection-check.yml](../.github/workflows/aws-connection-check.yml).
- **The role can do almost nothing itself.** It may assume the roles that `cdk bootstrap` created, which carry out
  deployments, and read the single release parameter. [account-stack.test.ts](test/account-stack.test.ts) fails if
  the stack's IAM policies gain a statement or a bare `*`; it does not inspect managed policies, or inline `Policies`
  written on the role itself.
- **No account ID is ever committed.** The account comes from the credentials in use, the role ARN lives only in the
  environment secret, and both workflows mask the account ID in their public logs.

## Who can change what in AWS

*Status: Built. Everything in this section exists today.*

![Who can change what in AWS: by hand, the administrator sets BRANCHBOOK_ALERT_EMAIL and deploys BranchbookAccount. The pipeline passes the aws-demo branch policy and two IAM guards, then deploys only BranchbookRelease, because its CDK app never includes BranchbookAccount. That guard is code, not IAM, and main requires no review.](../docs/assets/diagrams/access-boundary.svg)

**A.** By hand, the administrator `branchbook-admin`, an IAM user who signs in with `aws login`, runs the CDK app with
`BRANCHBOOK_ALERT_EMAIL` set, so the app includes `BranchbookAccount`. `cdk deploy` then updates that stack in
`us-east-1` through the `us-east-1` CDK bootstrap roles. Code and workflow enforce that switch. The hosted demo's
two stacks have a switch of their own, `BRANCHBOOK_HOSTED_DEMO=true`, which the administrator sets to deploy them
([The hosted demo](#the-hosted-demo)).

1. The job `deploy` in `deploy.yml` declares the GitHub environment `aws-demo`, which only the branch `main` and `v*`
   tags may use (a GitHub setting). `aws-connection-check.yml`, run by hand, also declares it.
2. AWS STS accepts GitHub's OIDC token only on an exact match with the role's trust policy (`StringEquals`): `aud` is
   `sts.amazonaws.com`, and `sub` names this repository by ID and the environment `aws-demo` (IAM).
3. The deploy role's sessions last at most 1 hour, and its policy has two statements: `AssumeCdkBootstrapRolesOnly`
   (`sts:AssumeRole` on `cdk-hnb659fds-*`) and `ReadReleaseRecordOnly` (`ssm:GetParameter` on
   `/branchbook/release`) (IAM).
4. `deploy.yml` never sets `BRANCHBOOK_ALERT_EMAIL` or `BRANCHBOOK_HOSTED_DEMO`, so the pipeline's CDK app
   holds only `BranchbookRelease`. Deploying `BranchbookAccount` or the hosted demo is not allowed because those
   stacks are not in the app
   ([bin/branchbook.ts](bin/branchbook.ts)). Code and workflow enforce this, not IAM.
5. Through the `af-south-1` bootstrap roles and CloudFormation, the pipeline deploys `BranchbookRelease` in
   `af-south-1`: the SSM parameter `/branchbook/release`, which holds the deployed commit.

Each guard carries a badge naming what enforces it:

| Badge | What enforces it | Guards | Who can change it |
|---|---|---|---|
| **IAM** | AWS refuses the call, whatever the workflow asks for | 2, 3 | A deploy that changes IAM: the administrator's, or, through cfn-exec, the pipeline's (see below) |
| **GitHub setting** | GitHub refuses to start the job | 1 | A repository administrator, in the settings |
| **Code and workflow** | The CDK app and `deploy.yml` never ask for it | A, 4 | Any commit that reaches `main` |

**The pipeline does not change its own access, but IAM would not stop it.** `BranchbookAccount` defines the role the
pipeline assumes, so it is part of the CDK app only when `BRANCHBOOK_ALERT_EMAIL` is set: by an administrator on
their own machine, or with the placeholder `alerts@example.com` in the credential-free `infra` workflow. The deploy
workflow never sets it, so it cannot synthesise that stack, let alone deploy it. That guard lives in the repository,
so a change to the repository can remove it.

**Not prevented by IAM today:**

- `AssumeCdkBootstrapRolesOnly` allows `cdk-hnb659fds-*`, which also matches the `us-east-1` bootstrap roles.
- The cfn-exec role keeps CDK's default `AdministratorAccess`, because the bootstrap commands under
  [First-time setup](#first-time-setup) pass no `--cloudformation-execution-policies`. A stack the pipeline deploys
  can therefore create any resource, IAM included.
- `main` requires no review or passing check ([docs/delivery.md](../docs/delivery.md#known-gaps)), so a workflow
  pushed to `main` that set `BRANCHBOOK_ALERT_EMAIL` could deploy `BranchbookAccount`, and one that set
  `BRANCHBOOK_HOSTED_DEMO=true` could deploy the hosted demo.

What limits this today is the repository, not AWS: it has one collaborator, who is an administrator, and the
`aws-demo` branch policy admits only `main` and `v*` tags.

**Hardening options, not done:**

- Scope `AssumeCdkBootstrapRolesOnly` to the bootstrap roles of `af-south-1`, whose names end in `-af-south-1`.
- Bootstrap with a custom execution policy (`--cloudformation-execution-policies`) or a permissions boundary, so
  cfn-exec cannot create or widen IAM roles.
- Protect `main`, and add a `CODEOWNERS` entry for `.github/` and `infra/`.

## The hosted demo

*Status: Built, deployed by hand on 2026-10-26.* The design is in
[ADR-0006](../docs/adr/0006-hosted-demo-on-aws-with-cdk.md), and where the build departs from it is in its
[Notes](../docs/adr/0006-hosted-demo-on-aws-with-cdk.md#notes); the cost line items are on the
[architecture page](https://claude.ai/artifact/X4b1pc9RSCboanuXBSn6oX).

The demo runs at https://branchbook.click. The domain was registered through Route 53 on 2026-10-26, and Route 53
created its hosted zone on registration, so no stack owns DNS; the zone's ID is in [cdk.json](cdk.json)
(`demoDomain`, `demoHostedZoneId`). `https://branchbook.click` is 24 characters, inside the 28 the confirmation SMS
allows (BR-18).

Both stacks are in `af-south-1`:

- **[`BranchbookData`](lib/data-stack.ts):** a VPC, `10.40.0.0/16`, across `af-south-1a` and `af-south-1b`, with a
  public and an isolated `/24` subnet in each and no NAT gateway. RDS for PostgreSQL 17.11, the version `make up`
  runs, on `db.t4g.micro` in the isolated subnets: Single-AZ, gp3 storage of 20 GB that may grow to 50, encrypted,
  7-day backups, and `rds.force_ssl=1`, so it refuses any connection without TLS. Its credentials are generated into
  Secrets Manager as `branchbook/database`, and so is `branchbook/sms-cap-key`, the key of the SMS daily cap's ledger
  ([Real SMS](#real-sms)). Deleting the stack leaves a final snapshot; deletion protection is off.
  PostgreSQL's own log is not exported, because a constraint error's `DETAIL` line can quote a mobile number (BR-19).
- **[`BranchbookApp`](lib/app-stack.ts):** an ACM certificate for `branchbook.click`, validated through Route 53; AWS
  WAF; an internet-facing Application Load Balancer; one Fargate task; an AWS End User Messaging SMS protect
  configuration that lets the task text South Africa only ([Real SMS](#real-sms)); application logs in
  `/branchbook/app`, kept 7 days; three CloudWatch alarms; and the Route 53 A alias that points the domain at the load
  balancer.

A reviewer's request takes this path:

1. The browser resolves `branchbook.click` through Route 53, whose alias names the load balancer. DNS answers the
   name; it is not in the data path.
2. AWS WAF, attached to the load balancer, applies its rules in order: it blocks `/actuator` (403); allows 10
   `POST`s to `/api/v1/appointments` and the paths under it, bookings and cancellations together, per IP in 5
   minutes; then applies AWS's managed rule groups `AWSManagedRulesAmazonIpReputationList`,
   `AWSManagedRulesKnownBadInputsRuleSet` and `AWSManagedRulesCommonRuleSet`. Both path rules decode and lowercase
   the path before comparing it (`URL_DECODE`, `LOWERCASE`), so `/%61ctuator` and `/ACTUATOR` are blocked too. It
   asks for no sign-in.
3. The load balancer, in the public subnets, ends HTTPS on port 443 with ELB's recommended TLS 1.3 policy, drops
   invalid header fields and health-checks `/readyz`. Plain HTTP on port 80 is answered with a redirect to HTTPS, and
   HSTS keeps browsers there.
4. The Fargate task (ARM64, 0.5 vCPU, 1 GB), which admits traffic only from the load balancer, serves the request on
   port 8080. It runs in a public subnet with a public IP, so it reaches ECR, Secrets Manager and AWS End User
   Messaging without a NAT gateway.
5. The task talks to RDS on port 5432 over TLS, directly, with no RDS Proxy; the database admits only the task.

The task is configured by environment variables; there are no `prod` or `demo` Spring profiles:

| Variable | Value |
|---|---|
| `DB_URL` | The RDS endpoint and the database `branchbook`, with `sslmode=require` |
| `DB_USER`, `DB_PASSWORD` | From the Secrets Manager secret `branchbook/database`; never in the template |
| `BRANCHBOOK_SMS_RECIPIENT_CAP_KEY` | From the Secrets Manager secret `branchbook/sms-cap-key`, 48 random letters and digits; never in the template. The daily cap's ledger counts each number under an HMAC-SHA256 fingerprint keyed with it |
| `PUBLIC_BASE_URL` | `https://branchbook.click`, the confirmation SMS's `/manage` link |
| `BRANCHBOOK_INBOX_ENABLED` | `false`: the simulated inbox is off, so `/api/v1/messages` answers 404 and the `/messages` page says it is switched off on this deployment. Each booker still sees their own SMS and its status on the confirmation page |
| `BRANCHBOOK_RETENTION_ENABLED` | `true`: an hourly job deletes each booking, with its messages and idempotency keys, once its appointment is over |
| `BRANCHBOOK_SMS_GATEWAY` | `aws`: each SMS goes through AWS End User Messaging SMS ([Real SMS](#real-sms)). The published demo numbers, 082 555 0000 to 082 555 0199, stay on the simulated gateway |
| `BRANCHBOOK_SMS_AWS_REGION` | `af-south-1`, the stack's own Region |
| `BRANCHBOOK_SMS_AWS_PROTECT_CONFIGURATION_ID` | The ID of the stack's protect configuration, which every send names |
| `BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP` | `5`: the most SMS one number is sent in a day, whatever the bookings |
| `BRANCHBOOK_SMS_AWS_DRY_RUN` | `false`. A deploy run with `BRANCHBOOK_SMS_AWS_DRY_RUN=true` sets it to `true`, and AWS then only checks each send and texts no one |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health,info`: the task serves no `/actuator/prometheus`, so its meters stay private even if a request slipped past WAF's block |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `native`: the app trusts the load balancer's `X-Forwarded-*` headers, so it sees each request as HTTPS |

Any South African mobile number may book: the demo-numbers-only rule (`BRANCHBOOK_BOOKING_DEMO_NUMBERS_ONLY`) is
off, because the demo now texts the number it is given.

**No sign-in.** Anyone with the address can try the demo: a sign-in prompt in front of a demo is a poor experience
for everyone who opens it, reviewers included
([ADR-0006, Notes](../docs/adr/0006-hosted-demo-on-aws-with-cdk.md#notes)). What limits what a visitor can do is
the deletion of each booking once its appointment is over, the SMS controls under [Real SMS](#real-sms), and WAF's limit on
bookings and cancellations. The inbox is off because, with no sign-in, it would list every booking's reference to
every visitor, and a demo number's last four digits are public, so anyone could open a booking made with one and see
the name typed into it.

A new version starts and passes its health check before the old one stops (minimum 100, maximum 200 percent), and
the deployment circuit breaker rolls back a version that never becomes healthy. The three alarms publish to an SNS
topic: the app answering 5xx to 5 or more requests in 5 minutes, no healthy task for 3 minutes, and the database
with less than 2 GB free. They are e-mailed only if `BRANCHBOOK_DEMO_ALERT_EMAIL` was set at deploy time.

**Cost.** About $82 a month before VAT while it runs (ADR-0006's estimate), inside the $90 budget; the domain costs
about $3 a year. Each SMS is charged on top, up to the account's SMS spend limit ([Real SMS](#real-sms)).

**Teardown date: 2026-11-30**, the end of the review window. Extend it here, with the reason, if the review runs
longer; until then the budget's alerts at $45, $72 and $90 are the backstop.

### Real SMS

*Status: Built. The project owner decided on 2026-10-27 that the hosted demo texts for real.*

**What is texted, and to whom.** Each booking's confirmation SMS, and a cancellation SMS when an appointment is
cancelled, go to the number the booking was made with, through AWS End User Messaging SMS in `af-south-1`. They go
on AWS's shared route, with no origination identity (no sender ID or number of our own). The published demo numbers,
082 555 0000 to 082 555 0199, always stay on the simulated gateway, so a reader who tries the README's examples
never texts a stranger; 082 555 0000 still fails, as it does locally.

**The controls,** all in [lib/app-stack.ts](lib/app-stack.ts) and [lib/sms-countries.ts](lib/sms-countries.ts):

- **South Africa only.** The stack's protect configuration blocks SMS to every one of the 245 countries AWS lists
  except South Africa (`ZA`), and every voice and MMS destination; left alone, it would allow them all. The lists
  are AWS's own answer for `af-south-1` on 2026-10-27, so no code AWS does not know can fail a deploy; refresh them
  from the same call, named in `sms-countries.ts`, if AWS adds a country.
- **One way to send.** The task role may call `sms-voice:SendTextMessage` on that protect configuration and nothing
  else. A send on the shared route, with no origination identity, names only the protect configuration, so the task
  can send through this one alone.
- **Five a day to one number** (`BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP=5`), whatever the bookings and cancellations.
  The app counts each SMS in a ledger in the database, under an HMAC-SHA256 fingerprint of the number, so the ledger
  holds no number. Its key, `branchbook/sms-cap-key`, is generated by the data stack and lives as long as the
  database, so a new task, or a redeployed app stack, keeps the count.
- **Ten bookings and cancellations per address in 5 minutes,** WAF's lowest limit, because each sends a real SMS.
- **No delivery events are logged.** No configuration set records them, because they carry the destination number,
  and no log may hold personal data ([BR-19](../docs/domain-rules.md#br-19)). So `SENT` means AWS accepted the
  message; nothing tells the app whether the handset received it.
- **A dry run first, if wanted.** Deploying with `BRANCHBOOK_SMS_AWS_DRY_RUN=true` in the environment sets the task's
  setting of that name, and AWS only checks each send, which proves the task's permission before anyone is texted
  ([Deploying and updating it](#deploying-and-updating-it)). Keep it brief: while it is on, the app records every
  send as `SENT` although nothing arrives, so redeploy without it after one test booking.

Retention stays on and the inbox stays off, as in the table above.

**The administrator's steps in AWS.** A new account starts in the SMS sandbox:

1. **In the sandbox,** only verified destination numbers, at most 10, receive messages, and the spend limit is 1 USD a
   month. To try it, add your own number as a verified destination number in the AWS End User Messaging SMS console,
   in `af-south-1`, and enter the code AWS texts to it.
2. **To text anyone else,** open an AWS Support case for production access in `af-south-1`, and ask in it for a
   higher monthly spend limit. AWS reviews the request.

Until production access is granted, a text to an unverified number ends `FAILED`: the confirmation page says "Not
delivered", and the booking still stands.

### Deploying and updating it

From this folder, signed in as `branchbook-admin`, with Docker running:

```bash
AWS_PROFILE=branchbook-admin BRANCHBOOK_HOSTED_DEMO=true \
BRANCHBOOK_DEMO_ALERT_EMAIL=<your address> \
  npx cdk deploy BranchbookData BranchbookApp
```

- **`BRANCHBOOK_HOSTED_DEMO=true` is required.** The two stacks are in the CDK app only when it is set. No password
  or other secret is supplied at deploy time.
- **`BRANCHBOOK_DEMO_ALERT_EMAIL` is optional.** Without it the alarms still exist, on a topic with no subscriber.
- **`BRANCHBOOK_SMS_AWS_DRY_RUN=true` is optional.** It deploys a task that asks AWS to check each send without sending
  it. Deploy again without it to text for real.
- **The image is built here.** `cdk deploy` builds the repository's [Dockerfile](../Dockerfile) for `linux/arm64` on
  the administrator's machine and pushes it to the `af-south-1` bootstrap ECR repository.
- **To update,** run the same command from the commit you want live, with the same address: leaving it out removes
  its subscription.

The pipeline never deploys these stacks: `deploy.yml` never sets `BRANCHBOOK_HOSTED_DEMO`, so its CDK app does not
contain them.
`/branchbook/release` therefore records the commit the pipeline last deployed, not the hosted app's version.

### Tearing it down

```bash
AWS_PROFILE=branchbook-admin BRANCHBOOK_HOSTED_DEMO=true \
  npx cdk destroy BranchbookApp BranchbookData
```

The variable must be set, because the stacks are in the app only when it is. The protect configuration goes with
`BranchbookApp`. Four things outlive the stacks:

- **The database's final snapshot.** Delete it by hand, under Snapshots in the RDS console.
- **The log group `/branchbook/app`,** which CloudFormation keeps. Delete it in the CloudWatch console.
- **The domain and its hosted zone,** which no stack owns. The domain renews each year unless auto-renew is turned
  off under Registered domains in Route 53; the hosted zone stays until it is deleted by hand.
- **Any verified destination numbers,** which belong to the account, not a stack. Delete them in the AWS End User
  Messaging SMS console.

**Open points** for a pipeline deploy, which nothing needs today:

- The pipeline would need an `ubuntu-24.04-arm` runner or QEMU to build the ARM64 image, because the
  [Dockerfile](../Dockerfile)'s runtime stage has `RUN` steps. Today it builds on the administrator's Apple-silicon
  machine.
- The release record would have to be written after a smoke test, so that it records only a version that works.

**Resolved on 2026-10-26:** the availability zones are pinned in [bin/branchbook.ts](bin/branchbook.ts), so
synthesis needs no lookup; the App stack's alert address has its own input, `BRANCHBOOK_DEMO_ALERT_EMAIL`; and
`application-prod.yml` and `application-demo.yml` are not needed, because the task sets environment variables.

## Prerequisites

- An AWS account you own and an administrator IAM user for daily work. The [first-time setup](#first-time-setup)
  below walks through it.
- [AWS CLI v2](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) 2.32 or later (for
  `aws login`) and Node.js 24.

## Commands

All commands run from this folder.

```bash
npm ci              # install the pinned dependencies
npm run lint        # ESLint, including TSDoc on every export
npm run build       # type-check
npm test            # unit tests on the synthesised templates
BRANCHBOOK_ALERT_EMAIL=alerts@example.com \
BRANCHBOOK_HOSTED_DEMO=true \
  npx cdk synth     # render CloudFormation without touching AWS
```

The lint rules are in [eslint.config.mjs](eslint.config.mjs). They use typescript-eslint, which needs the TypeScript
compiler API that TypeScript 7 does not yet provide, so this package uses TypeScript 6.0, the same as `web/`.

`BRANCHBOOK_ALERT_EMAIL` is where budget alerts go, read from the environment so that no personal address is ever
committed, and `BRANCHBOOK_HOSTED_DEMO=true` asks for the hosted demo. Each brings its stacks into the app. Any
placeholder address works for `synth`; use the real one only when deploying.

## First-time setup

These steps need you personally, and only happen once.

1. **Create the AWS account (console).** Sign up at https://aws.amazon.com and choose the paid plan, so the account
   never closes automatically; new-account credits still apply. Turn on MFA for the root user.
2. **Create one administrator user, then stop using root (console).** Signed in as root, create the IAM user
   `branchbook-admin` with console access and the `AdministratorAccess` policy, and turn on its MFA. Creating the
   first administrator is the one task root is meant for. IAM Identity Center is the alternative when several people
   need access; for a one-person account an IAM user signed in through `aws login` is simpler and still issues only
   short-lived credentials.
3. **Enable Cape Town (console).** Under Account, then AWS Regions, enable **Africa (Cape Town) af-south-1**. It can
   take a while to activate, and the pipeline deploys there.
4. **Sign the CLI in** while the browser is signed in as `branchbook-admin`:

   ```bash
   aws configure set region af-south-1 --profile branchbook-admin
   aws login --profile branchbook-admin   # opens the browser; choose the branchbook-admin session
   aws sts get-caller-identity --profile branchbook-admin \
     --query Arn --output text            # must end in user/branchbook-admin
   ```

   `aws login` stores temporary credentials and refreshes them; no access key is created. Run it again when the
   session expires.

5. **Bootstrap CDK** in `us-east-1`, the Region of `BranchbookAccount`. This creates the roles, S3 bucket and ECR
   repository CDK deploys through; it trusts no other AWS account:

   ```bash
   npx cdk bootstrap \
     "aws://$(aws sts get-caller-identity --profile branchbook-admin --query Account --output text)/us-east-1" \
     --profile branchbook-admin --tags project=branchbook
   ```

   Bootstrap `af-south-1` the same way: the pipeline deploys there. Neither command passes
   `--cloudformation-execution-policies`, so the cfn-exec role keeps CDK's default `AdministratorAccess`
   ([Who can change what in AWS](#who-can-change-what-in-aws)).

6. **Deploy `BranchbookAccount`:**

   ```bash
   BRANCHBOOK_ALERT_EMAIL=<your address> \
     npx cdk deploy BranchbookAccount --profile branchbook-admin
   ```

7. **Tell GitHub about it (console).** In the repository settings, under **Environments**, create `aws-demo`. Under
   **Deployment branches and tags**, choose **Selected branches and tags**, and add `main` (branch) and `v*` (tag).
   The trust policy names the environment, not a branch, so this rule is what keeps a feature branch out. Check it:

   ```bash
   gh api repos/KatlehoKomeke/Appointment-Booking-System/environments/aws-demo/deployment-branch-policies \
     --jq '.branch_policies[] | "\(.type) \(.name)"'
   ```

   Inside that environment, add the secret `AWS_DEPLOY_ROLE_ARN` with the role ARN. An environment secret is only
   readable by jobs that declare the environment, which is tighter than a repository-wide secret. This copies the ARN
   to the clipboard without printing your account ID:

   ```bash
   aws cloudformation describe-stacks --stack-name BranchbookAccount \
     --region us-east-1 --profile branchbook-admin \
     --query "Stacks[0].Outputs[?OutputKey=='DeployRoleArn'].OutputValue" \
     --output text | tr -d '\n' | pbcopy
   ```

8. **Prove the connection.** Under **Actions**, run **aws-connection-check**. It assumes the role and reports which
   role it obtained, with the account ID masked.

## Removing it

Run these in order, from this folder. Each stack is destroyed through the bootstrap roles of its Region, so the
bootstrap goes last.

1. Tear down the hosted demo, as in [Tearing it down](#tearing-it-down), if it is still running.
2. Destroy the release record:

   ```bash
   npx cdk destroy BranchbookRelease --profile branchbook-admin
   ```

3. Destroy the GitHub trust, the deploy role and the budget. The variable must be set, because the stack is in the
   app only when it is; any placeholder works:

   ```bash
   BRANCHBOOK_ALERT_EMAIL=alerts@example.com \
     npx cdk destroy BranchbookAccount --profile branchbook-admin
   ```

4. Delete `CDKToolkit` in both Regions:

   ```bash
   aws cloudformation delete-stack --stack-name CDKToolkit --region af-south-1 --profile branchbook-admin
   aws cloudformation delete-stack --stack-name CDKToolkit --region us-east-1 --profile branchbook-admin
   ```

   The ECR repository must be empty for the delete to succeed; in `af-south-1` it holds the hosted demo's images,
   so delete them first in the ECR console. CloudFormation keeps each assets bucket
   (`cdk-hnb659fds-assets-<account>-<region>`) on purpose. In the S3 console, empty it, which also removes its old
   versions, then delete it.

5. Delete the `AWS_DEPLOY_ROLE_ARN` secret from the `aws-demo` environment, last. Every later push to `main` fails
   the deploy job at sign-in; `gh workflow disable deploy` stops it running.

## Dependency notes

- **Versions are pinned.** `aws-cdk-lib` and `constructs` are pinned exactly, and `package-lock.json` fixes
  everything else. `npm ci` installs exactly what was tested.
- **One override clears a test-only advisory.** `overrides` replaces `argparse` 1 with 2 under `js-yaml`. The old
  `argparse` pulls in `sprintf-js`, which has an unpatched denial-of-service advisory
  ([GHSA-hp3w-g68c-fv3c](https://github.com/advisories/GHSA-hp3w-g68c-fv3c)). `js-yaml` 3 uses `argparse` only for
  its command-line tool, never in the library code Jest's coverage tooling calls, so the override changes no
  behaviour and leaves `npm audit` clean. Remove it once Jest no longer depends on `js-yaml` 3.

## Layout

| Path | Purpose |
|---|---|
| `bin/branchbook.ts` | The CDK app: which stacks exist and where they deploy |
| `lib/account-stack.ts` | `AccountStack`: the GitHub OIDC trust, the deploy role and the budget |
| `lib/release-stack.ts` | `ReleaseStack`: records the deployed commit in `/branchbook/release` |
| `lib/data-stack.ts` | `DataStack`: the hosted demo's VPC and database |
| `lib/app-stack.ts` | `AppStack`: the hosted demo's certificate, WAF, load balancer, Fargate task, SMS protect configuration, alarms and DNS record |
| `lib/sms-countries.ts` | The countries AWS lists for a protect configuration; the hosted demo blocks every one but South Africa |
| `test/account-stack.test.ts` | Unit tests that check the connection's security properties |
| `test/release-stack.test.ts` | Unit tests for the release record the pipeline verifies |
| `test/hosted-demo.test.ts` | Unit tests for the hosted demo's database, network, WAF, task and SMS permission |
| `cdk.json` | Non-sensitive configuration (repository name and public IDs, environment, budget, the demo's domain and hosted zone ID) and CDK feature flags |

Next: [docs/delivery.md](../docs/delivery.md) for how a commit gets here; [SECURITY.md](../SECURITY.md) for secrets
and personal information.
