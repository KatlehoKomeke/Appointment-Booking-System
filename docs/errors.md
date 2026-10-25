# Error catalogue

Every error the API returns is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `application/problem+json`
body, and every error it returns on purpose carries a stable `code`. The web app chooses its wording from the code, never from the `detail` text, so the
wording can change without breaking the client. This file is the contract for those codes.
`ProblemCodeCatalogueTest` fails the build if the table below and the `ProblemCode` enum disagree on any code or
status.

## Shape of a problem body

```json
{
  "type": "urn:branchbook:problem:SLOT_FULL",
  "title": "That time was just taken",
  "status": 409,
  "detail": "All seats for this time were booked while you were completing your details.",
  "instance": "urn:branchbook:request:3f6d2c0e-7a1b-4d7e-9f0c-2b1a9e8d7c6b",
  "code": "SLOT_FULL",
  "correlationId": "3f6d2c0e-7a1b-4d7e-9f0c-2b1a9e8d7c6b",
  "alternatives": [
    { "startsAt": "2026-10-27T09:45:00+02:00", "endsAt": "2026-10-27T10:00:00+02:00" }
  ]
}
```

Rules that apply to every problem body:

- **No personal data, ever.** A body never echoes input and never contains a name, mobile digits, an appointment
  reference or a booked time ([BR-23](domain-rules.md#br-23)).
- **`correlationId` is always present.** It matches the `X-Correlation-Id` response header and the server logs,
  so a support request can be traced without sharing personal data.
- **`instance` identifies the occurrence, not the request.** It is `urn:branchbook:request:` followed by the
  correlation id. It is never the request path, because a path is the caller's input and can carry personal data
  or a booking reference.
- **Field problems carry `errors[]`.** Each item is `{field, code, message}`; the web form maps them onto inputs.
- **Stack traces are never serialised.** An unexpected failure is a `500 INTERNAL_ERROR` with only the correlation id.
- **Failures the API never returns on purpose carry no code.** A path that does not exist (404), a wrong HTTP method
  (405), an `Accept` header the API cannot satisfy (406) or an unsupported media type (415) keeps its status and
  standard title, and has no `code`, no `type` (which RFC 9457 reads as `about:blank`) and no `detail`. It still
  carries `instance` and `correlationId`. The web app never makes such requests, so it needs no code for them.
- **Requests that fail before reaching the application still get a problem.** A URL that the security firewall
  rejects (for example one containing `;`) is a `400 VALIDATION_FAILED`, and a failure in a servlet filter is a
  `500 INTERNAL_ERROR`, both with `correlationId`.

## Precedence

One request yields exactly one code:

- **422 slot problems:** `DATE_OUTSIDE_BOOKING_WINDOW`, then `SLOT_ON_CLOSED_DAY`, `SLOT_OUTSIDE_HOURS`,
  `SLOT_OFF_GRID`, `SLOT_WITHIN_LEAD_TIME` ([BR-21](domain-rules.md#br-21)).
- **409 conflicts, decided under the per-mobile lock:** `DUPLICATE_APPOINTMENT`, then `CUSTOMER_OVERLAP`, then `SLOT_FULL`.
- **Availability:** a malformed parameter is `400 VALIDATION_FAILED`; then the branch must exist
  (`404 BRANCH_NOT_FOUND`), offer the service (`422 SERVICE_NOT_OFFERED_AT_BRANCH`) and have the date in its window
  (`422 DATE_OUTSIDE_BOOKING_WINDOW`). A closed day is not an error: it is a `200` with `closedReason` and no slots.

## Codes

| Code | HTTP | When it happens | What the customer sees |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | The request is malformed: a missing or badly shaped field, unknown JSON property, a `startsAt` without an offset, or a malformed `X-Mobile-Last4` header. | Inline messages on the fields named in `errors[]`. |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | `POST /api/v1/appointments` arrived without an `Idempotency-Key` header holding a UUID. | Nothing; the web app always sends one. |
| `BRANCH_NOT_FOUND` | 404 | The branch code is unknown or the branch is inactive. | "We couldn't find that branch." |
| `APPOINTMENT_NOT_FOUND` | 404 | The reference is unknown **or** the last four digits are wrong. Both cases return the same body and timing, so the API cannot be used to discover references. | "We couldn't find an appointment with those details." |
| `MESSAGE_NOT_FOUND` | 404 | Retry of a simulated message id that does not exist (Tier 2 retry endpoint, local profiles only). | Not shown to customers. |
| `SERVICE_NOT_OFFERED_AT_BRANCH` | 422 | The branch does not offer the requested service, or no active service has that code. | "This branch doesn't offer that service." |
| `DATE_OUTSIDE_BOOKING_WINDOW` | 422 | The date is outside the 30 bookable days, `[today, today + 30 days)` in the branch's time zone. | The date is not selectable. |
| `SLOT_ON_CLOSED_DAY` | 422 | The branch is closed that day, or it is a public holiday. | The day is shown as closed, with the reason. |
| `SLOT_OUTSIDE_HOURS` | 422 | The slot would start before opening or end after closing. | The slot is not offered. |
| `SLOT_OFF_GRID` | 422 | The start time is not on the service's slot grid. | The slot is not offered. |
| `SLOT_WITHIN_LEAD_TIME` | 422 | The slot starts less than two hours from now; usually a page left open too long. | "That time is too soon. Please choose a later slot." |
| `MOBILE_NUMBER_REQUIRED` | 422 | The number is not a South African mobile number (for example a landline or a VoIP number). | "We need a cellphone number to SMS your confirmation." |
| `IDEMPOTENCY_KEY_REUSED` | 422 | The same `Idempotency-Key` was sent with a different request body. | Nothing; the web app mints a new key when the details change. |
| `DUPLICATE_APPOINTMENT` | 409 | This mobile already has an upcoming appointment for this service. The body names no reference, branch or time. | "You already have an upcoming appointment for this service." |
| `CUSTOMER_OVERLAP` | 409 | This mobile already has an appointment at an overlapping time, at any branch. | "You already have an appointment at that time." |
| `SLOT_FULL` | 409 | The last seat was taken while the customer was filling in their details. Carries up to three `alternatives[]`. | "That time was just taken", with the alternatives and the details kept. |
| `TOO_LATE_TO_CANCEL` | 409 | Cancellation was requested at or after the appointment's start time. | "This appointment has already started." |
| `LOOKUP_RATE_LIMITED` | 429 | Five wrong lookups for one reference within an hour. Carries `Retry-After`. | "Too many attempts. Try again later." |
| `INTERNAL_ERROR` | 500 | An unexpected failure. Only the correlation id is returned. | A generic error with the correlation id to quote. |
| `TRY_AGAIN` | 503 | A lock or statement timeout, a deadlock after one automatic retry, no database connection, or any other temporary failure. Always carries `Retry-After`. | The web app retries once with the same idempotency key, then asks the customer to try again. |

Not in v1: `413 PAYLOAD_TOO_LARGE`. Request-body size limits belong to the gateway or WAF in front of the
application, and the request-body filter was cut from the plan.
