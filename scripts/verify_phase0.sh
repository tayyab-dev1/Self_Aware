#!/usr/bin/env bash
# Checks every Phase 0 "done when" item (docs/POC_Implementation_Plan.md) against the
# running local stack:
#
#   make db-up
#   make backend-run      # terminal 1
#   make ai-run           # terminal 2
#   make verify-phase0    # terminal 3
#
# Exit code is the number of failed checks (0 = Phase 0 is done).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

BACKEND_URL="http://${BACKEND_HOST:-127.0.0.1}:${BACKEND_PORT:-8080}"
AI_URL="http://${AI_LAYER_HOST:-127.0.0.1}:${AI_LAYER_PORT:-8081}"
DB="${POSTGRES_DB:-sms}"
SUPERUSER="${POSTGRES_SUPERUSER:-postgres}"
BACKEND_ROLE="${BACKEND_DB_USER:-sms_backend}"
AI_ROLE="${AI_LAYER_INDEX_DB_USER:-sms_ai_layer}"
COMPOSE=(docker compose --project-directory docker -f docker/docker-compose.yml)
[[ -f .env ]] && COMPOSE+=(--env-file .env)

failures=0
pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() {
  printf '  \033[31m✗\033[0m %s\n' "$1"
  [[ -n "${2:-}" ]] && printf '      %s\n' "$2"
  failures=$((failures + 1))
}
section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# Run SQL inside the container as a given role (unix socket, no password needed).
sql_as() {
  "${COMPOSE[@]}" exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U "$1" -d "$DB" -tA -c "$2" 2>&1
}

json_field() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)" 2>/dev/null; }

printf '\033[1mPhase 0: Scaffold\033[0m\n'

# --- Database -----------------------------------------------------------------
section "Database (Postgres 16 + pgvector)"

if "${COMPOSE[@]}" exec -T postgres pg_isready -h 127.0.0.1 -U "$SUPERUSER" -d "$DB" >/dev/null 2>&1; then
  version=$(sql_as "$SUPERUSER" "SELECT extversion FROM pg_extension WHERE extname = 'vector'")
  pass "Postgres is up, vector extension $version"
else
  fail "Postgres is not running" "run: make db-up"
fi

for role in "$BACKEND_ROLE" "$AI_ROLE"; do
  out=$(sql_as "$role" "SELECT '[1,2,3]'::vector")
  if [[ "$out" == "[1,2,3]" ]]; then
    pass "SELECT '[1,2,3]'::vector works for $role"
  else
    fail "SELECT '[1,2,3]'::vector failed for $role" "$out"
  fi
done

out=$(sql_as "$AI_ROLE" "SELECT count(*) FROM school.fee_invoices")
if [[ "$out" == *"permission denied"* ]]; then
  pass "$AI_ROLE is refused on business data (invariant 1)"
else
  fail "$AI_ROLE can reach business data, or the check could not run" "$out"
fi

# --- Backend ------------------------------------------------------------------
section "Backend ($BACKEND_URL)"

health=$(curl -fsS -m 5 "$BACKEND_URL/actuator/health" 2>&1)
if [[ "$(json_field 'd["status"]' <<<"$health")" == "UP" ]]; then
  pass "backend is running, health UP"
else
  fail "backend is not answering" "run: make backend-run   (got: ${health:0:120})"
fi

migrations=$(sql_as "$BACKEND_ROLE" "SELECT count(*) FILTER (WHERE success) || ' applied, ' || count(*) FILTER (WHERE NOT success) || ' failed' FROM school.flyway_schema_history WHERE version IS NOT NULL")
if [[ "$migrations" == *" applied, 0 failed" && "$migrations" != "0 applied"* ]]; then
  pass "Flyway migrations ran clean ($migrations)"
else
  fail "Flyway migrations did not run clean" "$migrations"
fi

seed=$(sql_as "$BACKEND_ROLE" "SELECT (SELECT count(*) FROM school.classes) || ' classes, ' || (SELECT count(*) FROM school.students) || ' students, ' || (SELECT count(*) FROM school.fee_invoices) || ' invoices (' || (SELECT count(*) FROM school.fee_invoice_balances WHERE settlement <> 'Paid') || ' not fully paid), ' || (SELECT count(*) FROM school.app_users) || ' user'")
if [[ "$seed" == "2 classes, 30 students, 60 invoices ("*" not fully paid), 1 user" && "$seed" != *"(0 not fully paid)"* ]]; then
  pass "seed data: $seed"
else
  fail "seed data is not what Phase 0 asks for" "$seed"
fi

metadata=$(curl -fsS -m 5 "$BACKEND_URL/agent/metadata" 2>&1)
count=$(json_field 'len(d["capabilities"])' <<<"$metadata")
if [[ -n "$count" ]]; then
  pass "GET /agent/metadata answers ($count capabilities)"
else
  fail "GET /agent/metadata did not answer with the contract shape" "${metadata:0:160}"
fi

# --- AI layer -----------------------------------------------------------------
section "AI layer ($AI_URL)"

live=$(curl -fsS -m 5 "$AI_URL/health/live" 2>&1)
if [[ "$(json_field 'd["status"]' <<<"$live")" == "ok" ]]; then
  pass "AI layer is running"
else
  fail "AI layer is not answering" "run: make ai-run   (got: ${live:0:120})"
fi

index=$(sql_as "$AI_ROLE" "SELECT coalesce(string_agg(version, ',' ORDER BY version), 'none') || CASE WHEN to_regclass('ai_layer.capability_index') IS NULL THEN ' (capability_index missing)' ELSE '' END FROM ai_layer.schema_migrations")
if [[ "$index" != *"missing"* && "$index" != *"ERROR"* && "$index" != "none" ]]; then
  pass "AI layer index migrations ran clean ($index), capability_index exists"
else
  fail "AI layer index migrations did not run" "$index"
fi

ready=$(curl -sS -m 10 "$AI_URL/health/ready" 2>&1)
backend_ok=$(json_field 'd["checks"]["backend_metadata"]["ok"]' <<<"$ready")
if [[ "$backend_ok" == "True" ]]; then
  pass "AI layer reaches GET /agent/metadata ($(json_field 'd["checks"]["backend_metadata"]["detail"]' <<<"$ready"))"
else
  fail "AI layer cannot reach GET /agent/metadata" "${ready:0:240}"
fi
if [[ "$(json_field 'd["status"]' <<<"$ready")" == "ready" ]]; then
  pass "AI layer readiness: ready"
else
  fail "AI layer readiness is not ready" "${ready:0:240}"
fi

echo
if [[ $failures -eq 0 ]]; then
  printf '\033[32mPhase 0 is done: every check passed.\033[0m\n'
else
  printf '\033[31m%d check(s) failed.\033[0m\n' "$failures"
fi
exit "$failures"
