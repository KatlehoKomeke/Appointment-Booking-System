# ADR-0005: A committed OpenAPI contract with a generated TypeScript client

- **Status:** Accepted
- **Date:** 2026-10-22

## Context

The web app and the API change together, so their contract must not drift silently. Reviewers should be able to
read the API without running the code.

## Decision

- springdoc generates the OpenAPI 3.1 document from the controllers. `docs/openapi.yaml` is committed, and
  `OpenApiContractIT` fails when the generated document differs from the committed file.
- The web app's types come from that file through `openapi-typescript`, and requests go through `openapi-fetch`.
  CI fails when the generated client differs from the committed one.
- After an intended API change, `make contract` regenerates both files, which are committed with the change.
- Error codes are part of the contract and are catalogued in [errors.md](../errors.md).

## Alternatives considered

- **Hand-written TypeScript types.** Drift stays invisible until something breaks at runtime.
- **Spec-first server generation.** It adds a code generator to the back end for a single client.

## Consequences

- Every contract change is a reviewed diff, in the same commit as the server and client changes.
- Swapping the front end, for example to Vue, touches only `web/`; nothing below the contract changes.
