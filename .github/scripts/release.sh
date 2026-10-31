#!/usr/bin/env bash
#
# .github/scripts/release.sh: reads and checks an environment's release record, the two SSM parameters that the
# release stack (BranchbookRelease for production, BranchbookStagingRelease for staging) writes once a deployment has
# passed its smoke test: /<prefix>/release holds the git commit and /<prefix>/release-image the image digest.
# <prefix> is the environment's resourcePrefix in infra/config/<environment>.json, for example branchbook-staging.
#
# Usage:
#   release.sh read <parameter>                    prints the parameter's value, or nothing if it does not exist yet
#   release.sh is-newer <prefix> <commit>          prints true if <commit> is the recorded commit or a descendant of
#                                                  it, or if nothing usable is recorded; false if the environment
#                                                  already runs a commit that <commit> does not contain
#   release.sh verify <prefix> <commit> <digest>   exits 1 unless the record names exactly this commit and image
#
# ci.yml's delivery jobs call it, after signing in to AWS. It needs the AWS CLI and, for is-newer, the full git
# history. Any AWS error other than a missing parameter fails the command, so a permission problem is never mistaken
# for an empty record. Messages go to stderr; only the answer goes to stdout.

set -euo pipefail

errors="$(mktemp)"
trap 'rm -f "$errors"' EXIT

# Prints an SSM parameter's value, or nothing when the parameter does not exist.
read_parameter() {
  local value
  if value="$(aws ssm get-parameter --name "$1" --query Parameter.Value --output text 2>"$errors")"; then
    printf '%s\n' "$value"
  elif grep --quiet ParameterNotFound "$errors"; then
    return 0
  else
    cat "$errors" >&2
    return 1
  fi
}

# Answers whether deploying <commit> moves the environment forward. Deployments queue per environment, and a slower
# run can reach the queue after a newer one, so without this check an older commit could replace a newer release.
is_newer() {
  local prefix="$1" commit="$2" recorded
  recorded="$(read_parameter "/$prefix/release")"
  if [[ -z "$recorded" ]]; then
    echo "Nothing is recorded in /$prefix/release yet." >&2
    echo true
  elif ! git cat-file -e "$recorded^{commit}" 2>/dev/null; then
    # For example "local", which a hand deployment records: there is no history to compare with.
    echo "/$prefix/release holds '$recorded', which is not a commit of this repository." >&2
    echo true
  elif git merge-base --is-ancestor "$recorded" "$commit"; then
    echo "/$prefix/release holds $recorded, which $commit contains." >&2
    echo true
  else
    echo "/$prefix/release holds $recorded, which $commit does not contain: a newer commit is already live." >&2
    echo false
  fi
}

# Fails unless the release record names exactly this commit and image digest.
verify() {
  local prefix="$1" commit="$2" digest="$3" recorded_commit recorded_digest
  recorded_commit="$(read_parameter "/$prefix/release")"
  recorded_digest="$(read_parameter "/$prefix/release-image")"
  if [[ "$recorded_commit" != "$commit" || "$recorded_digest" != "$digest" ]]; then
    echo "AWS records commit '$recorded_commit' and image '$recorded_digest'; expected $commit and $digest." >&2
    return 1
  fi
  echo "AWS ${AWS_REGION:-} now records commit $commit and image $digest in /$prefix." >&2
}

case "${1:-}" in
  read) read_parameter "$2" ;;
  is-newer) is_newer "$2" "$3" ;;
  verify) verify "$2" "$3" "$4" ;;
  *)
    echo "Usage: $0 read <parameter> | is-newer <prefix> <commit> | verify <prefix> <commit> <digest>" >&2
    exit 2
    ;;
esac
