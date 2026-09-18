"""Fixtures for evaluation runs: the throwaway Postgres from the integration tests, and empty
capability indexes that live for a whole eval module."""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator, Awaitable, Callable

import pytest_asyncio

from app.index.database import IndexDatabase
from tests.integration.conftest import PostgresServer, postgres_server

__all__ = ["postgres_server"]

IndexFactory = Callable[[], Awaitable[IndexDatabase]]


@pytest_asyncio.fixture(scope="module")
async def new_index(postgres_server: PostgresServer) -> AsyncIterator[IndexFactory]:
    """Opens migrated, empty capability indexes on fresh schemas; drops them afterwards."""
    superuser = await postgres_server.connect(
        postgres_server.superuser, postgres_server.superuser_password
    )
    opened: list[tuple[str, IndexDatabase]] = []

    async def open_index() -> IndexDatabase:
        schema = f"ai_eval_{uuid.uuid4().hex[:12]}"
        await superuser.execute(
            f'CREATE SCHEMA "{schema}" AUTHORIZATION "{postgres_server.ai_user}"'
        )
        database = await IndexDatabase.connect(postgres_server.ai_settings(), schema=schema)
        opened.append((schema, database))
        await database.apply_migrations()
        return database

    try:
        yield open_index
    finally:
        for schema, database in opened:
            await database.close()
            await superuser.execute(f'DROP SCHEMA "{schema}" CASCADE')
        await superuser.close()
