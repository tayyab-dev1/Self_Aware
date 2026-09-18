"""Build-time assertion 5 (CLAUDE.md): no two capability descriptions embed above 0.92 cosine
without being declared siblings.

Runs against the embeddings service (`make embeddings-up`) and the committed metadata snapshot,
which the backend's own build keeps current. It fails, rather than skips, when the service is down:
an unrun check is not a passing check.
"""

from __future__ import annotations

from collections.abc import AsyncIterator

import pytest_asyncio

from app.capabilities.similarity import (
    NEAR_DUPLICATE_THRESHOLD,
    description_similarities,
    near_duplicates,
)
from app.capabilities.snapshot import load_snapshot
from app.core.settings import Settings
from app.embeddings.client import EmbeddingsClient


@pytest_asyncio.fixture
async def embeddings() -> AsyncIterator[EmbeddingsClient]:
    client = EmbeddingsClient.from_settings(Settings())
    try:
        yield client
    finally:
        await client.aclose()


async def test_the_embeddings_service_runs_the_pinned_model(embeddings: EmbeddingsClient) -> None:
    await embeddings.require_expected_model()


async def test_no_two_descriptions_are_near_duplicates_unless_declared_siblings(
    embeddings: EmbeddingsClient,
) -> None:
    await embeddings.require_expected_model()
    capabilities = load_snapshot().capabilities

    pairs = await description_similarities(capabilities, embeddings)

    offenders = near_duplicates(pairs)
    closest = "\n".join(f"  {pair.describe()}" for pair in pairs[:10])
    assert not offenders, (
        f"Descriptions above {NEAR_DUPLICATE_THRESHOLD} that are not declared siblings:\n"
        + "\n".join(f"  {pair.describe()}" for pair in offenders)
        + "\nRewrite one description, or declare them siblings in disambiguateFrom (both ways)."
        + f"\nClosest pairs:\n{closest}"
    )
