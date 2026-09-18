from __future__ import annotations

from collections.abc import Mapping, Sequence

import pytest

from app.retrieval.fusion import Candidate, Ranking, expand_with_siblings, fuse
from app.retrieval.hybrid import HybridRetriever
from app.sync.metadata_sync import EMBEDDING_MODEL

CLUSTER = (
    "fee.cancellation.raise",
    "fee.credit.raise",
    "fee.latefee.waive",
    "fee.writeoff.propose",
)


def cluster_siblings(*others: str) -> dict[str, tuple[str, ...]]:
    siblings = {member: tuple(m for m in CLUSTER if m != member) for member in CLUSTER}
    siblings.update({other: () for other in others})
    return siblings


def ids(candidates: Sequence[Candidate]) -> list[str]:
    return [candidate.capability_id for candidate in candidates]


# --- fusion ----------------------------------------------------------------------------------


def test_fusion_adds_reciprocal_ranks_across_lists() -> None:
    fused = fuse([Ranking("dense", ["a", "b"]), Ranking("lexical", ["b", "c"])], k=60)

    assert ids(fused) == ["b", "a", "c"]
    assert fused[0].score == pytest.approx(1 / 62 + 1 / 61)
    assert (fused[0].dense_rank, fused[0].lexical_rank) == (2, 1)
    assert (fused[2].dense_rank, fused[2].lexical_rank) == (None, 2)


def test_fusion_keeps_the_best_rank_per_branch_across_queries() -> None:
    fused = fuse([Ranking("dense", ["x", "a"]), Ranking("dense", ["a"])])

    assert next(c for c in fused if c.capability_id == "a").dense_rank == 1


def test_fusion_breaks_ties_by_id() -> None:
    fused = fuse([Ranking("dense", ["b"]), Ranking("lexical", ["a"])])

    assert ids(fused) == ["a", "b"]


# --- sibling expansion -----------------------------------------------------------------------


def test_a_cluster_member_brings_every_sibling_straight_after_it() -> None:
    ranked = fuse([Ranking("dense", ["fee.overdue.list", "fee.credit.raise", "dashboard"])])

    chosen = expand_with_siblings(ranked, cluster_siblings("fee.overdue.list", "dashboard"), cap=30)

    assert ids(chosen) == [
        "fee.overdue.list",
        "fee.credit.raise",
        "fee.cancellation.raise",
        "fee.latefee.waive",
        "fee.writeoff.propose",
        "dashboard",
    ]
    assert chosen[2].sibling_of == "fee.credit.raise"
    assert chosen[2].score == 0.0


def test_a_sibling_that_also_scored_keeps_its_score_and_is_not_listed_twice() -> None:
    ranked = fuse([Ranking("dense", ["fee.credit.raise", "a", "fee.writeoff.propose"])])

    chosen = expand_with_siblings(ranked, cluster_siblings("a"), cap=30)

    assert ids(chosen).count("fee.writeoff.propose") == 1
    writeoff = next(c for c in chosen if c.capability_id == "fee.writeoff.propose")
    assert writeoff.dense_rank == 3
    assert writeoff.sibling_of == "fee.credit.raise"


def test_a_cluster_that_does_not_fit_under_the_cap_is_left_out_whole() -> None:
    ranked = fuse([Ranking("dense", ["a", "b", "fee.credit.raise", "c"])])

    chosen = expand_with_siblings(ranked, cluster_siblings("a", "b", "c"), cap=5)

    assert ids(chosen) == ["a", "b"]


def test_siblings_outside_the_allow_list_are_not_pulled_in() -> None:
    siblings: Mapping[str, tuple[str, ...]] = {
        "fee.credit.raise": ("fee.cancellation.raise", "fee.writeoff.propose"),
        "fee.cancellation.raise": ("fee.credit.raise",),
    }
    ranked = fuse([Ranking("dense", ["fee.credit.raise"])])

    assert ids(expand_with_siblings(ranked, siblings, cap=30)) == [
        "fee.credit.raise",
        "fee.cancellation.raise",
    ]


def test_the_cap_holds() -> None:
    names = [f"cap{n:02d}" for n in range(40)]
    ranked = fuse([Ranking("lexical", names)])

    assert len(expand_with_siblings(ranked, {name: () for name in names}, cap=30)) == 30


# --- the retriever ---------------------------------------------------------------------------


class RecordingIndex:
    def __init__(self, dense: list[str], lexical: list[str], siblings: dict[str, tuple[str, ...]]):
        self.dense, self.lexical, self._siblings = dense, lexical, siblings
        self.calls: list[tuple[str, object]] = []

    async def dense_ranking(
        self, vector: Sequence[float], allowed: Sequence[str], embedding_model: str, limit: int
    ) -> list[str]:
        self.calls.append(("dense", (tuple(vector), tuple(allowed), embedding_model, limit)))
        return [c for c in self.dense if c in allowed]

    async def lexical_ranking(self, text: str, allowed: Sequence[str], limit: int) -> list[str]:
        self.calls.append(("lexical", (text, tuple(allowed), limit)))
        return [c for c in self.lexical if c in allowed]

    async def siblings(self, allowed: Sequence[str]) -> Mapping[str, tuple[str, ...]]:
        self.calls.append(("siblings", tuple(allowed)))
        return {k: v for k, v in self._siblings.items() if k in allowed}


class RecordingEmbedder:
    def __init__(self, index: RecordingIndex) -> None:
        self.index = index

    async def embed(self, texts: Sequence[str]) -> list[list[float]]:
        assert self.index.calls == [], "queries must be embedded before the index is touched"
        return [[float(n)] for n, _ in enumerate(texts)]


async def test_retrieval_embeds_first_then_searches_only_the_allow_list() -> None:
    index = RecordingIndex(
        dense=["fee.overdue.list", "fee.credit.raise"],
        lexical=["fee.reminder.send"],
        siblings={**cluster_siblings("fee.overdue.list", "fee.reminder.send")},
    )
    retriever = HybridRetriever(index, RecordingEmbedder(index), cap=30, branch_limit=7)
    allowed = ["fee.reminder.send", "fee.overdue.list", "fee.credit.raise", "fee.writeoff.propose"]

    result = await retriever.retrieve(["remind class 5 blue"], allowed)

    assert result.capability_ids == [
        "fee.overdue.list",
        "fee.reminder.send",
        "fee.credit.raise",
        "fee.writeoff.propose",
    ]
    assert result.fused_rank_of("fee.credit.raise") == 3
    assert result.fused_rank_of("fee.writeoff.propose") is None
    assert index.calls[0] == ("dense", ((0.0,), tuple(sorted(allowed)), EMBEDDING_MODEL, 7))
    assert index.calls[1] == ("lexical", ("remind class 5 blue", tuple(sorted(allowed)), 7))


async def test_every_intent_is_searched_and_the_lists_are_fused() -> None:
    index = RecordingIndex(dense=["a"], lexical=["b"], siblings={"a": (), "b": ()})
    retriever = HybridRetriever(index, RecordingEmbedder(index))

    await retriever.retrieve(["first intent", " ", "second intent"], ["a", "b"])

    assert [call[0] for call in index.calls] == [
        "dense",
        "lexical",
        "dense",
        "lexical",
        "siblings",
    ]


async def test_an_empty_allow_list_retrieves_nothing_and_touches_nothing() -> None:
    index = RecordingIndex(dense=["a"], lexical=["a"], siblings={"a": ()})

    result = await HybridRetriever(index, RecordingEmbedder(index)).retrieve(["anything"], [])

    assert result.candidates == ()
    assert index.calls == []


async def test_a_blank_query_is_refused() -> None:
    index = RecordingIndex(dense=[], lexical=[], siblings={})

    with pytest.raises(ValueError, match="non-blank"):
        await HybridRetriever(index, RecordingEmbedder(index)).retrieve(["  "], ["a"])
