#!/usr/bin/env bash
#
# .github/scripts/read-config.sh: prints settings from one of the CDK app's configuration files (infra/config/*.json)
# as NAME=value lines, ready to append to $GITHUB_ENV or $GITHUB_OUTPUT. ci.yml's delivery jobs use it so that an
# environment's address, resource prefix, stack names and the image repository come from the configuration the CDK
# app itself reads, never from literals in a workflow.
#
# Usage: read-config.sh <config.json> NAME=dotted.path [NAME=dotted.path ...]
#   e.g. read-config.sh infra/config/staging.json PREFIX=resourcePrefix DOMAIN=domain.name
#
# It needs Node.js. Each value must be a non-empty string of letters, digits and . _ / -, so nothing read from a file
# can add a line of its own to $GITHUB_ENV; anything else fails the step.

set -euo pipefail

config="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
shift

for pair in "$@"; do
  name="${pair%%=*}"
  path="${pair#*=}"
  value="$(node -p '
    const [file, path] = process.argv.slice(1);
    const value = path.split(".").reduce((node, key) => node?.[key], require(file));
    if (typeof value !== "string" || !/^[A-Za-z0-9._\/-]+$/.test(value)) {
      throw new Error(`${file}: ${path} must be a non-empty name, got ${JSON.stringify(value)}`);
    }
    value' "$config" "$path")"
  printf '%s=%s\n' "$name" "$value"
done
