"""Runs the retrieval eval: build an index, retrieve for every labelled sentence, score it.

Two indexes are measured with the same code the service runs:

  POC index     the published capabilities, filled by the real metadata sync from the snapshot
  stress index  the same, plus every other planning-contract intent as a distractor, so the
                labelled capabilities compete with roughly the number a full product would index

Every sentence is retrieved as typed. From Phase 5 retrieval gets decompose's English intents
instead (CLAUDE.md invariant 11), so each Roman Urdu sentence is also retrieved through its English
gloss, standing in for those intents. The stress gate is measured in English for that reason.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

from app.capabilities.snapshot import load_snapshot
from app.gateway.models import (
    AgentMetadataResponse,
    CapabilityMetadata,
    CapabilityVersion,
    CapabilityVersionsResponse,
)
from app.index.database import CapabilityRow, IndexDatabase
from app.retrieval.hybrid import HybridRetriever
from app.sync.metadata_sync import EMBEDDING_MODEL, MetadataSync
from eval.retrieval.dataset import Distractor, EvalSentence
from eval.retrieval.embedding_cache import CachingEmbedder
from eval.retrieval.metrics import Outcome


class SnapshotSource:
    """Serves the committed metadata snapshot as if it were the backend."""

    def __init__(self, metadata: AgentMetadataResponse) -> None:
        self._metadata = metadata

    async def get_versions(self) -> CapabilityVersionsResponse:
        return CapabilityVersionsResponse(
            versions=[
                CapabilityVersion(id=capability.id, version=capability.version)
                for capability in self._metadata.capabilities
            ]
        )

    async def get_metadata(self) -> AgentMetadataResponse:
        return self._metadata


async def fill_poc_index(index: IndexDatabase, embedder: CachingEmbedder) -> list[str]:
    """Sync the snapshot into an empty index. Returns the capability ids added."""
    sync = MetadataSync(SnapshotSource(load_snapshot()), index, embedder)
    report = await sync.sync_once()
    return list(report.added)


async def add_distractors(
    index: IndexDatabase, embedder: CachingEmbedder, distractors: Sequence[Distractor]
) -> None:
    vectors = await embedder.embed([distractor.content for distractor in distractors])
    await index.apply_sync(
        [
            CapabilityRow(
                capability_id=distractor.id,
                content=distractor.content,
                module=distractor.module,
                read_only=False,
                embedding=vector,
                version="distractor",
                disambiguate_from=(),
                embedding_model=EMBEDDING_MODEL,
            )
            for distractor, vector in zip(distractors, vectors, strict=True)
        ],
        [],
    )


@dataclass(frozen=True, slots=True)
class IndexRun:
    name: str
    size: int
    typed: tuple[Outcome, ...]
    glossed: tuple[Outcome, ...]

    @property
    def in_english(self) -> tuple[Outcome, ...]:
        """Every sentence in English: English ones as typed, Roman Urdu through its gloss."""
        return tuple(o for o in self.typed if o.sentence.lang == "en") + self.glossed


async def run(
    name: str,
    index: IndexDatabase,
    embedder: CachingEmbedder,
    sentences: Sequence[EvalSentence],
    *,
    cap: int,
) -> IndexRun:
    allowed = sorted(await index.indexed_versions())
    retriever = HybridRetriever(index, embedder, cap=cap)
    await embedder.embed(
        [s.text for s in sentences] + [s.gloss for s in sentences if s.gloss is not None]
    )

    async def outcome(sentence: EvalSentence, query: str) -> Outcome:
        return await retrieve_for(retriever, allowed, sentence, [query])

    typed = [await outcome(sentence, sentence.text) for sentence in sentences]
    glossed = [
        await outcome(sentence, sentence.gloss)
        for sentence in sentences
        if sentence.lang == "ur-Latn" and sentence.gloss is not None
    ]
    return IndexRun(name=name, size=len(allowed), typed=tuple(typed), glossed=tuple(glossed))


async def retrieve_for(
    retriever: HybridRetriever, allowed: Sequence[str], sentence: EvalSentence, queries: list[str]
) -> Outcome:
    """Retrieve with these query texts; an empty list (decompose failed) retrieves nothing."""
    if not queries:
        return Outcome(sentence=sentence, query="", candidates=(), fused_rank=None, top=())
    result = await retriever.retrieve(queries, allowed)
    return Outcome(
        sentence=sentence,
        query=" | ".join(queries),
        candidates=tuple(result.capability_ids),
        fused_rank=result.fused_rank_of(sentence.expected),
        top=tuple(candidate.capability_id for candidate in result.ranked[:3]),
    )


def sibling_map(capabilities: Sequence[CapabilityMetadata]) -> dict[str, tuple[str, ...]]:
    return {capability.id: tuple(capability.disambiguate_from) for capability in capabilities}
