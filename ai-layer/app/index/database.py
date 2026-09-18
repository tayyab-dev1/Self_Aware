"""Connection pool for the capability index.

Connections are borrowed and returned inside each method and never handed out, so no code
path can hold one across a slow model call (CLAUDE.md invariant 10).
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Self

import asyncpg

from app.core.settings import Settings
from app.index.migrations import MIGRATIONS_DIR, apply_migrations, quote_schema

INDEX_SCHEMA = "ai_layer"


@dataclass(frozen=True, slots=True)
class IndexProbe:
    """What a readiness check learns about the index database."""

    vector_literal: str
    capability_index_present: bool
    applied_migrations: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class IndexedVersion:
    """What the index holds for one capability: enough to tell whether it must be re-embedded."""

    version: str
    embedding_model: str


@dataclass(frozen=True, slots=True)
class CapabilityRow:
    """One row of the capability index, as metadata sync writes it."""

    capability_id: str
    content: str
    module: str
    read_only: bool
    embedding: Sequence[float]
    version: str
    disambiguate_from: Sequence[str]
    embedding_model: str


# The lexical branch matches ANY word of the query, not all of them: a sentence rarely repeats a
# description's words, so AND would find almost nothing. plainto_tsquery joins words with "&";
# swapping those for "|" keeps its stemming and stop words. The rank is not divided by document
# length: that rewards terse descriptions over ones that also say what they are not for, and cost
# 4 points of recall@30 in the stress eval (README decision 39).
_LEXICAL_RANKING = """
WITH q AS (SELECT replace(plainto_tsquery('english', $1)::text, ' & ', ' | ')::tsquery AS query)
SELECT capability_id
FROM capability_index, q
WHERE capability_id = ANY($2::text[]) AND tsv @@ q.query
ORDER BY ts_rank(tsv, q.query) DESC, capability_id
LIMIT $3
"""

_DENSE_RANKING = """
SELECT capability_id
FROM capability_index
WHERE capability_id = ANY($2::text[]) AND embedding IS NOT NULL AND embedding_model = $3
ORDER BY embedding <=> $1::vector, capability_id
LIMIT $4
"""

_UPSERT_ROW = """
INSERT INTO capability_index (capability_id, content, module, read_only, embedding, version,
                              disambiguate_from, embedding_model, synced_at)
VALUES ($1, $2, $3, $4, $5::vector, $6, $7::text[], $8, now())
ON CONFLICT (capability_id) DO UPDATE SET
    content = EXCLUDED.content,
    module = EXCLUDED.module,
    read_only = EXCLUDED.read_only,
    embedding = EXCLUDED.embedding,
    version = EXCLUDED.version,
    disambiguate_from = EXCLUDED.disambiguate_from,
    embedding_model = EXCLUDED.embedding_model,
    synced_at = EXCLUDED.synced_at
"""


def vector_literal(vector: Sequence[float]) -> str:
    """pgvector's text form, bound as a parameter and cast in SQL (asyncpg has no vector codec)."""
    return "[" + ",".join(repr(float(value)) for value in vector) + "]"


class IndexDatabase:
    """The AI layer's one and only database handle."""

    def __init__(self, pool: asyncpg.Pool, schema: str = INDEX_SCHEMA) -> None:
        self._pool = pool
        self._schema = schema

    @classmethod
    async def connect(cls, settings: Settings, *, schema: str = INDEX_SCHEMA) -> Self:
        pool = await asyncpg.create_pool(
            host=settings.index_db_host,
            port=settings.index_db_port,
            database=settings.index_db_name,
            user=settings.index_db_user,
            password=settings.index_db_password.get_secret_value(),
            min_size=settings.index_db_pool_min_size,
            max_size=settings.index_db_pool_max_size,
            server_settings={
                "search_path": f"{quote_schema(schema)}, public",
                "application_name": "ai-layer",
            },
        )
        return cls(pool, schema)

    @property
    def schema(self) -> str:
        return self._schema

    async def apply_migrations(self, directory: Path = MIGRATIONS_DIR) -> list[str]:
        """Apply pending plain-SQL migrations. Returns the versions applied now."""
        async with self._pool.acquire() as connection:
            return await apply_migrations(connection, schema=self._schema, directory=directory)

    async def probe(self) -> IndexProbe:
        """Check pgvector works, the index table exists, and which migrations have run."""
        async with self._pool.acquire() as connection:
            vector_literal: str = await connection.fetchval("SELECT '[1,2,3]'::vector::text")
            table = await connection.fetchval(
                "SELECT to_regclass($1)::text", f"{self._schema}.capability_index"
            )
            history = await connection.fetchval(
                "SELECT to_regclass($1)::text", f"{self._schema}.schema_migrations"
            )
            versions: list[str] = (
                [
                    row["version"]
                    for row in await connection.fetch(
                        "SELECT version FROM schema_migrations ORDER BY version"
                    )
                ]
                if history is not None
                else []
            )
        return IndexProbe(
            vector_literal=vector_literal,
            capability_index_present=table is not None,
            applied_migrations=tuple(versions),
        )

    async def indexed_versions(self) -> dict[str, IndexedVersion]:
        """Every capability in the index, with the version and model its row was written from."""
        async with self._pool.acquire() as connection:
            rows = await connection.fetch(
                "SELECT capability_id, version, embedding_model FROM capability_index"
            )
        return {
            row["capability_id"]: IndexedVersion(row["version"], row["embedding_model"])
            for row in rows
        }

    async def apply_sync(self, upserts: Sequence[CapabilityRow], removals: Sequence[str]) -> None:
        """Write changed rows and delete withdrawn ones, all or nothing."""
        async with self._pool.acquire() as connection, connection.transaction():
            if upserts:
                await connection.executemany(
                    _UPSERT_ROW,
                    [
                        (
                            row.capability_id,
                            row.content,
                            row.module,
                            row.read_only,
                            vector_literal(row.embedding),
                            row.version,
                            list(row.disambiguate_from),
                            row.embedding_model,
                        )
                        for row in upserts
                    ],
                )
            if removals:
                await connection.execute(
                    "DELETE FROM capability_index WHERE capability_id = ANY($1::text[])",
                    list(removals),
                )

    async def dense_ranking(
        self, vector: Sequence[float], allowed: Sequence[str], embedding_model: str, limit: int
    ) -> list[str]:
        """Allowed capabilities, closest description embedding first (exact cosine scan)."""
        async with self._pool.acquire() as connection:
            rows = await connection.fetch(
                _DENSE_RANKING, vector_literal(vector), list(allowed), embedding_model, limit
            )
        return [row["capability_id"] for row in rows]

    async def lexical_ranking(self, text: str, allowed: Sequence[str], limit: int) -> list[str]:
        """Allowed capabilities sharing any word with the text, best full-text rank first."""
        async with self._pool.acquire() as connection:
            rows = await connection.fetch(_LEXICAL_RANKING, text, list(allowed), limit)
        return [row["capability_id"] for row in rows]

    async def siblings(self, allowed: Sequence[str]) -> Mapping[str, tuple[str, ...]]:
        """Declared siblings of every allowed capability in the index."""
        async with self._pool.acquire() as connection:
            rows = await connection.fetch(
                "SELECT capability_id, disambiguate_from FROM capability_index "
                "WHERE capability_id = ANY($1::text[])",
                list(allowed),
            )
        return {row["capability_id"]: tuple(row["disambiguate_from"]) for row in rows}

    async def close(self) -> None:
        await self._pool.close()
