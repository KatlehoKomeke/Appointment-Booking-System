# BranchBook end-to-end tests

[Playwright](https://playwright.dev/) drives a real Chromium through the running app, the way a customer would: the
React build served by the Spring Boot jar, the API, PostgreSQL and the outbox dispatcher together. The web app's own
tests ([web/README.md](../web/README.md)) run against a fake API; these run against the real one, under the app's real
Content Security Policy.

## Run them

Node 24 and a running app are needed. From the repository root:

```bash
make up    # build and start the app on http://localhost:8080
make e2e   # install, fetch Chromium, run every test against it
```

`make e2e` runs `npm ci`, `npx playwright install chromium` and `npx playwright test` in `e2e/`. To test another
address, set `E2E_BASE_URL`, for example `make e2e E2E_BASE_URL=http://localhost:18080`. From `e2e/`:

| Command | What it does |
|---|---|
| `npm ci` | Installs the exact versions in the lockfile |
| `npm run install-browsers` | Downloads the Chromium this Playwright release drives |
| `npm test` | Runs every test against `E2E_BASE_URL` (default http://localhost:8080) |
| `npm test -- --project=desktop` | Runs one project only |
| `npm run typecheck` | Type-checks the tests |
| `npx playwright show-trace test-results/<test>/trace.zip` | Replays a failed test step by step |

Each test books real appointments, so run them against a local or demo stack, never one whose data matters. They
need no reset between runs: see [Running them again](#running-them-again).

## What they prove

| Spec | Projects | Proves |
|---|---|---|
| [book-appointment.spec.ts](book-appointment.spec.ts) | mobile, desktop | The whole journey from the landing page through branch, service, date and time, details and review to the confirmation page. A landline is refused on its field with "We need a cellphone number to SMS your confirmation."; the confirmation shows an `APT-` reference and the SMS moving from Sending to "Sent at" within 15 s; the name and number never appear in the address at any step. On `/manage`, wrong digits get "We couldn't find an appointment with those details." and the right ones open the booking. axe finds no serious or critical WCAG 2.2 AA violation on the review step or the confirmation page. |
| [keyboard.spec.ts](keyboard.spec.ts) | desktop | A booking made with Tab, Shift+Tab, Enter, Space and the arrow keys alone. The skip link is the first stop and moves focus to `main`, each step's heading takes focus, and every control tabbed to shows a focus outline. |
| [failed-delivery.spec.ts](failed-delivery.spec.ts) | desktop | A booking for 082 555 0000, which the simulated gateway always refuses, shows "Not delivered" within 60 s once the dispatcher's five attempts are spent ([BR-17](../docs/domain-rules.md#br-17)), and says the booking still stands. |

Every test also fails if the page breaks the Content Security Policy, throws an uncaught error, or writes an error
to the console that the test did not declare; the 404 of a deliberately wrong lookup is declared.

The two projects are a 390 by 844 phone at twice the pixel density, and a 1280 by 800 desktop. Both run Chromium in
the New York time zone, so a time shown in SAST proves the app does not use the device's zone
([BR-20](../docs/domain-rules.md#br-20)). Tests run one at a time, since they share one database.

## Running them again

The database is never reset between runs, so a fixed customer would meet one-appointment-per-service
([BR-10](../docs/domain-rules.md#br-10)) on the second run, and a fixed time the overlap rule
([BR-9](../docs/domain-rules.md#br-9)). [fixtures.ts](fixtures.ts) avoids both: before each booking it picks a random
demo number from 082 555 0100 to 082 555 0199, and a random branch, service, open date and free time from the API
(`GET /api/v1/branches/{code}` and `/availability`). When the API still answers 409 `DUPLICATE_APPOINTMENT` or
`CUSTOMER_OVERLAP`, it books again with another.

082 555 0000 is one number, so it can hold one upcoming appointment per service: six in all. After six runs of
`failed-delivery.spec.ts` against one database, the test fails until those appointments are past, or `make down`
deletes the database.

## Screenshots

With `SCREENSHOTS=1`, `book-appointment.spec.ts` also saves a full-page PNG of each step to
[docs/assets/walkthrough/](../docs/assets/walkthrough/), named `<project>-<nn>-<step>.png`, for example
`mobile-06-review.png`. Without it, nothing is written outside `e2e/`.

```bash
cd e2e && SCREENSHOTS=1 E2E_BASE_URL=http://localhost:8080 npx playwright test book-appointment
```

The pictures show synthetic data only: the demo name, a demo number and a random reference.

## Output

Locally the list reporter prints each test; on CI (`CI` set) the run writes an HTML report to `playwright-report/`
and annotates the pull request, and a failed test is retried once. A failure keeps its screenshot and trace in
`test-results/`. All three output folders are git-ignored, since they can show a test's customer.
