"""Build-time assertion 5: no two descriptions embed above 0.92 cosine unless declared siblings.

Two descriptions that close together look the same to retrieval, so one can crowd the other out of
the candidate list. Siblings are fine, because retrieval always pulls a capability's siblings in
with it. Anything else that close needs its description rewritten, or a sibling declaration.
"""

from __future__ import annotations

import itertools
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol

from app.embeddings.client import cosine_similarity
from app.gateway.models import CapabilityMetadata

NEAR_DUPLICATE_THRESHOLD = 0.92


class Embedder(Protocol):
    async def embed(self, texts: Sequence[str]) -> list[list[float]]: ...


@dataclass(frozen=True, slots=True)
class SimilarPair:
    first: str
    second: str
    similarity: float
    siblings: bool

    def describe(self) -> str:
        relation = "siblings" if self.siblings else "not siblings"
        return f"{self.similarity:.3f}  {self.first} ~ {self.second}  ({relation})"


def pairwise_similarities(
    capabilities: Sequence[CapabilityMetadata], vectors: Sequence[Sequence[float]]
) -> list[SimilarPair]:
    """Every pair of capabilities with its description similarity, most similar first."""
    if len(capabilities) != len(vectors):
        raise ValueError(f"{len(capabilities)} capabilities but {len(vectors)} vectors")
    pairs = [
        SimilarPair(
            first=first.id,
            second=second.id,
            similarity=cosine_similarity(first_vector, second_vector),
            siblings=second.id in first.disambiguate_from or first.id in second.disambiguate_from,
        )
        for (first, first_vector), (second, second_vector) in itertools.combinations(
            zip(capabilities, vectors, strict=True), 2
        )
    ]
    return sorted(pairs, key=lambda pair: pair.similarity, reverse=True)


def near_duplicates(
    pairs: Sequence[SimilarPair], threshold: float = NEAR_DUPLICATE_THRESHOLD
) -> list[SimilarPair]:
    """The pairs that break the rule: above the threshold and not declared siblings."""
    return [pair for pair in pairs if pair.similarity > threshold and not pair.siblings]


async def description_similarities(
    capabilities: Sequence[CapabilityMetadata], embedder: Embedder
) -> list[SimilarPair]:
    vectors = await embedder.embed([capability.description for capability in capabilities])
    return pairwise_similarities(capabilities, vectors)
