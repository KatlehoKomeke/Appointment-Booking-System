# BranchBook web app

The React app customers use to book, look up and cancel branch appointments. In production it is not deployed on
its own: the Dockerfile builds it and places the output inside the Spring Boot jar, which serves the app and the API
from one origin (see [docs/architecture.md](../docs/architecture.md), section 2).

**Status (M2):** a placeholder page showing the fictional-bank notice, and the typed API client the booking flow
will use. The booking flow is built in M4.

## Stack

React 19 and TypeScript (strict) on Vite. Vitest with Testing Library runs the tests in jsdom. Exact versions are
in [package.json](package.json) and the lockfile, and the main ones in the root [README](../README.md#versions).

## Commands

Node 24 is required (the repository's [.nvmrc](../.nvmrc) selects it). Run these from `web/`:

| Command | What it does |
|---|---|
| `npm ci` | Installs the exact dependency versions from the lockfile |
| `npm run dev` | Starts the dev server on http://localhost:5173 with hot reload |
| `npm test` | Runs the unit tests once (`npm run test:watch` keeps them running) |
| `npm run lint` | Lints, failing on any warning |
| `npm run typecheck` | Type-checks the app and its configuration |
| `npm run format` | Formats with Prettier (`npm run format:check` only checks) |
| `npm run build` | Type-checks, then writes the production build to `dist/` |
| `npm run generate:api` | Regenerates `src/api/schema.d.ts` from [docs/openapi.yaml](../docs/openapi.yaml) |
| `npm run check:api` | Fails if `src/api/schema.d.ts` is not what `generate:api` would write |

The dev server forwards `/api`, `/actuator`, `/readyz` and `/livez` to the API on port 8080, so the app talks to
one origin in development as in production. Start the API with `make dev-api` from the repository root.

## Talking to the API

[src/api/client.ts](src/api/client.ts) is the only way the app calls the API. It is
[openapi-fetch](https://openapi-ts.dev/openapi-fetch/) typed by `src/api/schema.d.ts`, which
[openapi-typescript](https://openapi-ts.dev/) generates from the committed contract
([ADR-0005](../docs/adr/0005-committed-openapi-contract.md)). A path, parameter or field the API does not have is a
type error:

```ts
const day = await unwrap(
  api.GET('/api/v1/branches/{code}/availability', {
    params: { path: { code: 'RUS' }, query: { service: 'HOME', date: '2026-10-31' } },
  }),
);
day.slots[0]?.startsAt; // string, with the branch's +02:00 offset
day.closedReason; // 'CLOSED_DAY' | 'PUBLIC_HOLIDAY' | null
```

`unwrap` returns the body of a success and throws an `ApiProblem` for an error response, carrying the status, the
stable `code` (typed as the union of the codes the contract lists) and the `correlationId`. Screens choose their wording from `code`, never from the problem's `detail`.
A network failure is not an `ApiProblem`, so a screen can tell "offline" from "refused".

`schema.d.ts` is generated and committed, never edited by hand; ESLint and Prettier skip it, and `npm run
check:api` (in CI and `make test`) fails if regenerating it would change anything. After an API change, `make contract` in the repository root refreshes the contract
and this file together.

openapi-typescript 7.13.0 declares TypeScript 5 as its peer, while the app uses TypeScript 6. The `overrides` entry
in [package.json](package.json) makes it use the app's TypeScript. openapi-typescript only prints the file through
the compiler's API, and the type check and the client's tests run against what it prints.

## Quality gates

CI runs lint, the type check, the format check, the tests and the build on every push. The tests run twice, under
`TZ=UTC` and `TZ=America/New_York`, because appointment times must render in South African Standard Time on any
device ([docs/architecture.md](../docs/architecture.md), section 8).

The lint configuration, [eslint.config.js](eslint.config.js), enforces:

- **Documentation:** every exported component, hook, function and type has a TSDoc comment
  ([CONTRIBUTING.md](../CONTRIBUTING.md#typescript-web-and-infra)).
- **Type safety:** typescript-eslint's strict, type-aware rules.
- **Accessibility:** the strict rule set of `eslint-plugin-jsx-a11y-x`. This is the es-tooling fork of
  `eslint-plugin-jsx-a11y`, chosen because the original does not yet support ESLint 10
  ([jsx-eslint/eslint-plugin-jsx-a11y#1075](https://github.com/jsx-eslint/eslint-plugin-jsx-a11y/issues/1075)),
  and ESLint 9 reached end of life on 2026-08-06.

## Layout

```text
web/
├── index.html         the single page; marked noindex because the bank is fictional
├── public/            files served as they are (the favicon)
└── src/
    ├── main.tsx       mounts the app
    ├── App.tsx        the app shell
    ├── index.css      global styles and the colour tokens
    ├── api/           the typed API client; schema.d.ts is generated from docs/openapi.yaml
    └── test/setup.ts  test setup: DOM matchers and cleanup
```

## Security

The API sends a strict Content Security Policy (`default-src 'self'`), so the app must never rely on inline
scripts, inline `<style>` elements or third-party origins. Fonts, images and scripts are bundled and served from
the same origin.
