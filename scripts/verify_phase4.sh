#!/usr/bin/env bash
# Checks every Phase 4 "done when" item (docs/POC_Implementation_Plan.md): recall@30 above 90%,
# whole confusable clusters via sibling expansion, Roman Urdu reported on its own. Also checks
# that the running AI layer keeps its index in step with the backend.
#
#   make db-up && make embeddings-up
#   make backend-run      # terminal 1
#   make ai-run           # terminal 2
#   make verify-phase4    # terminal 3 (the first run embeds ~750 eval texts: several minutes)
#
# The live checks change one capability-index row's version in the AI layer's own schema and
# wait for sync to repair it. Nothing in the school's data is touched.
#
# Exit code is the number of failed checks (0 = Phase 4 is done).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

AI_URL="http://${AI_LAYER_HOST:-127.0.0.1}:${AI_LAYER_PORT:-8081}"
export AI_URL
LOG_DIR="$(mktemp -d)"
REPORT="ai-layer/eval/reports/retrieval.md"

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

printf '\033[1mPhase 4: Retrieval\033[0m\n'

# --- Live sync and retrieval ----------------------------------------------------------------------------
section "Metadata sync and retrieval in the running AI layer ($AI_URL)"

cat > "$LOG_DIR/live_checks.py" <<'PY'
import asyncio
import json
import os
import time
import urllib.request

from app.core.settings import Settings
from app.embeddings.client import EmbeddingsClient
from app.gateway.client import GatewayClient
from app.index.database import IndexDatabase
from app.retrieval.hybrid import HybridRetriever

CLUSTER = {"fee.cancellation.raise", "fee.credit.raise", "fee.latefee.waive", "fee.writeoff.propose"}


def report(ok, message, detail=""):
    print(f"{'PASS' if ok else 'FAIL'}|{message}|{detail}")


def ready():
    with urllib.request.urlopen(os.environ["AI_URL"] + "/health/ready", timeout=10) as response:
        return json.load(response)


async def main():
    settings = Settings()
    body = ready()
    sync_check = body["checks"].get("metadata_sync", {})
    report(
        body["status"] == "ready" and sync_check.get("ok") is True,
        "the AI layer is ready and says metadata sync has filled the index",
        sync_check.get("detail", "no metadata_sync check"),
    )

    gateway = GatewayClient.from_settings(settings)
    index = await IndexDatabase.connect(settings)
    embeddings = EmbeddingsClient.from_settings(settings)
    try:
        versions = {v.id: v.version for v in (await gateway.get_versions()).versions}
        indexed = {k: v.version for k, v in (await index.indexed_versions()).items()}
        report(indexed == versions, f"the index holds exactly the backend's {len(versions)} capabilities at their current versions",
               f"backend {sorted(versions.items())} index {sorted(indexed.items())}")

        async with index._pool.acquire() as connection:
            await connection.execute(
                "UPDATE capability_index SET version = 'stale' WHERE capability_id = 'fee.overdue.list'")
        interval = settings.metadata_sync_interval_seconds
        deadline = time.monotonic() + interval * 2 + 30
        repaired = None
        while time.monotonic() < deadline:
            repaired = (await index.indexed_versions())["fee.overdue.list"].version
            if repaired == versions["fee.overdue.list"]:
                break
            await asyncio.sleep(2)
        report(repaired == versions["fee.overdue.list"],
               f"a row whose version no longer matches the backend is re-embedded within one poll ({interval:g}s)",
               f"still {repaired}")

        allowed = list(versions)
        retriever = HybridRetriever(index, embeddings, cap=settings.retrieval_candidate_cap)
        result = await retriever.retrieve(["the family already paid a charge that was wrong"], allowed)
        offered = set(result.capability_ids)
        report(CLUSTER <= offered and result.candidates[0].capability_id in CLUSTER,
               "a sentence about one confusable correction retrieves all four, via siblings",
               str(result.capability_ids))
        pulled_in = [c.capability_id for c in result.candidates if c.sibling_of]
        report(bool(pulled_in), "sibling expansion is visible on the candidates (sibling_of is set)", str(pulled_in))

        narrow = await retriever.retrieve(["send fee reminders on WhatsApp"], ["fee.reminder.send", "dashboard.main.read"])
        report(set(narrow.capability_ids) <= {"fee.reminder.send", "dashboard.main.read"}
               and narrow.capability_ids[0] == "fee.reminder.send",
               "retrieval never offers a capability outside the allow-list, not even a declared sibling",
               str(narrow.capability_ids))
    finally:
        await embeddings.aclose()
        await index.close()
        await gateway.aclose()


asyncio.run(main())
PY

report_lines live bash -c 'cd ai-layer && PYTHONPATH=. uv run python "$0"' "$LOG_DIR/live_checks.py"

# --- The eval ---------------------------------------------------------------------------------------------
section "Retrieval eval (175 labelled sentences, POC index and a 489-capability stress index)"

run_check "the eval set is sound: 150-200 sentences, Roman Urdu glossed, every cluster member covered" eval-dataset \
  bash -c 'cd ai-layer && uv run pytest -q -p no:cacheprovider tests/unit/test_eval_dataset.py'

run_check "recall@30 > 90% on the POC index and among distractors, whole clusters, Roman Urdu reported (make ai-eval)" eval \
  bash -c 'cd ai-layer && uv run pytest -q -p no:cacheprovider eval'

if [[ -f "$REPORT" ]]; then
  printf '\n  From %s:\n' "$REPORT"
  awk '/^## /{name=$0} /^\| (in English|all sentences|English|Roman Urdu)/{sub(/^## /,"",name); split($0,c,"|"); printf "    %-44s %-32s recall@30 %s\n", substr(name,1,44), c[2], c[4]}' "$REPORT" | sed 's/\*\*//g'
  grep '^Confusable clusters' "$REPORT" | sed 's/^/    /'
fi

section "Sync, fusion, siblings and index queries (unit and integration tests)"

run_check "sync re-embeds only what changed, deletes what was withdrawn, survives an outage; fusion and siblings" unit \
  bash -c 'cd ai-layer && uv run pytest -q -p no:cacheprovider tests/unit/test_metadata_sync.py tests/unit/test_retrieval.py tests/unit/test_health_api.py'

run_check "real Postgres: migrations 0001-0002, dense and lexical rankings, siblings, startup sync before ready" integration \
  bash -c 'cd ai-layer && uv run pytest -q -p no:cacheprovider tests/integration/test_index_migrations.py tests/integration/test_capability_index_queries.py tests/integration/test_startup_and_readiness.py'

echo
if [[ $failures -eq 0 ]]; then
  printf '\033[32mPhase 4 is done: every check passed.\033[0m\n'
else
  printf '\033[31m%d check(s) failed.\033[0m Logs in %s\n' "$failures" "$LOG_DIR"
fi
exit "$failures"
