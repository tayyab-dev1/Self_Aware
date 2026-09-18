"""Show which capabilities retrieval offers for a sentence, and why.

    uv run python -m app.retrieval "remind class 5 blue about fees"     (or: make retrieve Q="...")

Reads the capability index as metadata sync last left it (the AI layer must have run once) and
needs the embeddings service. A developer tool: it has no user, so it treats every indexed
capability as allowed. The sentence is shown only on this terminal, never logged elsewhere.
"""

from __future__ import annotations

import asyncio
import sys

import structlog

from app.core.logging import configure_logging
from app.core.settings import get_settings
from app.embeddings.client import EmbeddingsClient
from app.index.database import IndexDatabase
from app.retrieval.hybrid import HybridRetriever


async def _retrieve(sentences: list[str]) -> int:
    settings = get_settings()
    configure_logging(settings.log_level, settings.log_format)
    log = structlog.get_logger("app.retrieval")
    if not sentences:
        log.error("usage", example='python -m app.retrieval "remind class 5 blue about fees"')
        return 2

    index = await IndexDatabase.connect(settings)
    embeddings = EmbeddingsClient.from_settings(settings)
    try:
        await embeddings.require_expected_model()
        allowed = list(await index.indexed_versions())
        retriever = HybridRetriever(
            index,
            embeddings,
            cap=settings.retrieval_candidate_cap,
            rrf_k=settings.retrieval_rrf_k,
            branch_limit=settings.retrieval_branch_limit,
        )
        result = await retriever.retrieve(sentences, allowed)
    finally:
        await embeddings.aclose()
        await index.close()

    log.info("retrieval", indexed=len(allowed), candidates=len(result.candidates))
    for position, candidate in enumerate(result.candidates, start=1):
        log.info(
            "candidate",
            rank=position,
            capability_id=candidate.capability_id,
            score=round(candidate.score, 4),
            dense_rank=candidate.dense_rank,
            lexical_rank=candidate.lexical_rank,
            sibling_of=candidate.sibling_of,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(_retrieve(sys.argv[1:])))
