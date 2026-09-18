from __future__ import annotations

import math
from collections.abc import Sequence

import pytest

from app.capabilities.similarity import (
    NEAR_DUPLICATE_THRESHOLD,
    SimilarPair,
    description_similarities,
    near_duplicates,
    pairwise_similarities,
)
from app.capabilities.snapshot import load_snapshot
from app.gateway.models import CapabilityMetadata


def capability(capability_id: str, siblings: Sequence[str] = ()) -> CapabilityMetadata:
    template = load_snapshot().capabilities[0]
    return template.model_copy(
        update={
            "id": capability_id,
            "disambiguate_from": list(siblings),
            "description": capability_id,
        }
    )


def unit(angle_degrees: float) -> list[float]:
    """A 2-D unit vector: the cosine of two of them is the cosine of the angle between."""
    radians = math.radians(angle_degrees)
    return [math.cos(radians), math.sin(radians)]


def test_the_threshold_is_the_one_claude_md_sets() -> None:
    assert NEAR_DUPLICATE_THRESHOLD == 0.92


def test_pairs_are_sorted_most_similar_first_and_marked_as_siblings_or_not() -> None:
    capabilities = [
        capability("a.b.c", ["a.b.d"]),
        capability("a.b.d", ["a.b.c"]),
        capability("x.y.z"),
    ]
    vectors = [unit(0), unit(10), unit(80)]

    pairs = pairwise_similarities(capabilities, vectors)

    assert [(pair.first, pair.second, pair.siblings) for pair in pairs] == [
        ("a.b.c", "a.b.d", True),
        ("a.b.d", "x.y.z", False),
        ("a.b.c", "x.y.z", False),
    ]
    assert pairs[0].similarity == pytest.approx(math.cos(math.radians(10)))


def test_near_duplicates_are_close_pairs_that_are_not_siblings() -> None:
    pairs = [
        SimilarPair("a.b.c", "a.b.d", 0.97, siblings=True),
        SimilarPair("a.b.c", "x.y.z", 0.95, siblings=False),
        SimilarPair("a.b.d", "x.y.z", 0.92, siblings=False),
        SimilarPair("p.q.r", "x.y.z", 0.40, siblings=False),
    ]

    assert near_duplicates(pairs) == [SimilarPair("a.b.c", "x.y.z", 0.95, siblings=False)]


def test_a_mismatched_number_of_vectors_is_an_error() -> None:
    with pytest.raises(ValueError, match="2 capabilities but 1 vectors"):
        pairwise_similarities([capability("a.b.c"), capability("a.b.d")], [unit(0)])


async def test_descriptions_are_what_gets_embedded() -> None:
    class RecordingEmbedder:
        def __init__(self) -> None:
            self.texts: list[str] = []

        async def embed(self, texts: Sequence[str]) -> list[list[float]]:
            self.texts = list(texts)
            return [unit(index * 30) for index in range(len(texts))]

    embedder = RecordingEmbedder()
    capabilities = [capability("a.b.c"), capability("a.b.d")]

    pairs = await description_similarities(capabilities, embedder)

    assert embedder.texts == ["a.b.c", "a.b.d"]
    assert len(pairs) == 1
