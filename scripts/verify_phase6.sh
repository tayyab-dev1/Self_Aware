#!/usr/bin/env bash
# Checks every Phase 6 "done when" item (docs/POC_Implementation_Plan.md) with the real models:
# the full loop end to end for one read and one write through POST /chat, an ambiguous name asked
# and resumed without a second planner call, and a cancelled confirmation leaving no audit row.
#
#   make db-up && make embeddings-up
#   make backend-run      # terminal 1
#   make ai-run           # terminal 2
#   make verify-phase6    # terminal 3 (calls Haiku and Sonnet through the Claude CLI)
#
# The write sends Class 5 Blue's WhatsApp reminder once more: reminders are logged and change no
# balance. Everything else is a read, or cancelled before anything runs.
#
# Exit code is the number of failed checks (0 = Phase 6 is done).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi
export AI_URL="http://${AI_LAYER_HOST:-127.0.0.1}:${AI_LAYER_PORT:-8081}"
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

printf '\033[1mPhase 6: Orchestrator and chat\033[0m\n'

cat > "$LOG_DIR/common.py" <<'PY'
import json
import os
import subprocess
import urllib.error
import urllib.request


def report(ok, message, detail=""):
    print(("PASS" if ok else "FAIL") + "|" + message + "|" + str(detail).replace("|", "/").replace("\n", " ")[:300])


def sql(query):
    """SQL in the database container as the backend role (the AI layer itself has no access)."""
    command = ["docker", "compose", "--project-directory", "docker", "-f", "docker/docker-compose.yml"]
    if os.environ.get("ENV_FILE_ARG"):
        command += ["--env-file", os.environ["ENV_FILE_ARG"]]
    command += ["exec", "-T", "postgres", "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-U", os.environ["DB_ROLE"],
                "-d", os.environ["DB_NAME"], "-tA", "-c", "SET search_path = school, public; " + query]
    done = subprocess.run(command, capture_output=True, text=True, cwd=os.environ["ROOT"])
    if done.returncode != 0:
        raise RuntimeError(done.stderr.strip() or done.stdout.strip())
    return done.stdout.strip()


def audit_kinds(plan_id):
    if not plan_id or "'" in plan_id:
        return []
    rows = sql(f"SELECT kind FROM agent_audit WHERE plan_id = '{plan_id}' ORDER BY id")
    return rows.split("\n") if rows else []
PY

# --- Through POST /chat on the running AI layer -------------------------------------------------------
section "The full loop through POST /chat ($AI_URL)"

cat > "$LOG_DIR/http_checks.py" <<'PY'
import json
import os
import sys
import urllib.error
import urllib.request

sys.path.insert(0, os.environ["LOG_DIR"])
from common import audit_kinds, report, sql  # noqa: E402

URL = os.environ["AI_URL"] + "/chat"


def chat(body, token=os.environ["DEV_TOKEN"]):
    request = urllib.request.Request(URL, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"{}")


status, _ = chat({"message": "class 5 blue ka baqaya dikhao"}, token="not-a-real-token")
report(status == 401, "a token the backend does not accept gets 401, before any model is called", status)

# A read, end to end
status, read = chat({"message": "class 5 blue mein kis kis ki fees baqaya hai?"})
report(status == 200 and read.get("type") == "answer"
       and read.get("text") == "Overdue fees for Class 5 Blue: 8 students, PKR 84,500 outstanding.",
       f"read: a Roman Urdu question is answered in one turn, in the backend's words: \"{read.get('text')}\"", read)
report([s.get("status") for s in read.get("steps", [])] == ["succeeded"] and read["steps"][0].get("count") == 8,
       "read: the answer carries the step's status and count (8 students)", read.get("steps"))
kinds = audit_kinds(read.get("plan_id"))
report(kinds == ["STARTED", "SUCCEEDED"], f"read: the backend audited it: {kinds}", kinds)

# A write, end to end
status, asked = chat({"message": "class 5 blue ke defaulters ko whatsapp par reminder bhejo"})
session = asked.get("session_id")
report(asked.get("type") == "confirmation" and "5 guardians in Class 5 Blue by WhatsApp" in asked.get("text", ""),
       f"write: the reply is the backend's confirmation: \"{asked.get('text')}\"", asked)
report(audit_kinds(asked.get("plan_id")) == [], "write: nothing has run before the user confirms (no audit row yet)")
before = int(sql("SELECT count(*) FROM fee_reminders"))
status, done = chat({"session_id": session, "confirm": True})
after = int(sql("SELECT count(*) FROM fee_reminders"))
report(done.get("type") == "answer" and done.get("text") == "Sent a fee reminder to 5 guardians in Class 5 Blue by WhatsApp.",
       f"write: confirming runs it and answers in the backend's words: \"{done.get('text')}\"", done)
report(after - before == 5 and audit_kinds(done.get("plan_id")) == ["STARTED", "SUCCEEDED"],
       "write: 5 reminders were logged (counted here with SQL) and audited STARTED, SUCCEEDED", (after - before, audit_kinds(done.get("plan_id"))))
report(done.get("plan_id") == asked.get("plan_id"), "write: the plan executed is the plan that was confirmed", (asked.get("plan_id"), done.get("plan_id")))
PY

report_lines http bash -c 'cd ai-layer && ROOT="$1" LOG_DIR="$2" PYTHONPATH=. uv run python "$2/http_checks.py"' _ "$ROOT" "$LOG_DIR"

# --- In process, counting model calls ---------------------------------------------------------------
section "An ambiguous name, resumed without planning again; a cancelled confirmation (model calls counted)"

cat > "$LOG_DIR/count_checks.py" <<'PY'
import asyncio
import os
import sys

sys.path.insert(0, os.environ["LOG_DIR"])
from common import audit_kinds, report  # noqa: E402

from app.core.settings import Settings
from app.decompose.decomposer import Decomposer
from app.embeddings.client import EmbeddingsClient
from app.gateway.client import GatewayClient
from app.index.database import IndexDatabase
from app.llm.claude_cli import ClaudeCliModel
from app.orchestration.orchestrator import ChatOrchestrator, ChatTurn
from app.orchestration.plan_cache import PlanCache
from app.orchestration.session import InMemorySessionStore
from app.planning.planner import Planner
from app.planning.service import SentencePlanner
from app.retrieval.hybrid import HybridRetriever
from domain.school.calendar import SCHOOL_TIME_ZONE, school_today
from domain.school.glossary import RECORD_WORDS, glossary_lines


class Counting:
    def __init__(self, model):
        self.model = model
        self.calls = []

    async def generate(self, request):
        self.calls.append(request.purpose)
        return await self.model.generate(request)


async def main():
    settings = Settings()
    token = os.environ["DEV_TOKEN"]
    model = Counting(ClaudeCliModel.from_settings(settings))
    gateway = GatewayClient.from_settings(settings)
    index = await IndexDatabase.connect(settings)
    embeddings = EmbeddingsClient.from_settings(settings)
    try:
        catalog = {c.id: c for c in (await gateway.get_metadata()).capabilities}
        planner = SentencePlanner(
            Decomposer(model, glossary_lines()),
            HybridRetriever(index, embeddings),
            Planner(model, max_steps=3, today=school_today, time_zone=SCHOOL_TIME_ZONE, record_words=RECORD_WORDS),
            lambda: catalog,
        )
        chat = ChatOrchestrator(gateway=gateway, planner=planner, catalog=lambda: catalog,
                                sessions=InMemorySessionStore(ttl_seconds=600), plan_cache=PlanCache(0),
                                today=school_today)

        first = await chat.handle(None, ChatTurn(message="class 5 ke defaulters ko sms par reminder bhejo"), token)
        question = first.reply
        labels = [option.label for option in question.options]
        report(question.type == "question" and question.code == "AMBIGUOUS_ENTITY"
               and sorted(labels) == ["Class 5 Blue", "Class 5 Green"],
               f"\"class 5\" is ambiguous: the reply is a question with the backend's options {labels}", question)
        calls_after_question = list(model.calls)
        report(calls_after_question == ["decompose", "plan"], f"planning the sentence took one decompose and one plan call: {calls_after_question}")

        blue = next((o.id for o in question.options if o.label == "Class 5 Blue"), None)
        second = await chat.handle(first.session_id, ChatTurn(choice=blue), token)
        confirmation = second.reply
        report(confirmation.type == "confirmation" and "6 guardians in Class 5 Blue by SMS" in confirmation.text,
               f"choosing Class 5 Blue resumes the same plan: \"{confirmation.text}\"", confirmation)
        report(model.calls == calls_after_question and confirmation.plan_id == question.plan_id,
               "resuming made no second planner call, and kept the same plan id",
               (model.calls, question.plan_id, confirmation.plan_id))

        third = await chat.handle(first.session_id, ChatTurn(confirm=False), token)
        report(third.reply.type == "answer" and third.reply.text == "Cancelled. Nothing was changed.",
               f"cancelling at the confirmation answers \"{third.reply.text}\"", third.reply)
        kinds = audit_kinds(confirmation.plan_id)
        report(kinds == [], f"after cancelling, the audit trail has no row for the plan (checked with SQL): {kinds}")
        after_cancel = await chat.handle(first.session_id, ChatTurn(confirm=True), token)
        report(after_cancel.reply.code == "NOTHING_PENDING" and audit_kinds(confirmation.plan_id) == [],
               "a late \"yes\" after cancelling runs nothing", after_cancel.reply)
    finally:
        await embeddings.aclose()
        await index.close()
        await gateway.aclose()


asyncio.run(main())
PY

report_lines count bash -c 'cd ai-layer && ROOT="$1" LOG_DIR="$2" PYTHONPATH=. uv run python "$2/count_checks.py"' _ "$ROOT" "$LOG_DIR"

section "The state machine and POST /chat (unit tests with a scripted backend and planner)"

run_check "read and write loops, questions resumed from the same plan, cancel, expired token, moved count, cache, sessions per user, API shape" unit \
  bash -c 'cd ai-layer && uv run pytest -q -p no:cacheprovider tests/unit/test_chat_orchestrator.py tests/unit/test_chat_answers.py tests/unit/test_chat_api.py'

echo
if [[ $failures -eq 0 ]]; then
  printf '\033[32mPhase 6 is done: every check passed.\033[0m\n'
else
  printf '\033[31m%d check(s) failed.\033[0m Logs in %s\n' "$failures" "$LOG_DIR"
fi
exit "$failures"
