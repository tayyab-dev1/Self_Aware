"""Hybrid retrieval: dense and lexical search, fused, expanded with siblings, capped.

For each query text (the English intents from decompose, from Phase 5 on) it runs two searches
over the allowed capabilities: embedding similarity (pgvector) and full-text match (Postgres). The
ranked lists are merged with reciprocal rank fusion, each capability brings its declared siblings
with it, and the result is capped. Every query is embedded before the index is touched.

Retrieval only narrows the choice. It never decides: the planner picks from these candidates, and
the backend checks whatever it picks.
"""

from __future__ import annotations

from collections.abc import Collection, Mapping, Sequence
from dataclasses import dataclass
from typing import Protocol

from app.core import trace
from app.retrieval.fusion import RRF_K, Candidate, Ranking, expand_with_siblings, fuse
from app.sync.metadata_sync import EMBEDDING_MODEL

CANDIDATE_CAP = 30
# How deep each branch looks. Deeper than the cap, so a capability ranked moderately by both
# branches can still fuse into the top.
BRANCH_LIMIT = 50


class QueryEmbedder(Protocol):
    async def embed(self, texts: Sequence[str]) -> list[list[float]]: ...


class CandidateIndex(Protocol):
    async def dense_ranking(
        self, vector: Sequence[float], allowed: Sequence[str], embedding_model: str, limit: int
    ) -> list[str]: ...

    async def lexical_ranking(self, text: str, allowed: Sequence[str], limit: int) -> list[str]: ...

    async def siblings(self, allowed: Sequence[str]) -> Mapping[str, tuple[str, ...]]: ...


@dataclass(frozen=True, slots=True)
class RetrievalResult:
    """``candidates`` is what the planner sees: capped, each followed by its siblings.

    ``ranked`` is the fused order before expansion and cap, for measuring ranking quality.
    """

    candidates: tuple[Candidate, ...]
    ranked: tuple[Candidate, ...] = ()

    @property
    def capability_ids(self) -> list[str]:
        return [candidate.capability_id for candidate in self.candidates]

    def fused_rank_of(self, capability_id: str) -> int | None:
        """1-based position in the fused ranking, or None when no search found it."""
        for position, candidate in enumerate(self.ranked, start=1):
            if candidate.capability_id == capability_id:
                return position
        return None


class HybridRetriever:
    def __init__(
        self,
        index: CandidateIndex,
        embedder: QueryEmbedder,
        *,
        cap: int = CANDIDATE_CAP,
        rrf_k: int = RRF_K,
        branch_limit: int = BRANCH_LIMIT,
    ) -> None:
        if cap < 1 or branch_limit < 1 or rrf_k < 1:
            raise ValueError("cap, branch_limit and rrf_k must be positive")
        self._index = index
        self._embedder = embedder
        self._cap = cap
        self._rrf_k = rrf_k
        self._branch_limit = branch_limit

    async def retrieve(self, queries: Sequence[str], allowed: Collection[str]) -> RetrievalResult:
        """Candidates for these query texts, drawn only from ``allowed``.

        ``allowed`` is the user's allow-list from the backend. It is required: an empty one
        retrieves nothing, rather than everything.
        """
        texts = [query.strip() for query in queries if query.strip()]
        if not texts:
            raise ValueError("retrieve needs at least one non-blank query")
        allow_list = sorted(set(allowed))
        if not allow_list:
            return RetrievalResult(candidates=())

        with trace.stage(
            "embed",
            "Embed the intents",
            trace.BY_EMBEDDINGS,
            f"Turns each intent into a meaning vector with {EMBEDDING_MODEL}, "
            "so it can be compared with the capability descriptions.",
        ) as stage:
            stage.input = {"intents": texts}
            vectors = await self._embedder.embed(texts)
            stage.output = [
                {
                    "intent": text,
                    "dimensions": len(vector),
                    "first_values": [round(v, 4) for v in vector[:6]],
                }
                for text, vector in zip(texts, vectors, strict=True)
            ]

        with trace.stage(
            "search",
            "Search the capability index",
            trace.BY_EMBEDDINGS,
            "Runs two searches per intent over only the capabilities this user may use: by meaning "
            "(pgvector) and by words (Postgres full-text).",
        ) as stage:
            stage.input = {"intents": texts, "allowed_capabilities": allow_list}
            rankings: list[Ranking] = []
            for text, vector in zip(texts, vectors, strict=True):
                dense = await self._index.dense_ranking(
                    vector, allow_list, EMBEDDING_MODEL, self._branch_limit
                )
                lexical = await self._index.lexical_ranking(text, allow_list, self._branch_limit)
                rankings += [Ranking("dense", dense), Ranking("lexical", lexical)]
            stage.output = [
                {
                    "intent": text,
                    "by_meaning": list(rankings[2 * n].capability_ids),
                    "by_words": list(rankings[2 * n + 1].capability_ids),
                }
                for n, text in enumerate(texts)
            ]

        with trace.stage(
            "fuse",
            "Fuse, add siblings, cap",
            trace.BY_CODE,
            f"Merges the ranked lists with reciprocal rank fusion (k={self._rrf_k}), brings in "
            "each capability's easily confused siblings so the planner sees them together, and "
            f"keeps at most {self._cap}.",
        ) as stage:
            stage.input = {"ranked_lists": len(rankings)}
            siblings = await self._index.siblings(allow_list)
            ranked = fuse(rankings, self._rrf_k)
            candidates = expand_with_siblings(ranked, siblings, self._cap)
            stage.output = {
                "fused": [
                    {
                        "capability": c.capability_id,
                        "score": round(c.score, 4),
                        "meaning_rank": c.dense_rank,
                        "words_rank": c.lexical_rank,
                    }
                    for c in ranked
                ],
                "candidates_for_the_planner": [
                    {"capability": c.capability_id, "added_as_sibling_of": c.sibling_of}
                    for c in candidates
                ],
            }
        return RetrievalResult(candidates=tuple(candidates), ranked=tuple(ranked))
