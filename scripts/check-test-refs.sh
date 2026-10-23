#!/usr/bin/env bash
#
# scripts/check-test-refs.sh: keeps the documentation honest about what is proven. Every test that
# docs/domain-rules.md or README.md cites as proof must exist.
#
# Every rule (a "## BR-n" heading) in docs/domain-rules.md must have exactly one status line and one
# "Proven by" paragraph that cites at least one test:
#   **Status.** Proven.            every test the rule cites must exist
#   **Status.** Planned for M3.    the rule's tests may not be written yet
# A test cited in README.md must exist, unless a planned rule cites it or the README table cell that
# cites it says "planned".
#
# A test is cited as `ClassName.methodName`, a test method (or ArchUnit rule) of a top-level class in
# api/src/test/java, or as a web test file name ending in .test.ts, .test.tsx or .spec.ts. Only
# methods annotated as tests count, so citing a helper method fails.
#
# Usage, from anywhere in the repository:  scripts/check-test-refs.sh
# It prints every problem it finds and exits non-zero if there is one.

set -euo pipefail
cd "$(dirname "$0")/.."

rules_file=docs/domain-rules.md
readme=README.md
# [.] rather than \. so the pattern means the same in grep and in every awk.
export CITATION='[A-Z][A-Za-z0-9]*(Test|IT)[.][a-zA-Z0-9_]+|[a-z0-9.-]+[.](test[.]tsx?|spec[.]ts)'

# Prints "Class.member" for every test of every Java test class, and the name of every web test file.
# A member counts only if the declaration that directly follows its annotations is annotated as a test and not
# @Disabled. Annotation arguments may span lines; any other line in between ends the search.
existing_tests() {
  # The $ expressions belong to awk, so the program is single-quoted on purpose.
  # shellcheck disable=SC2016
  find api/src/test/java -name '*.java' -print0 | xargs -0 awk '
    function parens(text,   opened, closed) {
      opened = gsub(/[(]/, "(", text); closed = gsub(/[)]/, ")", text); return opened - closed
    }
    FNR == 1 { class = FILENAME; sub(/.*\//, "", class); sub(/[.]java$/, "", class); test = 0; disabled = 0; depth = 0 }
    depth > 0 { depth += parens($0); next }
    /^[[:space:]]*@/ {
      if ($0 ~ /^[[:space:]]*@(Test|ParameterizedTest|RepeatedTest|TestFactory|ArchTest)([[:space:](]|$)/) test = 1
      if ($0 ~ /^[[:space:]]*@Disabled([[:space:](]|$)/) disabled = 1
      depth = parens($0)
      next
    }
    test {
      # Modifiers, optional type parameters and a type, then the name, then "(" (a method) or "=" (an ArchUnit rule).
      if ($0 ~ /^[[:space:]]*([a-z]+[[:space:]]+)*(<[^>]*>[[:space:]]+)?[A-Za-z0-9_<>,.?]+[[:space:]]+[a-zA-Z0-9_]+[[:space:]]*[(=]/ && !disabled) {
        match($0, /[a-zA-Z0-9_]+[[:space:]]*[(=]/)
        name = substr($0, RSTART, RLENGTH); sub(/[[:space:]]*[(=]$/, "", name)
        print class "." name
      }
    }
    { test = 0; disabled = 0 }
  '
  find web -path web/node_modules -prune -o \( -name '*.test.ts' -o -name '*.test.tsx' -o -name '*.spec.ts' \) \
    -type f -exec basename {} \;
}

# Reads docs/domain-rules.md and prints one line per finding:
#   CITE <rule> <proven|planned> <test>   a test the rule cites
#   PROBLEM <message>                     the rule is malformed
rule_citations() {
  awk '
    function flush(   line, token, cited) {
      if (rule == "") return
      if (statuses != 1) print "PROBLEM " rule " needs exactly one **Status.** line, found " statuses
      if (status == "unknown") print "PROBLEM " rule " has a status that is neither Proven nor Planned"
      if (proofs_found != 1) print "PROBLEM " rule " needs exactly one **Proven by.** paragraph, found " proofs_found
      line = proofs; cited = 0
      while (match(line, /`[^`]+`/)) {
        token = substr(line, RSTART + 1, RLENGTH - 2)
        line = substr(line, RSTART + RLENGTH)
        if (token ~ ("^(" ENVIRON["CITATION"] ")$")) { print "CITE " rule " " status " " token; cited++ }
        else print "PROBLEM " rule " cites `" token "`, which is not Class.method or a web test file"
      }
      if (proofs_found == 1 && cited == 0) print "PROBLEM " rule " cites no test"
    }
    /^## BR-/ { flush(); rule = $2; sub(/:$/, "", rule); statuses = 0; status = ""; proofs = ""; proofs_found = 0; in_proofs = 0; next }
    in_proofs && NF == 0 { in_proofs = 0 }
    in_proofs { proofs = proofs " " $0; next }
    /^\*\*Proven by\.\*\*/ { proofs_found++; in_proofs = 1; proofs = $0; next }
    /^\*\*Status\.\*\*/ {
      statuses++
      if ($0 ~ /^\*\*Status\.\*\* Proven/) status = "proven"
      else if ($0 ~ /^\*\*Status\.\*\* Planned/) status = "planned"
      else status = "unknown"
    }
    END { flush() }
  ' "$rules_file"
}

# Prints "<test> <planned|unmarked>" for every test README.md cites; "planned" means the citation is in a table row
# and the rest of its cell contains the word "planned".
readme_citations() {
  awk '
    {
      line = $0
      while (match(line, /`[^`]+`/)) {
        token = substr(line, RSTART + 1, RLENGTH - 2)
        line = substr(line, RSTART + RLENGTH)
        if (token !~ ("^(" ENVIRON["CITATION"] ")$")) continue
        cell = line; sub(/[|].*/, "", cell)
        planned = ($0 ~ /^[|]/) && (tolower(cell) ~ /(^|[^a-z])planned([^a-z]|$)/)
        print token, (planned ? "planned" : "unmarked")
      }
    }
  ' "$readme"
}

existing="$(existing_tests | sort -u)"
findings="$(rule_citations)"
citations="$(grep '^CITE ' <<<"$findings" || true)"
failures=0

exists() {
  grep -qxF -- "$1" <<<"$existing"
}

while read -r _ message; do
  [[ -z "$message" ]] && continue
  echo "$rules_file: $message"
  failures=$((failures + 1))
done < <(grep '^PROBLEM ' <<<"$findings" || true)

while read -r _ rule state test; do
  [[ -z "$test" ]] && continue
  if [[ "$state" == proven ]] && ! exists "$test"; then
    echo "$rules_file: $rule is marked Proven but cites $test, which is not a test that exists"
    failures=$((failures + 1))
  fi
done <<<"$citations"

while read -r test marker; do
  [[ -z "$test" ]] && continue
  if ! exists "$test" && [[ "$marker" != planned ]] \
    && ! awk -v t="$test" '$3 == "planned" && $4 == t { found = 1 } END { exit !found }' <<<"$citations"; then
    echo "$readme: cites $test, which does not exist, and neither a planned rule nor its table cell says it is planned"
    failures=$((failures + 1))
  fi
done < <(readme_citations)

if ((failures > 0)); then
  echo "$failures problem(s) found"
  exit 1
fi
rules="$(grep -c '^## BR-' "$rules_file")"
proven="$(awk '$3 == "proven" {print $4}' <<<"$citations" | sort -u | wc -l | tr -d ' ')"
planned="$(awk '$3 == "planned" {print $4}' <<<"$citations" | sort -u | wc -l | tr -d ' ')"
echo "All ${rules} rules are well formed. ${proven} cited tests prove rules and all exist; ${planned} belong to planned rules."
