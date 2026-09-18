#!/usr/bin/env bash
# Checks every Phase 2 "done when" item (docs/POC_Implementation_Plan.md) against the
# running stack, and runs the tests that prove what cannot be seen from outside: a
# tampered token failing verification, and another branch's records staying invisible.
#
#   make db-up
#   make backend-run      # terminal 1
#   make verify-phase2    # terminal 2 (takes a few minutes: it runs Maven)
#
# Exit code is the number of failed checks (0 = Phase 2 is done).
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
DB="${POSTGRES_DB:-sms}"
BACKEND_ROLE="${BACKEND_DB_USER:-sms_backend}"
COMPOSE=(docker compose --project-directory docker -f docker/docker-compose.yml)
[[ -f .env ]] && COMPOSE+=(--env-file .env)
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

# Reads PASS|message|detail lines from a check script; a script that cannot run, or reports nothing, fails.
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

printf '\033[1mPhase 2: Preflight\033[0m\n'

# --- Independent counts, straight from the tables --------------------------------------------------
# Guardians of Class 5 Blue with an overdue invoice who have a contact for each channel. Written here
# from the tables, not through the backend's repository, so it checks the backend's answer.
count_sql() {
  "${COMPOSE[@]}" exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U "$BACKEND_ROLE" -d "$DB" -tA -c "
    SELECT count(DISTINCT g.id) FILTER (WHERE g.whatsapp_number IS NOT NULL) || ' '
        || count(DISTINCT g.id) FILTER (WHERE g.mobile_number IS NOT NULL) || ' '
        || count(DISTINCT g.id) FILTER (WHERE g.email IS NOT NULL)
    FROM school.fee_invoices fi
    JOIN school.fee_invoice_balances b ON b.invoice_id = fi.id
    JOIN school.students st            ON st.id = fi.student_id
    JOIN school.guardians g            ON g.id = st.guardian_id
    JOIN school.sections s             ON s.id = fi.section_id
    JOIN school.classes c              ON c.id = s.class_id
    WHERE c.name = 'Class 5' AND s.name = 'Blue'
      AND fi.status = 'Issued' AND b.outstanding_amount > 0 AND fi.due_on < current_date" 2>&1
}
export SQL_COUNTS="$(count_sql)"

# --- Live preflight ------------------------------------------------------------------------------------
section "Preflight against the running backend ($BACKEND_URL)"

cat > "$LOG_DIR/preflight_checks.py" <<'PY'
import base64
import hashlib
import json
import os
import urllib.error
import urllib.request

base = os.environ["BACKEND_URL"]
token = os.environ["DEV_TOKEN"]
sql_counts = os.environ.get("SQL_COUNTS", "").split()


def call(method, path, body=None, bearer=True):
    headers = {"Content-Type": "application/json"}
    if bearer:
        headers["Authorization"] = "Bearer " + token
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"{}")


def report(ok, message, detail=""):
    print(("PASS" if ok else "FAIL") + "|" + message + "|" + str(detail).replace("|", "/").replace("\n", " "))


_, metadata = call("GET", "/agent/metadata", bearer=False)
versions = {c["id"]: c["version"] for c in metadata.get("capabilities", [])}


def plan(*steps):
    return {"plan_id": "verify-phase2", "session_id": "verify-session", "steps": list(steps)}


def step(number, capability, params):
    return {"step": number, "capability_id": capability, "capability_version": versions.get(capability, "?"), "params": params}


def reminder(section, channel="whatsapp"):
    return step(1, "fee.reminder.send", {"section_id": section, "channel": {"value": channel}})


def preflight(plan_body):
    return call("POST", "/agent/preflight", {"plan": plan_body})


# 1. An unambiguous name resolves to an id and a label; the label is what the confirmation uses.
blue_plan = plan(reminder({"raw": "class 5 blue"}))
status, body = preflight(blue_plan)
resolved = (body.get("steps") or [{}])[0].get("resolved") or [{}]
report(status == 200 and resolved[0].get("label") == "Class 5 Blue" and resolved[0].get("id", "").isdigit(),
       'an unambiguous name resolves to an id and a label: "class 5 blue" -> section %s, "%s"'
       % (resolved[0].get("id"), resolved[0].get("label")), "status %s: %s" % (status, body))
report(status == 200 and "in Class 5 Blue by" in (body.get("confirmation") or ""),
       "the confirmation uses the label, not the id", body.get("confirmation"))

# 2. The confirmation comes out fully rendered with the real count.
expected = "Send a fee reminder to 5 guardians in Class 5 Blue by WhatsApp, covering PKR 71,500 outstanding. This cannot be undone."
report(body.get("confirmation") == expected and "{" not in (body.get("confirmation") or "{"),
       "the confirmation is fully rendered with the real count: \"%s\"" % body.get("confirmation"), "expected: " + expected)

counts = {}
for channel in ("whatsapp", "sms", "email"):
    _, channel_body = preflight(plan(reminder({"raw": "class 5 blue"}, channel)))
    counts[channel] = (channel_body.get("steps") or [{}])[0].get("count")
from_backend = [str(counts[c]) for c in ("whatsapp", "sms", "email")]
report(sql_counts == from_backend,
       "each count is what the channel can reach, matching SQL run here on the tables (WhatsApp %s, SMS %s, email %s)"
       % tuple(from_backend), "SQL says %s" % sql_counts)

# 3. An ambiguous name returns AMBIGUOUS_ENTITY with candidates, and the choice resolves.
status, body = preflight(plan(reminder({"raw": "class 5"})))
labels = [c.get("label") for c in body.get("candidates", [])]
report(status == 422 and body.get("code") == "AMBIGUOUS_ENTITY" and labels == ["Class 5 Blue", "Class 5 Green"],
       'an ambiguous name returns AMBIGUOUS_ENTITY with candidates: "class 5" -> %s' % " / ".join(labels or ["none"]),
       "status %s: %s" % (status, body))
blue_id = next((c["id"] for c in body.get("candidates", []) if c.get("label") == "Class 5 Blue"), None)
status, chosen = preflight(plan(reminder({"raw": "class 5", "chosen_id": blue_id})))
report(status == 200 and chosen.get("confirmation") == expected,
       "the user's choice goes back with the same words and resolves, without a new plan", "status %s: %s" % (status, chosen))

# 4. A failing precondition returns PRECONDITION_FAILED carrying its hint.
status, body = preflight(plan(reminder({"raw": "class 6 blue"})))
report(status == 422 and body.get("code") == "PRECONDITION_FAILED"
       and body.get("hint") == "Nobody in this section has overdue fees right now",
       'a failing precondition returns PRECONDITION_FAILED with its hint: "%s"' % body.get("hint"), "status %s: %s" % (status, body))

# 5. The token binds this exact plan: its plan hash is SHA-256 of the canonical plan, recomputed here.
_, body = preflight(blue_plan)
issued = body.get("token", "")
try:
    part = issued.split(".")[0]
    payload = json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
except Exception as error:  # noqa: BLE001 - reported as a failed check
    payload = {"error": str(error)}


def plan_hash(plan_body):
    canonical = json.dumps(plan_body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


edited = json.loads(json.dumps(blue_plan))
edited["steps"][0]["params"]["section_id"]["raw"] = "class 5 bluE"
report(payload.get("plan_hash") == plan_hash(blue_plan) and plan_hash(edited) != payload.get("plan_hash")
       and payload.get("steps", [{}])[0].get("count") == 5,
       "the token carries the plan's SHA-256 (recomputed here), its resolved ids and count; one changed character changes the hash",
       payload)
report(len(issued.split(".")) == 2 and "class 5 blue" not in json.dumps(payload),
       "the token is base64url(payload).signature and carries ids and numbers, never the user's words", issued[:40])

# 6. Everything else preflight refuses.
refused = []
for capability in ("fee.cancellation.raise", "fee.credit.raise", "fee.writeoff.propose", "fee.latefee.waive"):
    status, body = preflight(plan(step(1, capability, {"invoice_id": {"raw": "Ahmed Raza's September invoice"}})))
    refused.append((capability, status, body.get("code")))
report(all(s == 501 and c == "NOT_IMPLEMENTED" for _, s, c in refused),
       "the four confusable fee corrections are refused with NOT_IMPLEMENTED, before anything is looked up", refused)

status, not_found = preflight(plan(reminder({"raw": "class 9 red"})))
stale_plan = plan(reminder({"raw": "class 5 blue"}))
stale_plan["steps"][0]["capability_version"] = "0" * 64
stale_status, stale = preflight(stale_plan)
anon_status, anon = call("POST", "/agent/preflight", {"plan": blue_plan}, bearer=False)
report((status, not_found.get("code"), stale_status, stale.get("code"), anon_status, anon.get("code"))
       == (422, "NOT_FOUND", 409, "STALE_VERSION", 401, "UNAUTHENTICATED"),
       "a name matching nothing is NOT_FOUND, an old version is STALE_VERSION, and no user is UNAUTHENTICATED",
       [(status, not_found), (stale_status, stale), (anon_status, anon)])

status, payment = preflight(plan(step(1, "fee.payment.record", {
    "invoice_id": {"raw": "Ahmed Raza's September invoice"}, "route": {"value": "cash"},
    "amount_received": {"value": "5000"}, "payment_date": {"value": "2026-09-14"}})))
report(status == 200 and payment.get("confirmation") ==
       "Record PKR 5,000 received in cash on 14 September 2026 against Ahmed Raza's September 2026 invoice INV/LHR/26-27/000031.",
       "a single-record write counts 1 and reads naturally: \"%s\"" % payment.get("confirmation"), "status %s: %s" % (status, payment))
PY

if ! curl -fsS -m 5 "$BACKEND_URL/actuator/health" >/dev/null 2>&1; then
  fail "backend is not answering" "run: make backend-run"
elif [[ ! "$SQL_COUNTS" =~ ^[0-9]+\ [0-9]+\ [0-9]+$ ]]; then
  fail "the database could not be queried for the independent counts" "${SQL_COUNTS:0:200} (run: make db-up)"
else
  report_lines preflight python3 "$LOG_DIR/preflight_checks.py"
fi

# --- The AI layer calls it through its generated models --------------------------------------------
section "AI layer calls preflight through its generated models"

cat > "$LOG_DIR/ai_client_check.py" <<'PY'
import asyncio
import os

from app.core.settings import Settings
from app.gateway.client import GatewayClient
from app.gateway.errors import GatewayRejectedError
from app.gateway.models import ParamValue, Plan, PlanStep


async def main() -> None:
    client = GatewayClient.from_settings(Settings(backend_base_url=os.environ["BACKEND_URL"]))
    try:
        versions = {v.id: v.version for v in (await client.get_versions()).versions}

        def reminder(section: ParamValue) -> Plan:
            return Plan(plan_id="verify-ai", session_id="verify-ai", steps=[PlanStep(
                step=1, capability_id="fee.reminder.send", capability_version=versions["fee.reminder.send"],
                params={"section_id": section, "channel": ParamValue(value="whatsapp")})])

        ready = await client.preflight(reminder(ParamValue(raw="class 5 blue")), os.environ["DEV_TOKEN"])
        assert ready.requires_confirmation and ready.confirmation and ready.token, ready
        assert ready.steps[0].count == 5, ready.steps[0]
        try:
            await client.preflight(reminder(ParamValue(raw="blue")), os.environ["DEV_TOKEN"])
        except GatewayRejectedError as rejection:
            labels = [candidate.label for candidate in rejection.error.candidates or []]
            assert rejection.code == "AMBIGUOUS_ENTITY" and labels == ["Class 5 Blue", "Class 6 Blue"], labels
        else:
            raise AssertionError("an ambiguous name should have been refused")
    finally:
        await client.aclose()


asyncio.run(main())
PY

run_check "the AI layer's client sends a plan, reads the confirmation, and reads AMBIGUOUS_ENTITY candidates" ai-client \
  bash -c 'cd ai-layer && PYTHONPATH=. uv run python "$0"' "$LOG_DIR/ai_client_check.py"

# --- What cannot be seen from outside ----------------------------------------------------------------
section "Token verification and the rules behind preflight (Maven)"

run_check "gateway: a tampered plan hash, an edited plan, another key, another user and an expired token all fail verification; every refusal and the composed confirmation" gateway-preflight \
  bash -c 'cd backend && ./mvnw -B -ntp -q -pl agent-gateway test -Dtest=PreflightTokensTest,PlanHasherTest,PreflightServiceTest,PreflightControllerTest,RegistryRulesTest,AgentGatewayAutoConfigurationTest'

run_check "real backend: preflight on the seeded school, another branch's records invisible (invariant 7), a tampered token refused, and no startup without a resolver" backend-preflight \
  bash -c 'cd backend && ./mvnw -B -ntp -q -pl school-app -am verify -Dtest=SchoolWordingTest,InvoicePhraseTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=PreflightIT,RegistryBuildChecksIT,AgentMetadataSnapshotIT,OpenApiContractIT -Dfailsafe.failIfNoSpecifiedTests=false'

echo
if [[ $failures -eq 0 ]]; then
  printf '\033[32mPhase 2 is done: every check passed.\033[0m\n'
else
  printf '\033[31m%d check(s) failed.\033[0m Logs in %s\n' "$failures" "$LOG_DIR"
fi
exit "$failures"
