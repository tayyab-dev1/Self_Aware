#!/usr/bin/env bash
# Checks every Phase 3 "done when" item (docs/POC_Implementation_Plan.md) against the
# running stack, and runs the tests that prove what cannot be shown safely on the demo
# school: a payment recorded for real, and a misreporting handler rolled back.
#
#   make db-up
#   make backend-run      # terminal 1
#   make verify-phase3    # terminal 2 (takes a few minutes: it runs Maven)
#
# The live checks only change the demo school in ways they undo: reminders are logged
# (they change no balance), a test payment and a WhatsApp number are added and removed
# again. The audit trail keeps what it recorded; it is append-only.
#
# Exit code is the number of failed checks (0 = Phase 3 is done).
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
export DB_NAME="${POSTGRES_DB:-sms}"
export DB_ROLE="${BACKEND_DB_USER:-sms_backend}"
export ENV_FILE_ARG="$([[ -f .env ]] && echo .env || echo)"
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

printf '\033[1mPhase 3: Execute\033[0m\n'

# --- Live execute -------------------------------------------------------------------------------------
section "Execute against the running backend ($BACKEND_URL)"

cat > "$LOG_DIR/execute_checks.py" <<'PY'
import json
import os
import subprocess
import urllib.error
import urllib.request
import uuid

base = os.environ["BACKEND_URL"]
token = os.environ["DEV_TOKEN"]
SENTENCE = "class 5 blue ke defaulters ko whatsapp par reminder bhejo"


def sql(query):
    """Runs SQL in the database container as the backend role; returns the output, or raises with psql's error."""
    command = ["docker", "compose", "--project-directory", "docker", "-f", "docker/docker-compose.yml"]
    if os.environ.get("ENV_FILE_ARG"):
        command += ["--env-file", os.environ["ENV_FILE_ARG"]]
    command += ["exec", "-T", "postgres", "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-U", os.environ["DB_ROLE"],
                "-d", os.environ["DB_NAME"], "-tA", "-c", "SET search_path = school, public; " + query]
    done = subprocess.run(command, capture_output=True, text=True)
    if done.returncode != 0:
        raise RuntimeError(done.stderr.strip() or done.stdout.strip())
    return done.stdout.strip()


def call(method, path, body=None, bearer=True):
    headers = {"Content-Type": "application/json"}
    if bearer:
        headers["Authorization"] = "Bearer " + token
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"{}")


def report(ok, message, detail=""):
    print(("PASS" if ok else "FAIL") + "|" + message + "|" + str(detail).replace("|", "/").replace("\n", " "))


_, metadata = call("GET", "/agent/metadata", bearer=False)
versions = {c["id"]: c["version"] for c in metadata.get("capabilities", [])}


def plan(*steps):
    return {"plan_id": "verify-" + uuid.uuid4().hex, "session_id": "verify-phase3", "steps": list(steps)}


def step(number, capability, params):
    return {"step": number, "capability_id": capability, "capability_version": versions.get(capability, "?"), "params": params}


def reminder():
    return step(1, "fee.reminder.send", {"section_id": {"raw": "class 5 blue"}, "channel": {"value": "whatsapp"}})


def confirm(plan_body):
    status, body = call("POST", "/agent/preflight", {"plan": plan_body})
    if status != 200:
        raise RuntimeError("preflight answered %s: %s" % (status, body))
    return body


def execute(plan_body, preflight_token):
    return call("POST", "/agent/execute", {"plan": plan_body, "token": preflight_token, "sentence": SENTENCE})


def audit_kinds(plan_body):
    rows = sql("SELECT kind FROM agent_audit WHERE plan_id = '%s' ORDER BY id" % plan_body["plan_id"])
    return rows.split("\n") if rows else []


# 1. A successful write produces an audit row and passes verification.
reminders_before = int(sql("SELECT count(*) FROM fee_reminders"))
send = plan(reminder())
confirmation = confirm(send)
status, body = execute(send, confirmation["token"])
done = (body.get("steps") or [{}])[0]
report(status == 200 and done.get("status") == "succeeded"
       and done.get("reply") == "Sent a fee reminder to 5 guardians in Class 5 Blue by WhatsApp."
       and done.get("count") == confirmation["steps"][0]["count"] == 5,
       'a confirmed reminder runs and passes verification: "%s" (count %s, as confirmed)' % (done.get("reply"), done.get("count")),
       "status %s: %s" % (status, body))
reminders_after = int(sql("SELECT count(*) FROM fee_reminders"))
queued = sql("SELECT count(*) || ' ' || COALESCE(sum(amount_outstanding), 0) FROM ("
             "SELECT * FROM fee_reminders ORDER BY id DESC LIMIT 5) latest WHERE delivery_status = 'Queued'")
report(reminders_after - reminders_before == 5 and queued.split()[0] == "5" and float(queued.split()[1]) == 71500,
       "it logged 5 reminders, Queued, covering PKR 71,500 (counted here with SQL)",
       "before %s, after %s, latest: %s" % (reminders_before, reminders_after, queued))
audit = sql("SELECT kind || ' ' || actual_count FROM agent_audit WHERE plan_id = '%s' ORDER BY id" % send["plan_id"])
sentence_kept = sql("SELECT count(*) FROM agent_audit WHERE plan_id = '%s' AND sentence = '%s'"
                    % (send["plan_id"], SENTENCE.replace("'", "''")))
report(audit.split("\n") == ["STARTED 5", "SUCCEEDED 5"] and sentence_kept == "2",
       "it produced audit rows STARTED and SUCCEEDED, each holding the user's sentence (read here with SQL)", audit)

# 2. Replaying the same idempotency key does not double-execute.
status, body = execute(send, confirmation["token"])
replayed = (body.get("steps") or [{}])[0]
report(status == 200 and replayed.get("status") == "replayed" and replayed.get("reply") == done.get("reply")
       and int(sql("SELECT count(*) FROM fee_reminders")) == reminders_after and audit_kinds(send)[-1] == "REPLAYED",
       "replaying the same session, plan and step answers \"replayed\" and sends nothing more", "status %s: %s" % (status, body))

# 3. Changing the underlying state between preflight and execute produces PRECONDITION_FAILED.
pay = plan(step(1, "fee.payment.record", {"invoice_id": {"raw": "Ahmed Raza's September invoice"}, "route": {"value": "cash"},
                                          "amount_received": {"value": "9000"}, "payment_date": {"value": "2026-09-14"}}))
pay_token = confirm(pay)["token"]
payments_before = int(sql("SELECT count(*) FROM fee_payments"))
sql("INSERT INTO fee_payments (invoice_id, receipt_no, amount, method, paid_on, recorded_by) "
    "SELECT id, 'RCT/VERIFY/' || md5(random()::text), 500, 'Cash', DATE '2026-09-14', 1 FROM fee_invoices "
    "WHERE invoice_no = 'INV/LHR/26-27/000031'")
try:
    status, body = execute(pay, pay_token)
    refused = (body.get("steps") or [{}])[0].get("error") or {}
    written = int(sql("SELECT count(*) FROM fee_payments")) - payments_before
finally:
    sql("DELETE FROM fee_payments WHERE receipt_no LIKE 'RCT/VERIFY/%'")
report(status == 200 and refused.get("code") == "PRECONDITION_FAILED" and refused.get("precondition") == "amount_within_balance"
       and written == 1 and audit_kinds(pay) == ["REFUSED"],
       "state changed after preflight (a PKR 500 payment added here) gives PRECONDITION_FAILED, and no payment is written",
       "status %s: %s; payments written besides the test one: %s" % (status, body, written - 1))

# 4. A count that moved materially between preflight and execute fails rather than proceeding.
moved = plan(reminder())
moved_token = confirm(moved)["token"]
reminders_before = int(sql("SELECT count(*) FROM fee_reminders"))
sql("UPDATE guardians SET whatsapp_number = '03000000104' WHERE family_code = 'FAM-0104'")
try:
    status, body = execute(moved, moved_token)
    changed = (body.get("steps") or [{}])[0].get("error") or {}
finally:
    sql("UPDATE guardians SET whatsapp_number = NULL WHERE family_code = 'FAM-0104'")
report(changed.get("code") == "COUNT_CHANGED" and changed.get("confirmed_count") == 5 and changed.get("current_count") == 6
       and int(sql("SELECT count(*) FROM fee_reminders")) == reminders_before,
       "a count that moved (a WhatsApp number added here: 5 guardians became 6) gives COUNT_CHANGED, and nothing is sent",
       "status %s: %s" % (status, body))

# 5. Refused as a whole, and reads.
other = plan(step(1, "fee.reminder.send", {"section_id": {"raw": "class 5 green"}, "channel": {"value": "whatsapp"}}))
status, body = execute(other, confirmation["token"])
report(status == 403 and body.get("code") == "TOKEN_INVALID" and audit_kinds(other) == ["REFUSED"],
       "a token issued for another plan is refused as a whole (TOKEN_INVALID), and the attempt is recorded", body)

overdue = plan(step(1, "fee.overdue.list", {"scope": {"value": "section"}, "section_id": {"raw": "class 5 blue"}}))
status, body = execute(overdue, confirm(overdue)["token"])
read = (body.get("steps") or [{}])[0]
report(status == 200 and read.get("reply") == "Overdue fees for Class 5 Blue: 8 students, PKR 84,500 outstanding.",
       'a read runs and answers from the data: "%s"' % read.get("reply"), "status %s: %s" % (status, body))

try:
    sql("UPDATE agent_audit SET sentence = 'changed' WHERE plan_id = '%s'" % send["plan_id"])
    changeable = "UPDATE went through"
except RuntimeError as error:
    changeable = str(error)
try:
    sql("DELETE FROM agent_audit WHERE plan_id = '%s'" % send["plan_id"])
    removable = "DELETE went through"
except RuntimeError as error:
    removable = str(error)
report("append-only" in changeable and "append-only" in removable,
       "the audit trail refuses UPDATE and DELETE (tried here with SQL)", "%s / %s" % (changeable, removable))
PY

if ! curl -fsS -m 5 "$BACKEND_URL/actuator/health" >/dev/null 2>&1; then
  fail "backend is not answering" "run: make backend-run"
else
  report_lines execute python3 "$LOG_DIR/execute_checks.py"
fi

# --- The AI layer calls it through its generated models --------------------------------------------
section "AI layer calls preflight and execute through its generated models"

cat > "$LOG_DIR/ai_client_check.py" <<'PY'
import asyncio
import os
import uuid

from app.core.settings import Settings
from app.gateway.client import GatewayClient
from app.gateway.models import ParamValue, Plan, PlanStep


async def main() -> None:
    client = GatewayClient.from_settings(Settings(backend_base_url=os.environ["BACKEND_URL"]))
    try:
        versions = {v.id: v.version for v in (await client.get_versions()).versions}
        plan = Plan(plan_id="verify-ai-" + uuid.uuid4().hex, session_id="verify-ai", steps=[PlanStep(
            step=1, capability_id="fee.overdue.list", capability_version=versions["fee.overdue.list"],
            params={"scope": ParamValue(value="section"), "section_id": ParamValue(raw="class 5 blue")})])
        confirmed = await client.preflight(plan, os.environ["DEV_TOKEN"])
        executed = await client.execute(plan, confirmed.token, "class 5 blue ki fees kis ne nahi di", os.environ["DEV_TOKEN"])
        assert executed.outcome == "completed", executed
        assert executed.steps[0].reply == "Overdue fees for Class 5 Blue: 8 students, PKR 84,500 outstanding.", executed
    finally:
        await client.aclose()


asyncio.run(main())
PY

run_check "the AI layer's client confirms a plan, executes it, and reads the step's reply" ai-client \
  bash -c 'cd ai-layer && PYTHONPATH=. uv run python "$0"' "$LOG_DIR/ai_client_check.py"

# --- What is only safe on a throwaway database ----------------------------------------------------------
section "Rollback, payments and the rules behind execute (Maven)"

run_check "gateway: a replay runs nothing twice, the delta rule, a misreporting handler, partial plans, expired and altered tokens, stale versions" gateway-execute \
  bash -c 'cd backend && ./mvnw -B -ntp -q -pl agent-gateway test -Dtest=ExecuteServiceTest,ExecuteControllerTest,DeltaRuleTest,RegistryRulesTest'

run_check "real backend: a payment with the next receipt number, a closed section out of scope, a partial plan, and a misreporting handler rolled back with its writes" backend-execute \
  bash -c 'cd backend && ./mvnw -B -ntp -q -pl school-app -am verify -Dtest=SchoolWordingTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ExecuteIT,ExecuteRollbackIT,DatabaseMigrationIT,AgentMetadataSnapshotIT,OpenApiContractIT -Dfailsafe.failIfNoSpecifiedTests=false'

echo
if [[ $failures -eq 0 ]]; then
  printf '\033[32mPhase 3 is done: every check passed.\033[0m\n'
else
  printf '\033[31m%d check(s) failed.\033[0m Logs in %s\n' "$failures" "$LOG_DIR"
fi
exit "$failures"
