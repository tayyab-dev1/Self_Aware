#!/usr/bin/env bash
# Runs once, when the Postgres data volume is first created (docker compose
# and the Testcontainers-based tests both mount this same file).
#
# One database, two services, each with its own role and exactly one schema:
#
#   school    business data. Owned by the backend role; Flyway migrates it.
#   ai_layer  the capability index. Owned by the AI layer role.
#
# The AI layer role is granted nothing on "school", so CLAUDE.md invariant 1
# ("the AI layer never queries the business database") is enforced by
# Postgres itself, not only by convention.
set -euo pipefail

: "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"
: "${BACKEND_DB_USER:?BACKEND_DB_USER must be set}"
: "${BACKEND_DB_PASSWORD:?BACKEND_DB_PASSWORD must be set}"
: "${AI_LAYER_INDEX_DB_USER:?AI_LAYER_INDEX_DB_USER must be set}"
: "${AI_LAYER_INDEX_DB_PASSWORD:?AI_LAYER_INDEX_DB_PASSWORD must be set}"

# Values are passed as psql variables and quoted by psql (:'x' / :"x"),
# never pasted into the SQL by the shell.
psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v db_name="$POSTGRES_DB" \
  -v backend_user="$BACKEND_DB_USER" -v backend_password="$BACKEND_DB_PASSWORD" \
  -v ai_user="$AI_LAYER_INDEX_DB_USER" -v ai_password="$AI_LAYER_INDEX_DB_PASSWORD" \
  <<'EOSQL'
-- pgvector lives in "public" so both roles can use the vector type.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE ROLE :"backend_user" LOGIN PASSWORD :'backend_password';
CREATE ROLE :"ai_user" LOGIN PASSWORD :'ai_password';

-- Nobody connects unless granted. TEMPORARY lets the seed migration use temp tables.
REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db_name" TO :"backend_user";
GRANT CONNECT ON DATABASE :"db_name" TO :"ai_user";

-- Nobody creates objects in "public"; usage of the vector type stays available.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA school AUTHORIZATION :"backend_user";
REVOKE ALL ON SCHEMA school FROM PUBLIC;

CREATE SCHEMA ai_layer AUTHORIZATION :"ai_user";
REVOKE ALL ON SCHEMA ai_layer FROM PUBLIC;

ALTER ROLE :"backend_user" SET search_path = school, public;
ALTER ROLE :"ai_user" SET search_path = ai_layer, public;
EOSQL

echo "initdb: roles '$BACKEND_DB_USER' (schema school) and '$AI_LAYER_INDEX_DB_USER' (schema ai_layer) created"
