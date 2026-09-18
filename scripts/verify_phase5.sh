#!/usr/bin/env bash
# Checks every Phase 5 "done when" item (docs/POC_Implementation_Plan.md) with the real models:
# a Roman Urdu sentence becomes a valid plan with English intents and names untouched, a sentence
# matching nothing is refused, a hallucinated capability id is caught, and a two-part sentence
# becomes two steps in order. The live plans also go through the backend's preflight, which
# writes nothing.
#
#   make db-up && make embeddings-up
#   make backend-run      # terminal 1
#   make ai-run           # terminal 2 (fills the capability index)
#   make verify-phase5    # terminal 3 (calls Haiku and Sonnet through the Claude CLI)
#
# Exit code is the number of failed checks (0 = Phase 5 is done).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi
export BACKEND_DEV_USER_TOKEN="${BACKEND_DEV_USER_TOKEN:-}"
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

report_lines() {
  local name="$1" out="$LOG_DIR/$1.out"
  shift
  if ! "$@" >"$out" 2>&1; then
    fail "the $name checks could not run" "see $out"
    tail -15 "$out" | sed 's/^/      | /'
  elif ! grep -q '^PASS|\|^FAIL|' "$out"; then
    fail "the $name checks reported nothing" "see $out"
  else
    while IFS='|' read -r verdict message detail; do
      if [[ "$verdict" == "PASS" ]]; then pass "$message"; elif [[ "$verdict" == "FAIL" ]]; then fail "$message" "$detail"; fi
    done < <(grep '^PASS|\|^FAIL|' "$out")
  fi
}

printf '\033[1mPhase 5: Decompose and plan\033[0m\n'

section "The whole pipeline on the running stack: decompose (Haiku) → retrieve → plan (Sonnet) → validate → preflight"

cat > "$LOG_DIR/live_checks.py" <<'PY'
import asyncio
import os
import uuid

from app.core.settings import Settings
from app.decompose.decomposer import Decomposer
from app.embeddings.client import EmbeddingsClient
from app.gateway.client import GatewayClient
from app.gateway.errors import GatewayRejectedError
from app.index.database import IndexDatabase
from app.llm.claude_cli import ClaudeCliModel
from app.planning.outcomes import PlannedSteps, Refusal
from app.planning.planner import Planner
from app.planning.service import SentencePlanner
from app.retrieval.hybrid import HybridRetriever
from app.validation.problems import InvalidModelOutputError, normalise
from domain.school.calendar import SCHOOL_TIME_ZONE, school_today
from domain.school.glossary import RECORD_WORDS, glossary_lines

URDU_WORDS = {"ke", "ko", "ki", "ka", "par", "bhejo", "karo", "aur", "dikhao", "mili", "hai"}


def report(ok, message, detail=""):
    print(f"{'PASS' if ok else 'FAIL'}|{message}|{str(detail)[:300]}")


class InjectedPlanner:
    """Answers decompose for real, then returns a plan naming a capability that does not exist."""

    def __init__(self, real):
        self.real = real

    async def generate(self, request):
        if request.purpose == "decompose":
            return await self.real.generate(request)
        return {"outcome": "plan", "steps": [{"capability_id": "fee.defaulters.expel",
                "params": [{"name": "section_id", "words": "class 5 blue"}]}]}


async def main():
    settings = Settings()
    token = os.environ["BACKEND_DEV_USER_TOKEN"]
    model = ClaudeCliModel.from_settings(settings)
    gateway = GatewayClient.from_settings(settings)
    index = await IndexDatabase.connect(settings)
    embeddings = EmbeddingsClient.from_settings(settings)
    try:
        catalog = {c.id: c for c in (await gateway.get_metadata()).capabilities}
        allowed = (await gateway.get_session_capabilities(token)).capability_ids
        retriever = HybridRetriever(index, embeddings, cap=settings.retrieval_candidate_cap)

        def pipeline(runner):
            return SentencePlanner(
                Decomposer(runner, glossary_lines()),
                retriever,
                Planner(runner, max_steps=settings.plan_max_steps, today=school_today, time_zone=SCHOOL_TIME_ZONE,
                        record_words=RECORD_WORDS),
                lambda: catalog,
            )

        async def understand(runner, sentence):
            return await pipeline(runner).understand(sentence, allowed=allowed, session_id=f"verify-{uuid.uuid4().hex[:8]}")

        # 1. Roman Urdu
        sentence = "class 5 blue ke defaulters ko whatsapp par reminder bhejo"
        u = await understand(model, sentence)
        intents = [i.text for i in u.decomposition.intents]
        entities = [e for i in u.decomposition.intents for e in i.entities]
        english = all(not URDU_WORDS & set(normalise(t).replace(",", " ").split()) for t in intents)
        report(english and entities and all(normalise(e) in normalise(sentence) for e in entities),
               "Roman Urdu: decompose wrote English intents and copied the names untouched",
               f"intents {intents} entities {entities}")
        ok = isinstance(u.outcome, PlannedSteps) and [s.capability_id for s in u.outcome.plan.steps] == ["fee.reminder.send"]
        report(ok, "Roman Urdu: the plan is fee.reminder.send for the user's own words, versions stamped",
               u.outcome)
        if ok:
            step = u.outcome.plan.steps[0]
            report(normalise(step.params["section_id"].raw) == "class 5 blue"
                   and step.capability_version == catalog["fee.reminder.send"].version,
                   'Roman Urdu: section_id carries "class 5 blue" as typed, and the current version', step)
            try:
                confirmed = await gateway.preflight(u.outcome.plan, token)
                report("5 guardians in Class 5 Blue" in (confirmed.confirmation or ""),
                       f"Roman Urdu: the backend's preflight accepts the plan: \"{confirmed.confirmation}\"")
            except GatewayRejectedError as rejected:
                report(False, "Roman Urdu: the backend's preflight accepts the plan", rejected.code)

        # 2. Nothing matches
        u = await understand(model, "kal Lahore mein mausam kaisa hoga?")
        report(isinstance(u.outcome, Refusal), f"a sentence matching nothing is refused ({getattr(u.outcome, 'reason', u.outcome)}), not guessed", u.outcome)

        # 3. Hallucinated capability id in a mocked model response
        try:
            u = await understand(InjectedPlanner(model), sentence)
            report(False, "a hallucinated capability id in a mocked planner answer is caught by the validator", u.outcome)
        except InvalidModelOutputError as caught:
            report(caught.codes == ["UNKNOWN_CAPABILITY"],
                   "a hallucinated capability id in a mocked planner answer is caught by the validator (UNKNOWN_CAPABILITY)",
                   caught.codes)

        # 4. Two parts
        sentence = "Ahmed Raza ki September ki fees 5000 cash aaj mili hai, record karo, phir class 5 blue ka baqaya dikhao"
        u = await understand(model, sentence)
        steps = [s.capability_id for s in u.outcome.plan.steps] if isinstance(u.outcome, PlannedSteps) else u.outcome
        report(steps == ["fee.payment.record", "fee.overdue.list"],
               "a two-part sentence becomes two steps in the right order: fee.payment.record, then fee.overdue.list", steps)
        if isinstance(u.outcome, PlannedSteps) and len(u.outcome.plan.steps) == 2:
            try:
                confirmed = await gateway.preflight(u.outcome.plan, token)
                report("PKR 5,000" in (confirmed.confirmation or ""),
                       f"two parts: preflight accepts both steps: \"{confirmed.confirmation}\"")
            except GatewayRejectedError as rejected:
                report(False, "two parts: preflight accepts both steps", rejected.code)
    finally:
        await embeddings.aclose()
        await index.close()
        await gateway.aclose()


asyncio.run(main())
PY

report_lines live bash -c 'cd ai-layer && PYTHONPATH=. uv run python "$0"' "$LOG_DIR/live_checks.py"

section "The same four, as tests (validator rules with scripted models; the real models without retrieval)"

run_check "validator: unknown, disallowed and non-candidate ids, invented and missing parameters, forms, types, quoted names and amounts, steps" unit \
  bash -c 'cd ai-layer && uv run pytest -q -p no:cacheprovider tests/unit/test_plan_validator.py tests/unit/test_decomposer.py tests/unit/test_planning_service.py tests/unit/test_claude_cli.py'

run_check "real Haiku and Sonnet: Roman Urdu plan, refusal, two steps in order (make ai-model-checks)" models \
  bash -c 'cd ai-layer && uv run pytest -q -p no:cacheprovider tests/model_checks'

section "Retrieval with real intents (the Phase 4 follow-up)"

run_check "recall@30 > 90% on the 489-capability stress index when searching with Haiku's intents; clusters whole (make ai-eval)" eval-intents \
  bash -c 'cd ai-layer && uv run pytest -q -p no:cacheprovider eval'

REPORT="ai-layer/eval/reports/retrieval_with_intents.md"
if [[ -f "$REPORT" ]]; then
  printf '\n  From %s:\n' "$REPORT"
  grep '^Decompose answered' "$REPORT" | sed 's/^/    /'
  awk '/^\| (all sentences|English|Roman Urdu), through intents/{split($0,c,"|"); printf "    %-34s recall@30 %s\n", c[2], c[4]}' "$REPORT" | sed 's/\*\*//g'
fi

echo
if [[ $failures -eq 0 ]]; then
  printf '\033[32mPhase 5 is done: every check passed.\033[0m\n'
else
  printf '\033[31m%d check(s) failed.\033[0m Logs in %s\n' "$failures" "$LOG_DIR"
fi
exit "$failures"
