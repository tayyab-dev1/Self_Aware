"""Print how similar every pair of capability descriptions is, most similar first.

    uv run python -m app.capabilities        (or: make descriptions-report)

For writing descriptions: a pair near the 0.92 line is one retrieval will confuse. Needs the
embeddings service (`make embeddings-up`).
"""

from __future__ import annotations

import asyncio

import structlog

from app.capabilities.similarity import (
    NEAR_DUPLICATE_THRESHOLD,
    description_similarities,
    near_duplicates,
)
from app.capabilities.snapshot import SNAPSHOT_PATH, load_snapshot
from app.core.logging import configure_logging
from app.core.settings import get_settings
from app.embeddings.client import EmbeddingsClient


async def _report() -> int:
    settings = get_settings()
    configure_logging(settings.log_level, settings.log_format)
    log = structlog.get_logger("app.capabilities")

    capabilities = load_snapshot().capabilities
    client = EmbeddingsClient.from_settings(settings)
    try:
        await client.require_expected_model()
        pairs = await description_similarities(capabilities, client)
    finally:
        await client.aclose()

    log.info(
        "description_similarity_report", snapshot=str(SNAPSHOT_PATH), capabilities=len(capabilities)
    )
    for pair in pairs:
        log.info(
            "pair",
            similarity=round(pair.similarity, 3),
            first=pair.first,
            second=pair.second,
            siblings=pair.siblings,
        )
    offenders = near_duplicates(pairs)
    if offenders:
        log.error(
            "near_duplicate_descriptions",
            threshold=NEAR_DUPLICATE_THRESHOLD,
            pairs=[pair.describe() for pair in offenders],
        )
        return 1
    log.info("no_near_duplicate_descriptions", threshold=NEAR_DUPLICATE_THRESHOLD)
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(_report()))
