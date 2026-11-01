# ADR-0011: WAF CAPTCHA and per-address limits instead of a one-time PIN

- **Status:** Accepted
- **Date:** 2026-10-30

## Context

There is no login ([ADR-0004](0004-restraint-no-login-no-id-number.md)), and production sends a real SMS to any South
African mobile number a booking or a cancellation names. Without a check that a person is booking, a script can fill
slots, make the bank text strangers, and run up the SMS bill (SMS pumping). The per-recipient daily cap and WAF's
per-address booking limit bound the damage per number and per address, but neither tells a person from a script, and
lookups were limited only per reference.

## Decision

- **A CAPTCHA on every booking and cancellation.** AWS WAF asks for a CAPTCHA on every booking and cancellation
  `POST`. A request without a valid token is answered `405` with `x-amzn-waf-action: captcha`; a solved puzzle gives
  immunity for 300 seconds.
- **The web app resends once.** It reads the CAPTCHA's settings from `GET /api/v1/client-config`, shows AWS's puzzle
  in an accessible dialog, and once it is solved resends the same request once, with the same body and the same
  `Idempotency-Key`, so a booking can never be made twice ([web/src/api/captcha.ts](../../web/src/api/captcha.ts),
  [web/src/captcha/CaptchaProvider.tsx](../../web/src/captcha/CaptchaProvider.tsx)). Where no CAPTCHA is configured,
  as locally and in CI, nothing changes.
- **Per-address rate limits** on bookings and cancellations, on lookups, and on all requests, each with its own limit
  and window per environment in [infra/config](../../infra/config/production.json) (production: 10, 100 and 1,000 per
  address in 5 minutes).
- **AWS's managed rule groups stay:** IP reputation, known bad inputs and the common rule set.
- **Synthetic traffic, in staging only.** The pipeline's smoke and end-to-end tests in staging carry a token from
  Secrets Manager in `x-branchbook-synthetic`, and staging's CAPTCHA rule lets those requests through. Production has
  no bypass, and [lib/config.ts](../../infra/lib/config.ts) refuses one there.

The WAF rules, in order, are in [infra/README.md](../../infra/README.md#environments-staging-and-production); what
the customer sees when the check fails is in [errors.md](../errors.md).

## Alternatives considered

- **An SMS one-time PIN before booking.** Every booking would cost an extra SMS, add friction for the customer, and
  give SMS pumpers a second message to trigger. The product owner chose no one-time PIN.
- **WAF's silent Challenge action.** Less friction, but weaker against scripted abuse. It could be adopted later.
- **A third-party CAPTCHA.** It would send visitors' data to another processor.

## Consequences

- Scripted booking and cancelling now need a person, or a paid CAPTCHA-solving service, per 5 minutes of immunity,
  and stay bounded by the per-address limits and the per-recipient daily cap.
- Residual risk: a person who solves CAPTCHAs can still book up to the per-address limit, and text any South African
  number up to the daily cap; the limits bound it ([SECURITY.md](../../SECURITY.md#known-risks)).
- The real puzzle is only exercised by hand. CI uses fakes in the web tests, the bypass in staging, and in production
  asserts only that an unsolved booking is challenged.
- The content security policy adds AWS WAF's domain only when a CAPTCHA is configured (`CaptchaConfiguredIT`), so
  the strict policy is unchanged locally.
- Tests: `captcha.test.ts`, `CaptchaProvider.test.tsx`, `clientConfig.test.ts`, `ClientConfigApiIT`,
  [environments.test.ts](../../infra/test/environments.test.ts) and [config.test.ts](../../infra/test/config.test.ts).
  The threats this answers are in [threat-model.md](../threat-model.md).
