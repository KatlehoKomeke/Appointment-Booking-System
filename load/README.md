# Load test

[`booking.js`](booking.js) is a [k6](https://grafana.com/docs/k6/latest/) script for BranchBook's two busiest paths:
reading a day's availability, and booking. It runs two scenarios side by side at a fixed arrival rate:

| Scenario | Each iteration |
|---|---|
| `browse` | `GET /api/v1/branches/{code}/availability` for a random branch, service and bookable date |
| `book` | the same read, then `POST /api/v1/appointments` for a free slot, a random published demo number (082 555 0001 to 0199) and a fresh `Idempotency-Key`, as the web app would |

Every customer detail is synthetic. A booking refused by design (409 `SLOT_FULL`, `DUPLICATE_APPOINTMENT` or
`CUSTOMER_OVERLAP`, or a 422) is an outcome, not a failure: with only 199 demo numbers, each of which may hold one
upcoming appointment per service, a database that has been loaded before refuses more of them as duplicates.

The script books real appointments in the target's database. Run it against a local stack or staging, **never
production**.

## Pass or fail

k6 exits non-zero if any threshold is crossed:

| Threshold | Default |
|---|---|
| 95th-percentile latency of availability reads | under 500 ms (`P95_READ_MS`) |
| 95th-percentile latency of bookings | under 1000 ms (`P95_BOOK_MS`) |
| `http_req_failed`: answers other than 200, 201, 409 and 422 | under 1% |
| `unexpected_responses`: the same, counted by the script | under 1% |
| `server_errors`: any 5xx | none at all |
| `checks` | over 99% pass |

## Settings

All come from the environment (`-e NAME=value` with Docker):

| Variable | Meaning | Default |
|---|---|---|
| `BASE_URL` | Where the app is | required |
| `SYNTHETIC_TOKEN` | Sent as `x-branchbook-synthetic` on every request, so staging's CAPTCHA rule lets the bookings through. Production has no bypass. | not sent |
| `READ_RATE` | Availability reads started per second by `browse` | `20` |
| `BOOK_RATE` | Bookings started per second by `book`; `0` runs reads only | `5` |
| `DURATION` | How long both scenarios run | `1m` |
| `P95_READ_MS`, `P95_BOOK_MS` | The latency thresholds, in milliseconds | `500`, `1000` |

## Run it on a local stack

k6 runs from its Docker image, pinned by digest (1.8.1), so nothing is installed. From the repository root:

```sh
COMPOSE_PROJECT_NAME=bb-load APP_PORT=18090 make up
docker run --rm --network bb-load_default -v "$PWD/load:/load:ro" -e BASE_URL=http://app:8080 \
  grafana/k6:1.8.1@sha256:23f2279054c3e01535455c4d92914a625df12aeb631ea0e3ea7a43f96bcbc843 run /load/booking.js
COMPOSE_PROJECT_NAME=bb-load make down
```

The container joins the compose network and reaches the app by its service name. To read the app's own view
afterwards, `curl -s http://127.0.0.1:18090/actuator/prometheus | grep -E '^(outbox_|resilience4j_circuitbreaker_state)'`.

## Run it against staging

Set `BASE_URL=https://stg.branchbook.click` and `SYNTHETIC_TOKEN` to the value of the staging secret
`branchbook-staging/synthetic-traffic-token`. WAF's per-address rate limits still apply to the synthetic token, and
they block: from one address, keep `BOOK_RATE` well under the booking limit (10 bookings per 5 minutes by default) or
set it to `0`, and keep `READ_RATE` under the all-requests limit, or the run fails on 403s that are WAF doing its
job.

## Observed results

These numbers depend on the machine, Docker's share of it and the database's contents; they are a baseline for
spotting a regression on the same machine, not a capacity figure. They were taken on an Apple M5 Pro laptop with
Docker Desktop given 18 CPUs and 8 GB, against `make up` (the app and PostgreSQL 17 in containers), on a database
holding only the demo data.

| Profile | Requests | Availability p95 | Booking p95 | Confirmed / refused by design | 5xx |
|---|---|---|---|---|---|
| Defaults: 20 reads/s and 5 bookings/s for 1 minute | 1,764 | 7.2 ms | 18.8 ms | 232 / 23 | 0 |
| Then 100 reads/s and 20 bookings/s for 1 minute | 8,218 | 4.7 ms | 14.8 ms | 547 / 462 | 0 |

Delivery does not keep up with 9 bookings a second locally, and is not meant to: the simulated gateway takes 150 ms
a message, so a tick of 20 messages takes about 3 seconds and the dispatcher delivers about 4 a second. Straight
after the heavier run, `outbox_pending` was 302 and `outbox_oldest_pending_age_seconds` 43.5; both were back at 0
within two minutes, with the circuit breaker closed throughout. `branchbook.dispatcher.batch-size`
(`BRANCHBOOK_DISPATCHER_BATCH_SIZE`) and `interval` set that rate, and an alarm on the oldest pending message's age
should allow for the backlog a burst leaves.
