#!/usr/bin/env bash
# Checks the Phase 7 "done when" items (docs/POC_Implementation_Plan.md): one command prints the four
# numbers, and a deliberately broken description shows up as a recall drop. It runs the CI
# regression run itself, from the recorded model answers, with no Claude CLI reachable.
#
#   make db-up && make embeddings-up
#   make measure          # once, to record any missing model answer (uses the Claude subscription)
#   make verify-phase7
#
# Needs Docker and the embeddings service; the backend and the AI layer need not run.
# Exit code is the number of failed checks (0 = Phase 7 is done).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
LOG_DIR="$(mktemp -d)"

failures=0
pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() {
  printf '  \033[31m✗\033[0m %s\n' "$1"
  [[ -n "${2:-}" ]] && printf '      %s\n' "$2"
  failures=$((failures + 1))
}
section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

run_check() {
  local description="$1" log="$LOG_DIR/$2.log"
  shift 2
  if "$@" >"$log" 2>&1; then
    pass "$description"
  else
    fail "$description" "see $log"
    tail -25 "$log" | sed 's/^/      | /'
  fi
}

printf '\033[1mPhase 7: Measure\033[0m\n'

section "The regression run: make measure-ci (recorded answers only; the Claude CLI path points nowhere)"

CI_LOG="$LOG_DIR/measure-ci.log"
if make measure-ci >"$CI_LOG" 2>&1; then
  pass "make measure-ci passes: every eval, the four numbers against the baseline, the fault injection, the broken description"
else
  fail "make measure-ci failed" "see $CI_LOG"
  tail -30 "$CI_LOG" | sed 's/^/      | /'
fi

missing=()
for name in "recall@30" "plan accuracy" "refusal correctness" "validator catch rate"; do
  grep -q "^$name " "$CI_LOG" || missing+=("$name")
done
if [[ ${#missing[@]} -eq 0 ]] && grep -q "Roman Urdu" "$CI_LOG"; then
  pass "one command printed the four numbers, for all sentences, English and Roman Urdu:"
  awk '/^ +all +English +Roman Urdu/{show=1} show{print "      " $0} /^out of:/{show=0}' "$CI_LOG" | head -6
else
  fail "the four numbers were not all printed" "missing: ${missing[*]:-Roman Urdu column}"
fi

if grep -q "^Broken description for" "$CI_LOG"; then
  pass "a deliberately broken description shows up as a recall drop:"
  grep "^Broken description for" "$CI_LOG" | sed 's/^/      /'
else
  fail "the broken-description check printed nothing" "see $CI_LOG"
fi

if grep -q "ModelUnavailableError\|claude-code" "$CI_LOG"; then
  fail "the regression run tried to reach a model" "see $CI_LOG"
else
  pass "no model was called: every answer came from ai-layer/eval/recordings/"
fi

section "The measurement's own logic, and CI"

run_check "recordings replay without a model and refuse a changed prompt; every fault is caught for its own reason; the numbers count what they say" unit \
  bash -c 'cd ai-layer && uv run pytest -q -p no:cacheprovider tests/unit/test_measure_logic.py'

if grep -q "make measure-ci" .github/workflows/ai-layer.yml && grep -q "make backend-verify" .github/workflows/ai-layer.yml; then
  pass "the CI workflow runs the backend tests, the AI layer tests and make measure-ci (.github/workflows/ai-layer.yml)"
else
  fail "the CI workflow does not run the regression" ".github/workflows/ai-layer.yml"
fi

echo
if [[ $failures -eq 0 ]]; then
  printf '\033[32mPhase 7 is done: every check passed.\033[0m\n'
else
  printf '\033[31m%d check(s) failed.\033[0m Logs in %s\n' "$failures" "$LOG_DIR"
fi
exit "$failures"
