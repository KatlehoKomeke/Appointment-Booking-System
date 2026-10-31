# Diagrams

BranchBook's documentation uses two kinds of diagram, chosen by how often the picture changes. Eight hand-written
SVG files in this folder hold the stable big pictures: the system, the local stack, the schema, delivery, one measured
push, the AWS footprint, the access boundary and the secret layers. Six Mermaid blocks, written inline in
[docs/architecture.md](../../architecture.md) and [infra/README.md](../../../infra/README.md), hold the code-shaped
pictures that change at milestones: one request, the modules, the booking transaction, the confirmation states, the
appointment lifecycle and the keyless deploy. Grids of facts stay in tables.

This page is the index and the rulebook for all fourteen. Every SVG starts as a copy of
[`_template.svg`](_template.svg), which holds the shared style and the key below.

- [Index](#index)
- [Key](#key)
- [Rules](#rules)
- [Editing a diagram](#editing-a-diagram)
- [When a milestone lands](#when-a-milestone-lands)

## Index

The rows run in reading order: the system, the application, the local stack, delivery, AWS, then secrets.

### What each diagram shows

| Diagram | Format | What it answers | Shown in |
| --- | --- | --- | --- |
| [system-at-a-glance](system-at-a-glance.svg) | SVG | What BranchBook is, what runs inside it, which parts exist today, and how code reaches AWS | [README.md](../../../README.md) (hero); [architecture.md, section 2](../../architecture.md#2-system-context-and-containers) |
| request-flow | Mermaid | What code runs, in what order, for a real request today | [architecture.md, section 2.1](../../architecture.md#21-one-request-through-the-jar) |
| module-map | Mermaid | Which modules exist, and which depend on which | [architecture.md, section 3](../../architecture.md#3-modules-and-the-dependency-rule) |
| booking-transaction | Mermaid | In what order a booking takes its locks and writes its rows | [architecture.md, section 4](../../architecture.md#4-the-booking-transaction) |
| confirmation-states | Mermaid | Which states a confirmation or cancellation SMS can be in, and how it moves between them | [architecture.md, section 5](../../architecture.md#5-the-confirmation-sms) |
| appointment-lifecycle | Mermaid | Which states an appointment can take, and which transition the API exposes | [architecture.md, section 6](../../architecture.md#6-appointment-lifecycle) |
| [data-model](data-model.svg) | SVG | Which tables exist, which module owns each, how they relate, and what the database itself guarantees | [architecture.md, section 7](../../architecture.md#7-data-model) |
| [local-runtime](local-runtime.svg) | SVG | What `make up` builds and starts, how it knows the stack is ready, and where else the same image runs | [architecture.md, section 10.1](../../architecture.md#101-locally-the-image-and-the-stack) |
| [delivery-pipeline](delivery-pipeline.svg) | SVG | What happens between `git commit` and AWS: which checks run where, which of them block, what deploys, and where the gaps are | [README.md](../../../README.md#how-it-is-tested-and-delivered); [delivery.md](../../delivery.md#at-a-glance); [architecture.md, section 10.2](../../architecture.md#102-from-a-commit-to-aws) |
| [push-timeline](push-timeline.svg) | SVG | What one push to `main` runs, how long each job takes, and whether the deploy waits for the tests | [delivery.md](../../delivery.md#one-push-measured) |
| [aws-footprint](aws-footprint.svg) | SVG | What exists in AWS, in which Region, what staging and production share, who deploys each part, how a customer's request reaches the app, and how production's SMS leave it | [infra/README.md](../../../infra/README.md#what-exists-today); [architecture.md, section 10.3](../../architecture.md#103-in-aws) |
| keyless-deploy | Mermaid | How the deploy job gets short-lived credentials, what is checked, and how it proves the deploy landed | [infra/README.md](../../../infra/README.md#how-github-reaches-aws-without-keys) |
| [access-boundary](access-boundary.svg) | SVG | Who can reach the app, whether either environment's pipeline can reach the other's stacks or widen its own access, and what enforces each guard | [infra/README.md](../../../infra/README.md#who-can-change-what-in-aws) |
| [secret-layers](secret-layers.svg) | SVG | Which layer stops a secret at each stage, which layers can be skipped, which act only after the code is public, and what holds each secret at run time | [SECURITY.md](../../../SECURITY.md#secrets) |
| [diagram key](_template.svg) | SVG | What each colour, stroke, shape and pill means | [this page](#key) |

### Keeping each diagram true

Each row names the paths a diagram is drawn from. A commit that changes a watch path updates the diagram and its row
in the same commit. **Verified at** is the commit the diagram was last checked against; the same commit is written in
each SVG's header comment. Java paths are relative to `api/src/main/java/za/co/fynbos/branchbook/`, and workflow
files live in `.github/workflows/`.

| Diagram | Watch paths | Expected to change at | Verified at |
| --- | --- | --- | --- |
| system-at-a-glance | the module packages, their controllers and their scheduled jobs; `web/src/`; `api/src/main/resources/application.yml`; `compose.yaml`; `infra/bin/branchbook.ts`; `infra/lib/app-stack.ts` (the task's settings); `infra/cdk.json` | when a module, a background job, the SMS gateway or the path to AWS changes | `21798b6` |
| request-flow | `scheduling/`; `catalogue/application/BranchCalendarProvider.java`; `catalogue/infrastructure/`; `booking/infrastructure/JdbcBookedIntervals.java`; `shared/correlation/`; `shared/security/`; `shared/problem/` | when the availability path changes | `85cd726` |
| module-map | the top-level packages; `ArchitectureTest.java` in `api/src/test/` | when a module or a dependency between modules changes | `85cd726` |
| booking-transaction | `booking/application/`; `V2__schema.sql` | when the booking transaction's steps change | `85cd726` |
| confirmation-states | `notification/`; the `notification_messages` table in `V2__schema.sql` and `V6__cancel_deletes_the_booking.sql` | when the outbox, the dispatcher or the daily cap changes | `4d4ba68` |
| appointment-lifecycle | `booking/domain/AppointmentStatus.java`; `booking/application/CancelAppointmentHandler.java` | when a status or a transition changes | `85cd726` |
| data-model | `api/src/main/resources/db/migration/`; the writers in `booking/application/` and `notification/application/` | With a new migration | `44b8d1c` |
| local-runtime | `Dockerfile`; `.dockerignore`; `compose.yaml`; the `Makefile` up and dev targets; `web/vite.config.ts`; `application.yml` (probes, management endpoints, connection pool); the controllers (the routes in the API box) | when a route, a management endpoint or the compose stack changes | `44b8d1c` |
| delivery-pipeline | every workflow; `.githooks/`; `.github/dependabot.yml`; `infra/lib/app.ts`; `infra/lib/account-stack.ts`; `infra/lib/registry-stack.ts`; the `main` ruleset and the `staging` and `production` environments | when a workflow, a job, a hook, a stack the pipeline deploys or a GitHub setting changes | `90bf68b` |
| push-timeline | the job structure of every workflow; a dated measurement, not a picture of the current code | re-measured after the first run of the pipeline with `deploy` inside `ci` | `85cd726` (measured at `c5feafe`, before the `deploy` job joined `ci`) |
| aws-footprint | `infra/bin/`; `infra/lib/`; `infra/config/`; `infra/cdk.json` | when a stack, an environment, or who deploys it, changes | `90bf68b` |
| keyless-deploy | `ci.yml` (job `deploy`); `infra/lib/account-stack.ts` | on hardening; when the `deploy` job's steps change | `21798b6` |
| access-boundary | `infra/lib/account-stack.ts`; `infra/lib/guardrails-stack.ts`; `infra/lib/app.ts`; `infra/lib/app-stack.ts` (ports, WAF, the task's database user); `infra/cdk.json` (the permissions boundary); `V9__runtime_role.sql`; `ci.yml` (the delivery jobs); `aws-connection-check.yml`; the `staging` and `production` environments; the `main` ruleset; the bootstrap commands in infra/README.md | when a guard changes | `90bf68b` |
| secret-layers | `.gitignore`; `.githooks/pre-commit`; `.gitleaks.toml`; `secret-scan.yml`; `ci.yml` (job `stack` and the delivery jobs); `compose.yaml` and the `Makefile` (the local password); `infra/lib/data-stack.ts`, `infra/lib/app-stack.ts` and `infra/lib/captcha-api-key.ts` (the generated secrets, the task's secrets and IAM database authentication); the environments' secrets; the repository's security settings | when a layer is added or changes | `90bf68b` |
| diagram key | the rules on this page | only with the visual language | `85cd726` |

The post-commit hook (`make hooks`) prints a reminder after a major commit, as `scripts/major-commits.sh` defines it:
any `feat`; a `fix` that changes code under `api/src/main/`, `web/src/`, `infra/lib/` or `infra/bin/`; and any
commit that touches a migration, an `api` package, `infra/lib/`, `infra/bin/`, the `Dockerfile`, `compose.yaml`, a
workflow or an ADR. That reminder covers the matching rows above. Any other change to a watch path is checked by
hand, notably to `.githooks/`, `.github/dependabot.yml`, `.gitleaks.toml`, `.gitignore`, `.dockerignore`, the
`Makefile`, `application.yml`, `web/vite.config.ts` and `infra/cdk.json`, and to any GitHub setting, which produces
no commit at all.

## Key

![Diagram key: solid boxes are built, a warn tag marks partly built, dashed boxes with a milestone tag are planned; a green line is the main path, a dashed line a planned flow, a red crossed line a refused path, a dotted line a health check; coloured stripes mark AWS service categories.](_template.svg)

- **Built:** a solid outline, a surface fill and ink text. An optional pill names the milestone or who deploys it, such
  as `built · M3` or `built · by hand`.
- **Built, highlighted:** a green fill and outline (`box-ok`), on one node per diagram at most.
- **Partly built:** a solid box with the warn pill `partly built`. Its missing part is a dashed chip that names its
  milestone, such as `booking screens · planned · M4`.
- **Planned:** a dashed outline with no fill, ink-2 text, and a dashed pill that always names the milestone:
  `planned · M4`, `planned · Tier 2`, `planned · after M6`.
- **Refused outcome:** a red box. **Note:** a folded corner, joined to its subject by a dotted leader. **Gap or
  caveat:** a yellow folded note whose text starts `Gap:` or `Caveat:`.
- **Step badge:** a green circle whose number matches the numbered list beside the image. The administrator's path
  uses the letter A.
- **Group, lane or Region:** a grey rounded panel, also used for a compose project or a workflow card.
- **CloudFormation stack:** a box with a green header band holding the stack name in monospace. A planned stack is a
  dashed frame with no band.
- **Gate:** a small diamond on an edge, with its label beside it. When the rule is too long for a label, the gate is
  a node with a thin accent band along its top and a badge naming what enforces it.
- **Person or browser:** a fully rounded pill. **Database:** a cylinder. **Workflow or file:** a box with a folded
  corner.
- **Edges:** a thin grey arrow is a flow; the heavier green arrow is the main path, one per diagram; a dashed arrow is
  a planned flow whose label ends with its milestone; a red arrow crossed at its midpoint is refused or not allowed;
  a dotted line is a health check or a note leader. Where two lines cross, the lower one breaks.
- **Pills:** `reports; blocks nothing` (warn) marks a check that runs but cannot stop anything; `cannot block a push`
  (warn) marks a check that runs after the push and can stop only a merge, because `main` requires it; `refused` (red)
  marks a refused outcome.
- **AWS tiles:** a 4 px stripe on the left edge names the service category.

## Rules

### Canvas and files

- One file per diagram: `docs/assets/diagrams/<id>.svg`, at most 40 KB.
- `viewBox="0 0 960 H"` with `width="960"` and `height="H"`. H is a multiple of 8 and at most 800. The one exception
  is [data-model.svg](data-model.svg), at 1024: its ten tables do not fit in 800.
- Every file paints its own card: `<rect class="card" x="0.5" y="0.5" width="959" height="H-1" rx="16"/>`. Content
  stays between x 24 and x 936.
- An 8 px grid with integer coordinates; a 1 px stroke sits on a .5 coordinate so it renders crisp. Padding is 24 px
  inside the card, 16 px between siblings, 12 px inside nodes and 32 px between lanes.
- Radii: card 16, groups 12, nodes 8, AWS tiles 6, pills fully rounded.
- Embed with Markdown image syntax, so GitHub links the image to the full-size file and phone readers can zoom:
  `![alt](assets/diagrams/<id>.svg)` from `docs/`, `docs/assets/diagrams/<id>.svg` from the repository root and from
  `SECURITY.md`, and `../docs/assets/diagrams/<id>.svg` from `infra/`.

### Palette

Every colour comes from a class, and every class reads a token. The tokens are defined on `:root` and redefined
inside `@media (prefers-color-scheme:dark)`.

| Token | Light | Dark | Job |
| --- | --- | --- | --- |
| `bg` | `#F2F5F2` | `#0D1310` | the opaque card behind every diagram |
| `surface` | `#FFFFFF` | `#141C18` | built nodes, table cards, pill fills, step-badge numerals |
| `surface-2` | `#E8EEE9` | `#1A2520` | groups, lanes, Regions, compose projects, workflow cards |
| `ink` | `#15201A` | `#E2EAE5` | node titles and primary labels |
| `ink-2` | `#46534C` | `#A9B6AE` | secondary lines, edge labels, neutral edges and arrowheads, planned text |
| `ink-3` | `#69766E` | `#85938B` | dashed strokes of planned nodes and edges, probe lines, axis ticks; as text only on `surface` |
| `line` | `#D3DCD5` | `#28352F` | card border, gridlines, table-card row rules; never text |
| `line-strong` | `#AEBDB3` | `#3E4F46` | strokes of built nodes and groups; never text |
| `accent` | `#1E6B52` | `#62C29A` | the one main path, step badges, the deploy bar on the timeline |
| `accent-strong` | `#164F3D` | `#8AD8B5` | text of built pills |
| `accent-soft` | `#DCEDE4` | `#16332A` | built pills, the highlighted node, a stack's header band |
| `warn` | `#775C10` | `#E3C266` | partly built, gaps, caveats, guards that only process enforces, `reports; blocks nothing` |
| `warn-soft` | `#F5ECCD` | `#342B12` | the fill behind `warn` |
| `bad` | `#A63650` | `#EC8CA3` | refused or forbidden paths and outcomes |
| `bad-soft` | `#F6E0E6` | `#3A1C25` | the fill behind `bad` |

AWS category colours appear only as the 4 px stripe on an AWS tile, in the same hex in both themes, never as text or
fill:

| Class | Colour | Category | Services in this repository |
| --- | --- | --- | --- |
| `aws-compute` | `#ED7100` | compute and containers | ECR repository; ECS Fargate (the service and the migration task) |
| `aws-db` | `#C925D1` | database | Amazon RDS for PostgreSQL |
| `aws-net` | `#8C4FFF` | networking | Route 53, VPC, Application Load Balancer |
| `aws-sec` | `#DD344C` | security | IAM OIDC provider, IAM role, AWS STS; AWS WAF, ACM, Secrets Manager; the CDK bootstraps |
| `aws-mgmt` | `#E7157B` | management and integration | AWS CloudFormation, SSM Parameter Store; CloudWatch, SNS; AWS End User Messaging SMS; AWS FIS |
| `aws-store` | `#7AA116` | storage and cost | S3 assets bucket (bootstrap), AWS Budgets |

No other colours. Mermaid blocks carry no colour at all.

### Classes

| Class | Draws |
| --- | --- |
| `card` | the opaque card |
| `box` | a built node, tile, file, cylinder or note |
| `box-ok` | the one highlighted built node |
| `box-soft` | a group, lane, Region, compose project or workflow card; the `GitHub setting` badge |
| `box-dash` | a planned node or frame, unfilled so the group colour shows through |
| `box-warn`, `box-bad` | a gap or caveat note; a refused outcome |
| `edge`, `edge-acc`, `edge-dash`, `edge-bad` | a flow, the main path, a planned flow, a refused path |
| `probe` | a health check or a note leader |
| `grid` | gridlines and table-card row rules |
| `ah`, `ah-acc`, `ah-dash`, `ah-bad` | the arrowhead for each edge colour; `ah-bad` also draws the cross on a refused edge |
| `t`, `s`, `l`, `g` | a node title, a secondary line, an edge label, a group or lane label |
| `tp` | ink-2 text: planned titles, and 12 px captions and legend text with `font-size="12"` |
| `mono` | monospace, for every real identifier |
| `halo`, `halo-2` | an outline in the background colour behind a label on `bg` or on `surface-2` |
| `pill-built`, `pill-plan`, `pill-warn`, `pill-bad` | a pill's fill; add `-t` for its text |
| `badge`, `badge-t` | a step badge and its numeral; `badge` also fills the accent band of a long-form gate |
| `aws-compute`, `aws-db`, `aws-net`, `aws-sec`, `aws-mgmt`, `aws-store` | the category stripe on an AWS tile |

If a diagram truly needs another class, add it in a second `<style>` element after the shared one, prefix it with
`x-`, and use only the shared tokens.

### Type

- System fonts only, with no `@font-face` or web fonts. Sans: `-apple-system, "Segoe UI", Helvetica, Arial,
  sans-serif`. Mono: `ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace`, for every real
  identifier: files, paths, workflow and job ids, stack, role and class names, routes, environment variables, SSM
  paths and commands.
- Sizes on the 960 canvas: node title 14 px, weight 600 (13 px in mono); secondary line 13 px (12 px in mono); edge
  label 12.5 px on a halo; group label 12 px, weight 600, letter-spacing 0.04em; pills, legend and captions 12 px;
  step numerals 12 px, weight 700. Nothing is smaller than 12 px, so text stays at about 10.4 px or more in GitHub's
  README column, which is about 830 px wide.
- Line pitch 1.35: 19 px under 14 px text, 18 px under 13 px, 16 px under 12 px.
- Sentence case, with capitals only in identifiers. At most three lines in a node. No rotated text, no text on a
  path and no `textLength`. Wrap any label longer than about 34 characters.
- Every label fits within 80 percent of its box's inner width in Arial, which leaves room for Segoe UI, SF and the
  Linux fallbacks. Keep 12 px of padding inside every box.
- Times as `12:41:40Z`, money as `$90`, durations as `1 m 37 s`. British spelling.

### Shapes and lines

- **Node:** `rect`, `rx="8"`, class `box`. At least 44 px tall for one line and 60 px for two.
- **Group, lane, Region, compose project, workflow card:** `rx="12"`, class `box-soft`, its `g` label inside at
  +16, +22 from the top-left corner.
- **CloudFormation stack:** a `box` with a 28 px header band in `accent-soft` holding the mono stack name. A planned
  stack is `box-dash` with no band.
- **AWS tile:** `rx="6"`, class `box`, with a stripe 4 px wide and 8 px shorter than the tile, inset 4 px from the
  left edge. The first line is the service's plain name: `AWS STS`, `IAM role`, `AWS CloudFormation`,
  `SSM Parameter Store`, `AWS Budgets`.
- **Actor** (customer, browser, administrator): a pill with `rx` equal to half its height.
- **Database:** a cylinder whose ellipses have `ry="6"`. **File** (a workflow, a migration): a node with a 10 px fold
  at the top-right corner.
- **Gate** (push protection, an environment's branch policy, a trust policy, `needs`): a 14 px diamond on the edge,
  its label beside it. Where the rule is long, a node with a 3 px accent band along its top and an enforcement badge.
- **Note:** a folded-corner box joined to its subject by a `probe` leader. A neutral note is `box`; a gap or caveat is
  `box-warn` and its text starts `Gap:` or `Caveat:`.
- **Step badge:** a circle of radius 10, class `badge`, its numeral `badge-t`.
- **Pill:** 20 px tall, fully rounded, 12 px text, at least 8 px of side padding.
- **Lines:** `edge` 1.4 px in ink-2; `edge-acc` 2.2 px in accent, on one main path per diagram; `edge-dash` 1.3 px,
  dashed 5 4, for a planned flow; `edge-bad` 1.6 px in bad, with a 10 px cross at its midpoint and a label starting
  `not allowed:` or `refused:`; `probe` 1.2 px, dotted 1.5 3, for health checks and note leaders.
- **Routing:** orthogonal, with 6 px corners. Edges enter a box at the middle of a side and never pass through a
  label. A line crosses at most one other line, and the lower one breaks with a 4 px gap on each side. Labels sit on
  straight segments, on a halo. Each edge colour has its own 8 × 8 arrowhead.

### Built, partly built and planned

- The same three words everywhere: Built, Partly built, Planned. Inside a diagram the tags are lower case:
  `built · M2`, `partly built`, `planned · M5`, `planned · Tier 2`, `planned · after M6`.
- A planned element, or its group, carries `class="planned"` and `data-milestone` set to one of `M3`, `M4`, `M5`,
  `M6`, `M7`, `tier2` or `after-M6`, so a search finds it.
- A planned edge is always `edge-dash`, even between two built boxes, and its label ends with the milestone.
- An area that is entirely planned sits inside one dashed region bannered `Planned after M6`, never inside a solid
  stack box. Accent is never applied to anything planned.
- Colour never carries status alone: the dash pattern and the pill text carry it too.
- Only pushed work is drawn. Hardening options that nobody has committed to are not drawn; they live in the text.
- A measured fact carries its commit and date. A planned cost carries "about".

### Legend

Every SVG has a legend row at the bottom-left of the card, its last baseline 24 px above the bottom edge. Each entry
is a drawn 24 × 14 swatch, then 12 px ink-2 text. A legend shows only the entries its diagram uses, always in this
order and with this wording:

1. `Built`
2. `Partly built`
3. `Planned, with milestone`
4. `Main path`
5. `Planned flow`
6. `Refused or not allowed`
7. `Health check`
8. `Gap or caveat`
9. On AWS diagrams, `AWS category:` followed by the stripes used, each named.

[access-boundary.svg](access-boundary.svg) adds the enforcement badges `IAM`, `Network` and `PostgreSQL` (accent-strong
on accent-soft), `GitHub setting` (ink-2 on surface-2) and `Code and workflow` (warn on warn-soft). Space the entries so that each
label still clears the next swatch when the file is rendered in Verdana. In the key the legend wraps onto three
lines; a diagram's legend is usually one.

### AWS and GitHub marks

- No official AWS Architecture Icons, no AWS logo, no Octocat, no git logo, no Docker whale, and no Spring, React or
  PostgreSQL logo. A service is a labelled tile with a category stripe; PostgreSQL is a plain cylinder; git is mono
  command text such as `git commit`; a container is a node titled with its compose service and image, such as
  `app · branchbook:local`.
- A Region is a `box-soft` group titled `AWS · us-east-1 (N. Virginia)` or `AWS · af-south-1 (Cape Town)`. The account
  appears only as a group titled `AWS account (ID never committed)`.
- AWS STS sits outside both Region groups: IAM is global, and the delivery jobs call the af-south-1 endpoint. IAM
  resources sit inside the `BranchbookAccount` stack frame, because that stack defines them.
- Account-specific names are written `-<account>-<region>`, and an ARN, if one is needed at all, as
  `arn:aws:iam::<account>:role/cdk-hnb659fds-*`.
- GitHub is a neutral group titled `GitHub`, with the slug `KatlehoKomeke/Appointment-Booking-System` in mono.
  Workflows are file shapes named by their `.yml` file, with job ids as small pills or nodes. A GitHub environment is
  a gate or a node labelled with its name, such as `environment production`.

### Light and dark

One file serves both themes. Inside an `<img>`, the SVG's `prefers-color-scheme` follows the browser or operating
system, which can differ from the theme chosen in GitHub's settings. That is why every SVG paints its own opaque card
with a border: when the two disagree, the reader still sees a self-contained, legible card. The AWS stripes and step
badges read on both surfaces, and arrowheads switch with their edges because they use the same tokens.

### Accessibility

- Every image has alt text of one or two sentences, at most about 300 characters, stating the takeaway and which parts
  are built or planned.
- The SVG root has `role="img"` and `aria-labelledby="title desc"`. The `<title>` is the diagram's name; the `<desc>`
  reads every node and edge in order: left to right, top to bottom, following the step numbers.
- Numbered badges match a numbered list in the Markdown beside the image, so phone and screen-reader users lose
  nothing.
- Text contrast is at least 4.5:1 in both themes. Checked pairs, light then dark: ink on surface 16.8 and 14.2; ink-2
  on surface-2 6.9 and 7.5; ink-3 on surface 4.8 and 5.4 (never on `bg` or `surface-2`); accent-strong on
  accent-soft 7.8 and 8.2; warn on warn-soft 5.3 and 8.1; bad on bad-soft 5.1 and 6.4; surface on accent 6.4
  and 8.0.
- Status never rests on colour: planned has dashes and the milestone word, refused has a cross and `not allowed:`, the
  main path a heavier stroke, an enforcement badge a word.
- Text stays real `<text>`, so it can be selected, searched and zoomed to 200 percent. No animation, no information
  only on hover, and no pictographic characters: ticks and crosses are drawn as paths.

### What never goes in a diagram

- Values that drift: patch versions, test or smoke-check counts, run counts, durations, dates, SHAs. The one
  exception is [push-timeline.svg](push-timeline.svg), a dated measurement stamped with its commit and date. Schema
  constraint counts are allowed, because migrations fix them and `SchemaConstraintIT` proves them.
- An AWS account ID, a full ARN with an account number, or an e-mail address.
- Work that has not been pushed, and hardening options nobody has committed to.
- Any real bank's name or branding, and any logo or icon set.
- `<script>`, `<foreignObject>`, `<image>`, an external `href` or any `url()` other than `url(#marker)`, `@import`,
  filters, gradients, shadows, embedded fonts, colour in presentation attributes (`fill=`, `stroke=`), and any
  12-digit number.
- Editor metadata, generated ids, and transforms other than `translate()`.

### Mermaid

- Only `flowchart`, `sequenceDiagram`, `stateDiagram-v2` and `erDiagram`.
- Quoted labels of 60 characters or fewer, so GitHub does not scale the block down. No semicolons or `#` in a label.
- No `%%{init}%%`, `themeVariables`, `style`, `linkStyle`, coloured `classDef`, `box`, `accTitle` or `accDescr`, so
  GitHub's own light and dark Mermaid themes apply cleanly.
- A planned node uses `:::planned` with `classDef planned stroke-dasharray: 5 4` and nothing else. A planned edge uses
  `-.->` with the milestone in its label.
- Each block is followed by the line `Solid: built. Dashed: planned, with its milestone.` The caption and the prose
  beside it carry the content for screen readers.

## Editing a diagram

Any text editor will do. A file exported from a vector editor (Inkscape, Figma or another) is tidied by hand before it
is committed: editor metadata and generated ids removed, inline colours replaced by the shared classes.

1. **Start from the template.** Copy `_template.svg` to `<id>.svg`. Keep its `<style>` and `<defs>` blocks byte for
   byte; never edit the copy's tokens. Replace the samples between the card and the legend with your content.
2. **Fill in the header.** Set `<title>`, write the `<desc>` in reading order, and fill in the header comment:
   diagram id, where it is shown, its source paths, `Verified at <short sha> (<date>)`, and the pointer to this page.
3. **Write one element per line,** with a 2-space indent, integer coordinates and kebab-case group ids named after real
   things, such as `lane-laptop`, `region-af-south-1`, `stack-branchbook-release` or `edge-read-back`, in reading
   order. Keep only the legend entries the diagram uses, in the order above.
4. **Check the file:**

   ```sh
   cd docs/assets/diagrams
   xmllint --noout <id>.svg
   grep -nE '<(image|script|foreignObject|filter|linearGradient|radialGradient)|@import|@font-face|href="http|url\([^#]' <id>.svg
   grep -nE '[0-9]{12}' <id>.svg
   grep -nE '(fill|stroke)="' <id>.svg
   diff <(sed -n '/<style>/,/<\/defs>/p' _template.svg) <(sed -n '/<style>/,/<\/defs>/p' <id>.svg)
   wc -c <id>.svg
   ```

   The three `grep` commands must print nothing. The `diff` may show only a second `<style>` element holding `x-`
   classes. The size must be 40 KB or less.
5. **Look at it in both themes.** Open the file in a browser and switch the operating system between light and dark
   (System Settings, Appearance on macOS; Settings, Personalisation, Colours on Windows), or use the browser's
   developer tools to emulate `prefers-color-scheme`. Narrow the window to about 830 px to see it at the README
   column's width, and check that nothing collides and every label sits inside its box.
6. **Stress the fonts.** Render a copy with every font replaced by Verdana, which is wider than any default:

   ```sh
   sed -E "s/font-family='[^']*'/font-family='Verdana'/; s/font-family:[^;}]*/font-family:Verdana/g" <id>.svg > /tmp/<id>-verdana.svg
   ```

   Nothing may overflow or collide in it.
7. **Record the check.** Set `Verified at` in the header comment and in the index row above to the commit you checked
   against, and update the alt text, the numbered list beside the image and the `<desc>` if the content changed.
8. **After pushing,** open each page that shows the diagram on github.com in four combinations: GitHub light and
   dark, each with the operating system in light and in dark.

To change a token or a shared class, edit `_template.svg`, then apply the same edit to every SVG here in one commit,
found with a `grep` for the old value.

## When a milestone lands

A milestone flips statuses by search. For milestone M4, from the repository root:

```sh
grep -rn --exclude=_template.svg 'data-milestone="M4"' docs/assets/diagrams
grep -rn 'M4' README.md SECURITY.md docs infra/README.md
```

`_template.svg` is excluded because its samples carry `data-milestone` only to show the pattern. For each element the
milestone has built:

- In an SVG, remove `class="planned"` and `data-milestone`; change `box-dash` to `box` and drop `tp` from its title;
  change the `pill-plan` pill to `pill-built` reading `built · M4`; turn a planned edge into `edge` with the `ah`
  arrowhead, and drop the milestone from its label. A partly built box loses its warn pill once its dashed chip is
  built. If nothing planned is left, remove `Planned, with milestone` and `Planned flow` from the legend.
- In a Mermaid block, remove `:::planned`, change `-.->` to `-->`, and drop the milestone from the label.
- Update the alt text, the `<desc>`, the numbered list beside the image, and `Verified at` in the header comment and
  the index.

The changes each milestone brings. Everything below has landed; no diagram draws planned work any more:

| Milestone | Diagrams |
| --- | --- |
| M3 (landed) | module-map, booking-transaction; system-at-a-glance (book and look up, `booking` built, `notification` partly built, the booking write, the key purge job); data-model (booking's writes, the PENDING row); local-runtime (the appointment routes) |
| M4 (landed) | system-at-a-glance (the booking screens) |
| M5 (landed) | module-map, confirmation-states, system-at-a-glance (the dispatcher, the gateway and `notification`), data-model (the notification status line) |
| Tier 2 (landed in M6) | appointment-lifecycle, system-at-a-glance (cancel), local-runtime (the cancel route) |
| After M6 (landed) | delivery-pipeline (the pipeline deploys the hosted demo, landed), access-boundary (landed), local-runtime (the Fargate task, landed), secret-layers (Secrets Manager, landed) |
| A new migration | data-model |
