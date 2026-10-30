#!/usr/bin/env bash
#
# scripts/smoke.sh: checks that a running BranchBook works end to end over HTTP. It checks the readiness probe, the
# app and its assets, the app's routes, the branch and availability APIs and their error contract (including a request
# rejected before Spring MVC), that the booking and lookup endpoints answer, the OpenAPI document and Swagger UI, real
# 404s, closed operational endpoints, the metrics endpoint and the security headers.
#
# It writes nothing, so it is safe against any instance: the booking and lookup checks use requests that are refused
# before the database is touched, and a lookup of a reference that cannot exist, which is never counted.
#
# Usage: scripts/smoke.sh [base-url]        default: http://localhost:8080
#
# `make smoke` runs it after `make up`, and CI runs it against the stack that docker compose starts. It needs only
# bash, curl and grep. It prints one line per check and exits non-zero if any check fails.
#
# EXPECT_DEMO_NUMBERS_ONLY=1 adds a check that the instance takes only the published demo numbers, as the hosted demo
# must (ADR-0006, BRANCHBOOK_BOOKING_DEMO_NUMBERS_ONLY=true), so a deployment that left the setting off fails its smoke
# test instead of taking real numbers unnoticed.
#
# EXPECT_PROD_PROFILE=1 checks a deployment running the prod profile (staging, production): the OpenAPI document and
# Swagger UI are 404s, no /actuator path is served on the public port (404, or 403 from the edge firewall), and the
# Content-Security-Policy is checked for the directives it must hold rather than for the local string, because a
# CAPTCHA adds to it. The metrics check is left out: the actuator has a port of its own there.
#
# EXPECT_CAPTCHA=1 checks that the edge demands a CAPTCHA: a booking without a token answers 405 with
# x-amzn-waf-action: captcha, and GET /api/v1/client-config publishes the CAPTCHA script. The booking refusal checks
# then cannot reach the application, so they are skipped, unless SMOKE_SYNTHETIC_TOKEN is set: staging's
# synthetic-traffic token, sent as x-branchbook-synthetic on every POST, which the CAPTCHA rule lets through. It is
# never printed. Without these variables the script behaves exactly as before.

set -euo pipefail

base_url="${1:-http://localhost:8080}"
failures=0
expect_prod_profile="${EXPECT_PROD_PROFILE:-}"
expect_captcha="${EXPECT_CAPTCHA:-}"

# Extra headers for every POST: the synthetic-traffic token, where one is given.
post_headers=()
if [[ -n "${SMOKE_SYNTHETIC_TOKEN:-}" ]]; then
  post_headers=(--header "x-branchbook-synthetic: $SMOKE_SYNTHETIC_TOKEN")
fi

# Runs one check: the first argument describes it, the rest is the command that must succeed.
check() {
  local description="$1"
  shift
  if "$@"; then
    printf 'ok    %s\n' "$description"
  else
    printf 'FAIL  %s\n' "$description"
    failures=$((failures + 1))
  fi
}

# Succeeds if GET <path> answers with the given HTTP status.
status_is() {
  local actual
  actual="$(curl --silent --output /dev/null --write-out '%{http_code}' "$base_url$1")"
  [[ "$actual" == "$2" ]] || { printf '      %s answered %s, expected %s\n' "$1" "$actual" "$2"; return 1; }
}

# Succeeds if GET <path> answers 404 from the application or 403 from an edge firewall in front of it, such as the
# hosted demo's WAF, which blocks every /actuator path before the app sees it. Either way the endpoint is closed.
is_closed() {
  local actual
  actual="$(curl --silent --output /dev/null --write-out '%{http_code}' "$base_url$1")"
  [[ "$actual" == 404 || "$actual" == 403 ]] \
    || { printf '      %s answered %s, expected 404 or 403\n' "$1" "$actual"; return 1; }
}

# Succeeds if the body of GET <path> contains the given text.
body_contains() {
  curl --silent --fail "$base_url$1" | grep --quiet --fixed-strings -- "$2"
}

# Succeeds if GET <path> returns the header with exactly the given value.
header_is() {
  curl --silent --fail --dump-header - --output /dev/null "$base_url$1" | tr -d '\r' \
    | grep --quiet --ignore-case --line-regexp --fixed-strings -- "$2: $3"
}

# Succeeds if GET <path> answers with a problem body carrying the given code, whatever the status.
problem_code_is() {
  curl --silent "$base_url$1" | grep --quiet --fixed-strings -- "\"code\":\"$2\""
}

# Succeeds if a correlation id sent with GET <path> comes back in the response header.
echoes_correlation_id() {
  curl --silent --fail --dump-header - --output /dev/null --header "X-Correlation-Id: $2" "$base_url$1" \
    | tr -d '\r' | grep --quiet --ignore-case --line-regexp --fixed-strings -- "X-Correlation-Id: $2"
}

# Succeeds if a request that Spring Security's firewall rejects (a ';' in the path) still gets a problem body with the
# correlation id it was sent, rather than the servlet container's default error page.
rejected_request_is_a_problem() {
  local response
  response="$(curl --silent --header "X-Correlation-Id: smoke-rejected-1" "$base_url/api/v1/branches;x=1")"
  grep --quiet --fixed-strings -- '"code":"VALIDATION_FAILED"' <<<"$response" \
    && grep --quiet --fixed-strings -- '"correlationId":"smoke-rejected-1"' <<<"$response"
}

# Prints a branch's last bookable date, as GET /api/v1/branches/<code> publishes it. The smoke test runs on the real
# clock, so it asks the server for a date inside the window rather than computing one.
bookable_to() {
  curl --silent --fail "$base_url/api/v1/branches/$1" | grep --only-matching -E '"bookableTo":"[0-9-]+"' | cut -d'"' -f4
}

# Succeeds if Sandton's FICA availability on its last bookable date lists slots with the branch's +02:00 offset, or,
# should that date be a public holiday, says so.
availability_is_served() {
  local date body
  date="$(bookable_to SAN)"
  [[ -n "$date" ]] || return 1
  body="$(curl --silent --fail "$base_url/api/v1/branches/SAN/availability?service=FICA&date=$date")"
  grep --quiet --fixed-strings -- "\"date\":\"$date\"" <<<"$body" \
    && grep --quiet -E '"startsAt":"[^"]+\+02:00"|"closedReason":"PUBLIC_HOLIDAY"' <<<"$body"
}

# Succeeds if a booking of Sandton's FICA update, sent with the given mobile number and Idempotency-Key (empty to leave
# the header out), is refused with the given code. It starts at the given start, or at 09:00 on Sandton's last bookable
# date. Each refusal it is asked for comes before the booking transaction, so nothing is written.
booking_refused_with() {
  local mobile="$1" key="$2" code="$3" starts_at="${4:-}" body
  [[ -n "$starts_at" ]] || starts_at="$(bookable_to SAN)T09:00:00+02:00"
  body="{\"branchCode\":\"SAN\",\"serviceCode\":\"FICA\",\"startsAt\":\"$starts_at\","
  body+="\"customer\":{\"firstName\":\"Smoke\",\"lastName\":\"Test\",\"mobile\":\"$mobile\"}}"
  curl --silent --request POST --header 'Content-Type: application/json' ${key:+--header "Idempotency-Key: $key"} \
    ${post_headers[@]+"${post_headers[@]}"} --data "$body" "$base_url/api/v1/appointments" \
    | grep --quiet --fixed-strings -- "\"code\":\"$code\""
}

# Succeeds if a booking sent without a CAPTCHA token, and without the synthetic-traffic token, is stopped at the edge
# with 405 and x-amzn-waf-action: captcha, which is how AWS WAF asks a non-HTML request for a CAPTCHA. The request
# carries no Idempotency-Key, so an instance without the CAPTCHA refuses it before writing anything.
booking_needs_a_captcha() {
  local headers
  headers="$(curl --silent --output /dev/null --dump-header - --request POST --header 'Content-Type: application/json' \
    --data '{}' "$base_url/api/v1/appointments" | tr -d '\r')"
  grep --quiet -E '^HTTP/[0-9.]+ 405' <<<"$headers" \
    && grep --quiet --ignore-case --line-regexp --fixed-strings -- 'x-amzn-waf-action: captcha' <<<"$headers"
}

# Succeeds if the page's Content-Security-Policy holds every directive the app relies on, whatever else it adds.
csp_has_the_required_directives() {
  local policy directive
  policy="$(curl --silent --fail --dump-header - --output /dev/null "$base_url/" | tr -d '\r' \
    | grep --ignore-case '^content-security-policy:' | cut -d' ' -f2-)"
  for directive in "default-src 'self'" "img-src 'self' data:" "object-src 'none'" "base-uri 'self'" \
    "form-action 'self'" "frame-ancestors 'none'"; do
    grep --quiet --fixed-strings -- "$directive" <<<"$policy" \
      || { printf '      the policy lacks %s\n' "$directive"; return 1; }
  done
}

# Succeeds if GET <path>, sent with the given X-Mobile-Last4 (empty to leave it out), answers with the given code.
lookup_answers() {
  curl --silent ${2:+--header "X-Mobile-Last4: $2"} "$base_url$1" | grep --quiet --fixed-strings -- "\"code\":\"$3\""
}

# Succeeds if /actuator/prometheus serves the application's two meters in Prometheus's text format. The scrape is read
# whole before it is searched, so a search that stops at the first match cannot cut curl off mid-body. A 403 also
# passes: an edge firewall in front of the app, such as the hosted demo's WAF, blocks every /actuator path on purpose,
# and the metrics are then read from inside the network instead.
metrics_are_served() {
  local scrape status
  status="$(curl --silent --output /dev/null --write-out '%{http_code}' "$base_url/actuator/prometheus")"
  if [[ "$status" == 403 ]]; then
    printf '      /actuator/prometheus is blocked at the edge (403), so the meters are not checked here\n'
    return 0
  fi
  scrape="$(curl --silent --fail "$base_url/actuator/prometheus")"
  grep --quiet --fixed-strings -- 'appointments_booked_total{outcome="confirmed"}' <<<"$scrape" \
    && grep --quiet -E '^outbox_pending ' <<<"$scrape"
}

# Succeeds if the script that index.html loads is served, which proves the web build is inside the jar.
app_script_is_served() {
  local script
  script="$(curl --silent --fail "$base_url/" | grep --only-matching -E 'src="/assets/[^"]+\.js"' | head -n 1 | cut -d'"' -f2)"
  [[ -n "$script" ]] && status_is "$script" 200
}

echo "Smoke-testing $base_url"

check 'readiness probe is UP' body_contains /readyz '"status":"UP"'
check 'liveness probe is UP' body_contains /livez '"status":"UP"'
check 'the app is served at /' body_contains / '<div id="root"></div>'
check "the app's script is served" app_script_is_served
check 'an app route serves the app' body_contains /appointments/APT-7K3M-9Q2X '<div id="root"></div>'
check 'the branch API lists the seeded branches' body_contains /api/v1/branches '"code":"POL"'
check 'a branch lists its bookable window' body_contains /api/v1/branches/SAN '"bookableFrom":'
check 'availability lists slots in South African time' availability_is_served
check 'a date outside the window is refused' \
  problem_code_is '/api/v1/branches/SAN/availability?service=FICA&date=2000-01-01' DATE_OUTSIDE_BOOKING_WINDOW
check 'a service the branch does not offer is refused' \
  problem_code_is "/api/v1/branches/GQE/availability?service=HOME&date=$(bookable_to GQE)" SERVICE_NOT_OFFERED_AT_BRANCH
if [[ "$expect_captcha" == 1 ]]; then
  check 'a booking without a CAPTCHA token is asked for one at the edge' booking_needs_a_captcha
  check 'the client configuration publishes the CAPTCHA script' \
    body_contains /api/v1/client-config '"scriptUrl":"https://'
fi
# Behind a CAPTCHA, a POST reaches the application only with the synthetic-traffic token.
if [[ "$expect_captcha" != 1 || -n "${SMOKE_SYNTHETIC_TOKEN:-}" ]]; then
  check 'a booking without an Idempotency-Key is refused' \
    booking_refused_with '082 555 0199' '' IDEMPOTENCY_KEY_REQUIRED
  check 'a booking with a landline is refused' \
    booking_refused_with '021 555 0199' 2f1c7a4e-3b5d-4c6e-8f9a-0b1c2d3e4f5a MOBILE_NUMBER_REQUIRED
  # 082 555 0200 is the first number past the published demo numbers. The start is long past, so an instance with the
  # setting off refuses the booking too, for its date, inside a transaction that is rolled back: nothing is written
  # either way, and the check fails because the code is DATE_OUTSIDE_BOOKING_WINDOW.
  if [[ "${EXPECT_DEMO_NUMBERS_ONLY:-}" == 1 ]]; then
    check 'a booking with a mobile outside the demo numbers is refused' \
      booking_refused_with '082 555 0200' 6b0e3d2a-9c4f-4a7e-8d1b-2e5f7a9c0d3e DEMO_NUMBER_REQUIRED \
      2000-01-03T09:00:00+02:00
  fi
else
  echo 'skip  the booking refusal checks: the edge asks every POST for a CAPTCHA, and no SMOKE_SYNTHETIC_TOKEN is set'
fi
check 'a lookup without the last four digits is refused' \
  lookup_answers /api/v1/appointments/APT-7K3M-9Q2X '' VALIDATION_FAILED
check 'a lookup of a reference that cannot exist is a 404' \
  lookup_answers /api/v1/appointments/not-a-reference 0199 APPOINTMENT_NOT_FOUND
if [[ "$expect_prod_profile" == 1 ]]; then
  check 'the OpenAPI document is not served' status_is /v3/api-docs 404
  check 'Swagger UI is not served' status_is /swagger-ui/index.html 404
else
  check 'the OpenAPI document is served' body_contains /v3/api-docs '"openapi":"3.1.0"'
  check 'Swagger UI is served' status_is /swagger-ui/index.html 200
fi
check 'an unknown branch is a 404 problem' status_is /api/v1/branches/XYZ 404
check 'problems carry a stable code' problem_code_is /api/v1/branches/XYZ BRANCH_NOT_FOUND
check 'the correlation id is echoed' echoes_correlation_id /api/v1/branches smoke-test-1
check 'a rejected request is a 400' status_is '/api/v1/branches;x=1' 400
check 'a rejected request is a problem' rejected_request_is_a_problem
check 'an unknown path is a real 404' status_is /no-such-page 404
check 'operational endpoints are closed' is_closed /actuator/env
if [[ "$expect_prod_profile" == 1 ]]; then
  for path in /actuator /actuator/health /actuator/info /actuator/prometheus; do
    check "$path is not served on the public port" is_closed "$path"
  done
  check 'content security policy holds the required directives' csp_has_the_required_directives
else
  check 'the metrics are served for Prometheus, unless an edge firewall blocks them' metrics_are_served
  check 'content security policy is sent' header_is / Content-Security-Policy \
    "default-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"
fi
check 'framing is denied' header_is / X-Frame-Options DENY
check 'no referrer is sent' header_is / Referrer-Policy no-referrer
check 'content types are not sniffed' header_is / X-Content-Type-Options nosniff

if ((failures > 0)); then
  echo "$failures check(s) failed"
  exit 1
fi
echo 'All checks passed'
