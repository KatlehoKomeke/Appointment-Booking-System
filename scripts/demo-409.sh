#!/usr/bin/env bash
#
# scripts/demo-409.sh: shows the race for the last seat against a running BranchBook. It finds a Polokwane FICA slot
# with exactly one seat left, then sends twenty bookings for it at the same moment, each from a different synthetic
# demo number. Exactly one must be booked and nineteen refused with 409; the script exits 1 on any other split.
#
# Usage: scripts/demo-409.sh [base-url]        default: http://localhost:8080
#
# It books one real appointment each run, so use it on a local stack (`make up`, then `make demo-409`), never on a
# shared instance. It can run again on the same database: it picks the next slot with one seat left. It needs only
# bash, curl, grep and date (GNU or BSD).

set -euo pipefail

base_url="${1:-http://localhost:8080}"
racers=20
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Prints the day before a YYYY-MM-DD date, with GNU or BSD date.
previous_day() {
  date -d "$1 - 1 day" +%F 2>/dev/null || date -j -v-1d -f %F "$1" +%F
}

# Prints a new UUID for an Idempotency-Key.
new_key() {
  uuidgen 2>/dev/null | tr 'A-Z' 'a-z' || cat /proc/sys/kernel/random/uuid
}

# Prints the first slot start on the given date with exactly one seat left, or nothing.
one_seat_left() {
  curl --silent --fail "$base_url/api/v1/branches/POL/availability?service=FICA&date=$1" \
    | grep --only-matching -E '"startsAt":"[^"]+","endsAt":"[^"]+","seatsAvailable":1' \
    | head -n 1 | cut -d'"' -f4
}

bookable_from="$(curl --silent --fail "$base_url/api/v1/branches/POL" | grep -oE '"bookableFrom":"[0-9-]+"' | cut -d'"' -f4)"
date="$(curl --silent --fail "$base_url/api/v1/branches/POL" | grep -oE '"bookableTo":"[0-9-]+"' | cut -d'"' -f4)"
starts_at=""
while [[ -z "$starts_at" ]]; do
  starts_at="$(one_seat_left "$date" || true)"
  if [[ -z "$starts_at" ]]; then
    [[ "$date" == "$bookable_from" ]] && { echo "No Polokwane FICA slot with one seat left in the window"; exit 1; }
    date="$(previous_day "$date")"
  fi
done
echo "Racing $racers bookings for Polokwane FICA at $starts_at"

# Twenty distinct demo numbers from 082 555 0100 to 0199, starting at a random one.
offset=$((RANDOM % 100))
for ((racer = 0; racer < racers; racer++)); do
  suffix="$(printf '%02d' $(((offset + racer) % 100)))"
  body="{\"branchCode\":\"POL\",\"serviceCode\":\"FICA\",\"startsAt\":\"$starts_at\","
  body+="\"customer\":{\"firstName\":\"Demo\",\"lastName\":\"Racer\",\"mobile\":\"082 555 01$suffix\"}}"
  curl --silent --output /dev/null --write-out '%{http_code}\n' --request POST \
    --header 'Content-Type: application/json' --header "Idempotency-Key: $(new_key)" \
    --data "$body" "$base_url/api/v1/appointments" > "$work/$racer" &
done
wait

booked="$(cat "$work"/* | grep -c '^201$' || true)"
refused="$(cat "$work"/* | grep -c '^409$' || true)"
echo "Booked: $booked, refused with 409: $refused, other: $((racers - booked - refused))"
if [[ "$booked" -eq 1 && "$refused" -eq $((racers - 1)) ]]; then
  echo 'Exactly one booking won the seat'
else
  echo 'Unexpected split'
  exit 1
fi
