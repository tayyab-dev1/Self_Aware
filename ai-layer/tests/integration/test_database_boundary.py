"""CLAUDE.md invariant 1, enforced by Postgres: the AI layer role cannot touch business data."""

from __future__ import annotations

import asyncpg
import pytest

from tests.integration.conftest import PostgresServer


async def test_the_ai_layer_role_can_use_the_vector_type(ai_connection: asyncpg.Connection) -> None:
    assert await ai_connection.fetchval("SELECT '[1,2,3]'::vector::text") == "[1,2,3]"


async def test_the_ai_layer_role_owns_only_its_own_schema(
    ai_connection: asyncpg.Connection, postgres_server: PostgresServer
) -> None:
    owners = dict(
        await ai_connection.fetch(
            """SELECT nspname, pg_get_userbyid(nspowner)
               FROM pg_namespace WHERE nspname IN ('ai_layer', 'school')"""
        )
    )

    assert owners == {"ai_layer": postgres_server.ai_user, "school": postgres_server.backend_user}
    assert await ai_connection.fetchval("SHOW search_path") == "ai_layer, public"


async def test_the_ai_layer_role_cannot_read_business_tables(
    superuser_connection: asyncpg.Connection,
    ai_connection: asyncpg.Connection,
    postgres_server: PostgresServer,
) -> None:
    # A business table exactly as the backend's Flyway migration would leave it.
    await superuser_connection.execute(
        "CREATE TABLE IF NOT EXISTS school.students (id bigint PRIMARY KEY, full_name text)"
    )
    await superuser_connection.execute(
        f'ALTER TABLE school.students OWNER TO "{postgres_server.backend_user}"'
    )

    with pytest.raises(asyncpg.InsufficientPrivilegeError):
        await ai_connection.fetch("SELECT full_name FROM school.students")


@pytest.mark.parametrize(
    "statement",
    ["CREATE TABLE school.planted (id int)", "CREATE TABLE public.planted (id int)"],
)
async def test_the_ai_layer_role_cannot_create_tables_outside_its_schema(
    ai_connection: asyncpg.Connection, statement: str
) -> None:
    with pytest.raises(asyncpg.InsufficientPrivilegeError):
        await ai_connection.execute(statement)
