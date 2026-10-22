# ADR-0004: No login, no ID number and no staff endpoints in v1

- **Status:** Accepted
- **Date:** 2026-10-22

## Context

The brief asks customers to schedule appointments, not to log in. A login built in a week would be security
theatre: no identity provider and no real verification. POPIA requires that personal information be adequate,
relevant and not excessive for its purpose (section 10).

## Decision

- **No customer accounts.** An appointment is opened with its reference, eight characters from a 31-symbol
  alphabet, plus the last four digits of the booking mobile in the `X-Mobile-Last4` header.
- **No enumeration.** An unknown reference and wrong digits return the same `404 APPOINTMENT_NOT_FOUND`, and five
  failures for one reference within an hour return `429`.
- **Collect only what booking needs:** first name, last name and a South African mobile number. Email arrives
  only with the optional Tier 3 email channel. No South African ID number is collected.
- **No staff endpoints.** The full lifecycle (`CONFIRMED`, `CHECKED_IN`, `COMPLETED`, `CANCELLED`, `NO_SHOW`) is
  modelled and tested in the domain, but only cancellation is reachable through the API.

## Alternatives considered

- **JWT, OIDC or OTP login.** Costly to build properly in a week, and it would not be trustworthy without a real
  identity provider.
- **Collecting the ID number for FICA-style services.** It is not needed to book a time, and identity is verified
  in the branch anyway.

## Consequences

- Anyone can book with any South African mobile number, so SMS pumping and slot squatting are open risks. They are
  documented, with the production controls: OTP verification, per-recipient caps and CAPTCHA.
- Production would bind appointments to a verified customer through the bank's identity provider.
- Branch check-in is the natural next feature, and its domain logic already exists.
