#!/usr/bin/env bash
#
# scripts/smoke.sh: checks that a running BranchBook works end to end over HTTP. It checks the readiness probe, the
# app and its assets, the app's routes, the branch API and its error contract (including a request rejected before
# Spring MVC), real 404s, closed operational endpoints and the security headers.
#
# Usage: scripts/smoke.sh [base-url]        default: http://localhost:8080
#
# `make smoke` runs it after `make up`, and CI runs it against the stack that docker compose starts. It needs only
# bash, curl and grep. It prints one line per check and exits non-zero if any check fails.

set -euo pipefail

base_url="${1:-http://localhost:8080}"
failures=0

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
check 'an unknown branch is a 404 problem' status_is /api/v1/branches/XYZ 404
check 'problems carry a stable code' problem_code_is /api/v1/branches/XYZ BRANCH_NOT_FOUND
check 'the correlation id is echoed' echoes_correlation_id /api/v1/branches smoke-test-1
check 'a rejected request is a 400' status_is '/api/v1/branches;x=1' 400
check 'a rejected request is a problem' rejected_request_is_a_problem
check 'an unknown path is a real 404' status_is /no-such-page 404
check 'operational endpoints are closed' status_is /actuator/env 404
check 'content security policy is sent' header_is / Content-Security-Policy \
  "default-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"
check 'framing is denied' header_is / X-Frame-Options DENY
check 'no referrer is sent' header_is / Referrer-Policy no-referrer
check 'content types are not sniffed' header_is / X-Content-Type-Options nosniff

if ((failures > 0)); then
  echo "$failures check(s) failed"
  exit 1
fi
echo 'All checks passed'
