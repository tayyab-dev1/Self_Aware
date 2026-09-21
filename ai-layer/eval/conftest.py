"""Fixtures for evaluation runs: the throwaway Postgres from the integration tests, empty
capability indexes that live for a whole eval module, and the stress index the measurements use."""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator, Awaitable, Callable

import pytest_asyncio

from app.core.settings import Settings
from app.embeddings.client import EmbeddingsClient
from app.index.database import IndexDatabase
from app.retrieval.hybrid import HybridRetriever
from eval.retrieval.dataset import load_distractors
from eval.retrieval.embedding_cache import CachingEmbedder
from eval.retrieval.harness import add_distractors, fill_poc_index
from tests.integration.conftest import PostgresServer, postgres_server

__all__ = ["postgres_server"]

IndexFactory = Callable[[], Awaitable[IndexDatabase]]
# The retriever over the POC's capabilities plus the distractors, the ids it holds, its embedder.
StressIndex = tuple[HybridRetriever, list[str], CachingEmbedder]


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


@pytest_asyncio.fixture(scope="module")
async def stress(
    new_index: IndexFactory,
) -> AsyncIterator[StressIndex]:
    client = EmbeddingsClient.from_settings(Settings())
    try:
        embedder = CachingEmbedder(client)
        await embedder.require_expected_model()
        index = await new_index()
        await fill_poc_index(index, embedder)
        await add_distractors(index, embedder, load_distractors())
        yield HybridRetriever(index, embedder), sorted(await index.indexed_versions()), embedder
    finally:
        await client.aclose()
