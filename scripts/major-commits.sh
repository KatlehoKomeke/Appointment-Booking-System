#!/usr/bin/env bash
#
# scripts/major-commits.sh: lists the major commits in a range, the ones after which the architecture page must be
# re-checked against the code (CONTRIBUTING.md, "The architecture page").
#
# A commit is major when it:
#   - is a `feat`, or a `fix` that changes code under api/src/main, web/src, infra/lib or infra/bin; or
#   - adds or changes a migration, an `api` package, infra/lib or infra/bin, the Dockerfile or compose.yaml, a GitHub
#     Actions workflow or an ADR.
# A dependency update (a `deps` scope, or a Dependabot "Bump ..." subject) is never major. Merge commits are skipped,
# because the commits they bring in are judged on their own.
#
# Usage, from anywhere in the repository:  scripts/major-commits.sh [<revision range>]
# The range defaults to HEAD^!, the last commit alone. It prints "<short sha> <subject>" for each major commit,
# oldest first, and exits 0 whether or not it finds one.

set -euo pipefail

range="${1:-HEAD^!}"

dependency_update='^([a-z]+\(deps[^)]*\)!?: |Bump )'
feature='^feat(\([^)]*\))?!?: '
fix='^fix(\([^)]*\))?!?: '
code_paths='^(api/src/main/|web/src/|infra/(lib|bin)/)'
design_paths='^(api/src/main/resources/db/migration/|api/src/main/java/.*/api/|infra/(lib|bin)/|Dockerfile$|compose\.yaml$|\.github/workflows/|docs/adr/)'

cd "$(git rev-parse --show-toplevel)"

for sha in $(git rev-list --reverse --no-merges "${range}"); do
  subject="$(git log -1 --format=%s "${sha}")"
  if [[ "${subject}" =~ ${dependency_update} ]]; then
    continue
  fi
  files="$(git diff-tree --no-commit-id --name-only -r "${sha}")"
  if [[ "${subject}" =~ ${feature} ]] \
    || { [[ "${subject}" =~ ${fix} ]] && grep -Eq "${code_paths}" <<<"${files}"; } \
    || grep -Eq "${design_paths}" <<<"${files}"; then
    printf '%s %s\n' "$(git rev-parse --short "${sha}")" "${subject}"
  fi
done
