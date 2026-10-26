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
- **Booking** (`POST /api/v1/appointments`), in this order, so a request that is wrong in several ways gets the
  first:
  1. `400 VALIDATION_FAILED`: the body cannot be read, has a field the request does not define, or breaks a
     field rule. Field-rule problems are listed in `errors[]` together. A body that cannot be read, or has an
     unknown field, stops before the rules are checked, so it reports only that one problem.
  2. `400 IDEMPOTENCY_KEY_REQUIRED`: the `Idempotency-Key` header is missing or not a UUID.
  3. `422 MOBILE_NUMBER_REQUIRED`: the number is missing or not a South African mobile number. Then, only where the
     deployment takes only the published demo numbers, `422 DEMO_NUMBER_REQUIRED`: the number is a South African
     mobile outside them.
  4. `422 IDEMPOTENCY_KEY_REUSED`, or a replay of the key's booking. Nothing after this step runs for a replay.
  5. `404 BRANCH_NOT_FOUND`, then `422 SERVICE_NOT_OFFERED_AT_BRANCH`.
  6. The one slot code, by the precedence above.
  7. The 409 conflicts, by the precedence above.
  8. `503 TRY_AGAIN` or `500 INTERNAL_ERROR`, which can happen at any step that reaches the database.

  Steps 1 to 3 write nothing. A refusal from step 4 onwards rolls the whole transaction back, the idempotency key's
  row included, so the same key can be sent again with corrected details.
- **Lookup** (`GET /api/v1/appointments/{reference}`): a missing or malformed `X-Mobile-Last4` is
  `400 VALIDATION_FAILED` and is not counted; then a blocked reference is `429 LOOKUP_RATE_LIMITED`, even with the
  right digits; then any mismatch is `404 APPOINTMENT_NOT_FOUND` and is counted. A reference that cannot be a
  reference at all is a `404` and is not counted, because it can never become a match. A lookup counts as a try
  while it is being checked and is released if its digits were right, so lookups of one reference that arrive
  together are refused with `429` and `Retry-After: 1` once the wrong ones and those still being checked reach five.
- **Cancel** (`POST /api/v1/appointments/{reference}/cancel`): exactly as a lookup up to the `404`, and counted
  towards the same five wrong tries of the reference. Then an appointment that is already cancelled is a `200` that
  writes nothing, even after its start; then one that has started is `409 TOO_LATE_TO_CANCEL` and is left as it
  was. A second cancel of the same appointment at the same moment is not an error: its version check fails, it
  reads the appointment once more and returns the same `200`. A lock not granted within 2 s is `503 TRY_AGAIN`.
- **Retry** (`POST /api/v1/messages/{id}/retry`, only while `branchbook.inbox.enabled` is true): an `id` that is not
  a UUID is `400 VALIDATION_FAILED`; then an unknown id is `404 MESSAGE_NOT_FOUND`. Any message that exists is a
  `200`: reset to PENDING if a retry could send it, otherwise returned unchanged with `retryable` false.

## Field codes

Each item of `errors[]` names the field as the client sent it: a JSON path such as `customer.firstName`, a header
name such as `X-Mobile-Last4`, or the name of a path or query parameter such as `code`, `service` or `date`. Its
`code` is one of these:

| Code | Field | Meaning |
|---|---|---|
| `REQUIRED` | a header or query parameter | It is missing. |
| `NOT_NULL` | a body field | It is missing or `null`. |
| `NOT_BLANK` | `customer.firstName`, `customer.lastName` | It is missing or only spaces. |
| `PATTERN` | `branchCode` and `serviceCode` in a body; `code` or `id` in a path; `service` in a query; `X-Mobile-Last4` | It has the wrong shape: 2 to 8 letters for a branch code, 2 to 12 letters for a service code, a UUID for a message id, four digits for the header. |
| `NAME_INVALID` | `customer.firstName`, `customer.lastName` | Not a name: 1 to 60 characters that start with a letter and hold only letters, spaces, hyphens and apostrophes. |
| `OFFSET_REQUIRED` | `startsAt` | Not an ISO-8601 date and time with an offset, such as `2026-10-27T09:30:00+02:00`. |
| `UNKNOWN_FIELD` | the unknown field, or `(body)` | The request does not define it, such as `endsAt`, which the server computes. Only the first is reported. |
| `TYPE_MISMATCH` | a query parameter | It cannot be read as its type, such as a date. |
| `MIN`, `MAX` | `limit` in a query | Below or above its range: 1 to 200 for the inbox. |
| `MOBILE_NUMBER_REQUIRED` | `customer.mobile` | The same as the problem's code. |
| `DEMO_NUMBER_REQUIRED` | `customer.mobile` | The same as the problem's code. |
| a slot code | `startsAt` | The same as the problem's code: `SLOT_OFF_GRID`, for example. |

A field problem's `message` is a short English hint, such as "must be 4 digits"; it never quotes the value sent.

## Retry-After

| Status | Seconds | When |
|---|---|---|
| `429 LOOKUP_RATE_LIMITED` | Up to 3600 | The seconds left until an hour after the reference's first wrong lookup or cancel, rounded up. |
| `429 LOOKUP_RATE_LIMITED` | 1 | Fewer than five wrong lookups, but the wrong lookups and the lookups of the reference still being checked together reach five. |
| `503 TRY_AGAIN` | 2 | A lock not granted within 2 s, a statement over 5 s, a deadlock or serialisation failure that happened again after the one automatic re-run, or a cancel whose version check failed again after its one re-read. |
| `503 TRY_AGAIN` | 5 | No database connection within 3 s, or any other temporary failure. |

## Codes

| Code | HTTP | When it happens | What the customer sees |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | The request is malformed: a missing or badly shaped field, unknown JSON property, a `startsAt` without an offset, or a missing or malformed `X-Mobile-Last4` header. | Inline messages on the fields named in `errors[]`. |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | `POST /api/v1/appointments` arrived without an `Idempotency-Key` header holding a UUID. | Nothing; the web app always sends one. |
| `BRANCH_NOT_FOUND` | 404 | The branch code is unknown or the branch is inactive. | "We couldn't find that branch." |
| `APPOINTMENT_NOT_FOUND` | 404 | The reference is unknown or malformed, **or** the last four digits are wrong. Every case returns the same body, the digits are compared in constant time, and the answer is never `403`, so the API cannot be used to discover references. | "We couldn't find an appointment with those details." |
| `MESSAGE_NOT_FOUND` | 404 | Retry of a simulated message id that does not exist. The retry, `POST /api/v1/messages/{id}/retry`, exists only while `branchbook.inbox.enabled` is true, as the inbox does; otherwise its path is a plain `404` with no code. | Not shown to customers. |
| `SERVICE_NOT_OFFERED_AT_BRANCH` | 422 | The branch does not offer the requested service, or no active service has that code. | "This branch doesn't offer that service." |
| `DATE_OUTSIDE_BOOKING_WINDOW` | 422 | The date is outside the 30 bookable days, `[today, today + 30 days)` in the branch's time zone. | The date is not selectable. |
| `SLOT_ON_CLOSED_DAY` | 422 | The branch is closed that day, or it is a public holiday. | The day is shown as closed, with the reason. |
| `SLOT_OUTSIDE_HOURS` | 422 | The slot would start before opening or end after closing. | The slot is not offered. |
| `SLOT_OFF_GRID` | 422 | The start time is not on the service's slot grid. | The slot is not offered. |
| `SLOT_WITHIN_LEAD_TIME` | 422 | The slot starts less than two hours from now; usually a page left open too long. | "That time is too soon. Please choose a later slot." |
| `MOBILE_NUMBER_REQUIRED` | 422 | The number is missing or not a South African mobile number (for example a landline or a VoIP number). `errors[]` names `customer.mobile`. | "We need a cellphone number to SMS your confirmation." |
| `DEMO_NUMBER_REQUIRED` | 422 | Only where the deployment takes only the published demo numbers (`branchbook.booking.demo-numbers-only`, which the hosted demo switches on): the number is a South African mobile outside 082 555 0000 to 0199. Decided with `MOBILE_NUMBER_REQUIRED`, before anything is written, so a real person's number is never stored or sent an SMS ([ADR-0006](adr/0006-hosted-demo-on-aws-with-cdk.md)). `errors[]` names `customer.mobile`; the body never repeats the number. | "Use a demo number. This demo accepts only the numbers 082 555 0000 to 082 555 0199. Try 082 555 0101." On the cellphone field: "This demo accepts only 082 555 0000 to 082 555 0199, for example 082 555 0101." |
| `IDEMPOTENCY_KEY_REUSED` | 422 | The same `Idempotency-Key` was sent with a different request body. | Nothing; the web app mints a new key when the details change. |
| `DUPLICATE_APPOINTMENT` | 409 | This mobile already has an upcoming appointment for this service. The body names no reference, branch or time. | "You already have an upcoming appointment for this service." |
| `CUSTOMER_OVERLAP` | 409 | This mobile already has an appointment at an overlapping time, at any branch. | "You already have an appointment at that time." |
| `SLOT_FULL` | 409 | The last seat was taken while the customer was filling in their details. Carries up to three `alternatives[]`. | "That time was just taken", with the alternatives and the details kept. |
| `TOO_LATE_TO_CANCEL` | 409 | Cancellation was requested at or after the appointment's start time. The appointment is left as it was, still `CONFIRMED`. Cancelling an appointment that is already cancelled is a `200`, never this. | "This appointment has already started." |
| `LOOKUP_RATE_LIMITED` | 429 | Five wrong tries of one reference, lookups and cancels together, block it, even for the right digits, until an hour after the first. Carries `Retry-After`. | "Too many attempts. Try again later." |
| `INTERNAL_ERROR` | 500 | An unexpected failure, such as a second reference collision in one booking. Only the correlation id is returned. | A generic error with the correlation id to quote. |
| `TRY_AGAIN` | 503 | A lock or statement timeout, a deadlock after one automatic retry, no database connection, or any other temporary failure. Always carries `Retry-After`. | The web app retries once with the same idempotency key, then asks the customer to try again. |

Not in v1: `413 PAYLOAD_TOO_LARGE`. Request-body size limits belong to the gateway or WAF in front of the
application, and the request-body filter was cut from the plan.
