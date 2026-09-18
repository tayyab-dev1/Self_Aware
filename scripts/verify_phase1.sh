#!/usr/bin/env bash
# Checks every Phase 1 "done when" item (docs/POC_Implementation_Plan.md) against the
# running stack, and runs the tests that prove a broken registry rule fails the build.
#
#   make db-up
#   make embeddings-up
#   make backend-run      # terminal 1
#   make verify-phase1    # terminal 2 (takes a few minutes: it runs Maven and pytest)
#
# Exit code is the number of failed checks (0 = Phase 1 is done).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

export BACKEND_URL="http://${BACKEND_HOST:-127.0.0.1}:${BACKEND_PORT:-8080}"
export DEV_TOKEN="${BACKEND_DEV_USER_TOKEN:-}"
export EXPECTED_IDS="dashboard.main.read fee.cancellation.raise fee.credit.raise fee.latefee.waive fee.overdue.list fee.payment.record fee.reminder.send fee.writeoff.propose"
LOG_DIR="$(mktemp -d)"

failures=0
pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() {
  printf '  \033[31m✗\033[0m %s\n' "$1"
  [[ -n "${2:-}" ]] && printf '      %s\n' "$2"
  failures=$((failures + 1))
}
section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# Runs a command quietly; on failure shows the end of its output.
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

printf '\033[1mPhase 1: Registry and metadata\033[0m\n'

# --- Live endpoints -----------------------------------------------------------
section "Gateway endpoints ($BACKEND_URL)"

cat > "$LOG_DIR/endpoint_checks.py" <<'PY'
import hashlib
import json
import os
import urllib.error
import urllib.request

base = os.environ["BACKEND_URL"]
token = os.environ["DEV_TOKEN"]
expected = os.environ["EXPECTED_IDS"].split()


def get(path, bearer=None):
    headers = {"Authorization": "Bearer " + bearer} if bearer else {}
    try:
        with urllib.request.urlopen(urllib.request.Request(base + path, headers=headers), timeout=10) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"{}")


def report(ok, message, detail=""):
    print(("PASS" if ok else "FAIL") + "|" + message + "|" + str(detail).replace("|", "/"))


def canonical(entry):
    fields = {key: value for key, value in entry.items() if key != "version"}
    return json.dumps(fields, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


status, metadata = get("/agent/metadata")
capabilities = metadata.get("capabilities", [])
ids = [capability["id"] for capability in capabilities]
report(status == 200 and ids == expected,
       "GET /agent/metadata lists the %d POC capabilities" % len(expected), "got %s" % ids)

required = ["id", "version", "module", "read_only", "blast_radius", "description",
            "disambiguate_from", "params", "preconditions", "effect"]
gaps = ["%s.%s" % (c["id"], key) for c in capabilities for key in required if key not in c]
gaps += ["%s.params[%s]" % (c["id"], p.get("name")) for c in capabilities for p in c.get("params", [])
         if not all(key in p for key in ("name", "type", "required", "meaning"))]
gaps += ["%s: write without confirmation_template" % c["id"] for c in capabilities
         if not c.get("read_only") and not c.get("effect", {}).get("confirmation_template")]
report(not gaps, "every entry has full detail: params with types, preconditions, effect and templates", gaps)
report("/api/" not in json.dumps(metadata), "no entry publishes an endpoint address (invariant 2)")

with open("snapshots/agent-metadata.json", encoding="utf-8") as handle:
    report(json.load(handle) == metadata, "the committed metadata snapshot matches what the backend serves",
           "run: make contracts")

mismatched = [c["id"] for c in capabilities if hashlib.sha256(canonical(c)).hexdigest() != c["version"]]
report(capabilities and not mismatched,
       "each version is the SHA-256 of its entry (recomputed here, independently of the backend)", mismatched)

edited = json.loads(json.dumps(capabilities))
target = edited[len(edited) // 2]
target["description"] = target["description"][:-1] + ("!" if not target["description"].endswith("!") else ".")
changed = [c["id"] for c, e in zip(capabilities, edited) if hashlib.sha256(canonical(e)).hexdigest() != c["version"]]
report(changed == [target["id"]],
       "changing one character of one description changes only that entry's version", "changed: %s" % changed)

status, versions = get("/agent/metadata/versions")
pairs = [(v["id"], v["version"]) for v in versions.get("versions", [])]
report(status == 200 and pairs == [(c["id"], c["version"]) for c in capabilities],
       "GET /agent/metadata/versions returns the same ids and versions")

status, session = get("/agent/session/capabilities", token)
report(status == 200 and session.get("capability_ids") == expected,
       "GET /agent/session/capabilities gives the dev user every capability id",
       "status %s: %s (is BACKEND_DEV_USER_TOKEN set?)" % (status, session))

status, body = get("/agent/session/capabilities")
report(status == 401 and body.get("code") == "UNAUTHENTICATED",
       "GET /agent/session/capabilities refuses a request without a token (401 UNAUTHENTICATED)",
       "status %s: %s" % (status, body))
PY

if ! curl -fsS -m 5 "$BACKEND_URL/actuator/health" >/dev/null 2>&1; then
  fail "backend is not answering" "run: make backend-run"
elif ! python3 "$LOG_DIR/endpoint_checks.py" >"$LOG_DIR/endpoint_checks.out" 2>&1; then
  fail "the endpoint checks could not run" "see $LOG_DIR/endpoint_checks.out"
  tail -15 "$LOG_DIR/endpoint_checks.out" | sed 's/^/      | /'
elif ! grep -q '^PASS|\|^FAIL|' "$LOG_DIR/endpoint_checks.out"; then
  fail "the endpoint checks reported nothing" "see $LOG_DIR/endpoint_checks.out"
else
  while IFS='|' read -r verdict message detail; do
    if [[ "$verdict" == "PASS" ]]; then pass "$message"; elif [[ "$verdict" == "FAIL" ]]; then fail "$message" "$detail"; fi
  done <"$LOG_DIR/endpoint_checks.out"
fi

# --- The AI layer reads them through its generated models ----------------------
section "AI layer reaches the gateway through its generated models"

cat > "$LOG_DIR/ai_client_check.py" <<'PY'
import asyncio
import os

from app.core.settings import Settings
from app.gateway.client import GatewayClient


async def main() -> None:
    client = GatewayClient.from_settings(Settings(backend_base_url=os.environ["BACKEND_URL"]))
    try:
        metadata = await client.get_metadata()
        versions = await client.get_versions()
        allowed = await client.get_session_capabilities(os.environ["DEV_TOKEN"])
    finally:
        await client.aclose()
    counts = (len(metadata.capabilities), len(versions.versions), len(allowed.capability_ids))
    assert counts == (8, 8, 8), counts


asyncio.run(main())
PY

run_check "the AI layer's client parses /agent/metadata, /versions and /session/capabilities" ai-client \
  bash -c 'cd ai-layer && PYTHONPATH=. uv run python "$0"' "$LOG_DIR/ai_client_check.py"

# --- Build-time assertions ----------------------------------------------------
section "Build-time assertions (each proves a broken rule stops the build)"

run_check "gateway: a missing PreconditionCheck bean, a one-sided sibling, an unknown placeholder and a missing count each stop the registry" gateway-rules \
  bash -c 'cd backend && ./mvnw -B -ntp -q -pl agent-gateway test -Dtest=RegistryRulesTest,AgentGatewayAutoConfigurationTest,CapabilityRegistryBuilderTest'

run_check "real backend: refuses to start without a PreconditionCheck bean, or with a one-directional disambiguateFrom; ids match the planning contracts" backend-build-checks \
  bash -c 'cd backend && ./mvnw -B -ntp -q -pl school-app -am verify -Dtest=CapabilityIdsMatchPlanningContractsTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=RegistryBuildChecksIT -Dfailsafe.failIfNoSpecifiedTests=false'

run_check "embeddings: the service runs pinned BGE-M3, and no two descriptions embed above 0.92 unless declared siblings" description-similarity \
  bash -c 'cd ai-layer && uv run pytest -q tests/build_checks'

echo
if [[ $failures -eq 0 ]]; then
  printf '\033[32mPhase 1 is done: every check passed.\033[0m\n'
else
  printf '\033[31m%d check(s) failed.\033[0m Logs in %s\n' "$failures" "$LOG_DIR"
fi
exit "$failures"
