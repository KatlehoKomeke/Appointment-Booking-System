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
  wrong lookups of one reference block it for an hour from the first with `429`.
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
- A mobile number alone opens nothing, but it is not secret either. Someone who books with another person's number
  learns from `409 DUPLICATE_APPOINTMENT` or `409 CUSTOMER_OVERLAP` that an appointment exists for it, though never
  its reference, branch or time ([BR-23](../domain-rules.md#br-23)). This is accepted for the demo; OTP
  verification closes it.
- Someone who knows a reference can block its lookup for an hour with five wrong tries. A reference appears only
  in the customer's own SMS and pages, so this is accepted too. The limit's counts are kept in memory for one
  instance ([docs/architecture.md](../architecture.md), section 4).
- Branch check-in is the natural next feature, and its domain logic already exists.

## Notes

- **2026-10-26.** The risks under Consequences, with their production controls, are listed in
  [SECURITY.md](../../SECURITY.md#known-risks).
- **2026-10-27.** Where `BRANCHBOOK_SMS_GATEWAY=aws`, as on the hosted demo, the confirmation and the cancellation are
  real SMS, so the "unauthenticated SMS sending" risk is real there. No login or one-time code is added; the controls
  are a protect configuration that allows South Africa only, the per-recipient daily cap (`RECIPIENT_DAILY_CAP`,
  [BR-17](../domain-rules.md#br-17)), WAF's per-address booking limit, `MaxPrice`, AWS's monthly spend limit and, until
  the owner leaves it, the SMS sandbox. Anyone can still make the app text any South African number, up to the cap a
  day ([SECURITY.md](../../SECURITY.md#known-risks)).
