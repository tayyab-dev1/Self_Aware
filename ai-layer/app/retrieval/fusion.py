"""Reciprocal rank fusion and sibling expansion: the two pure steps of hybrid retrieval."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass, replace
from typing import Literal

RRF_K = 60

Branch = Literal["dense", "lexical"]


@dataclass(frozen=True, slots=True)
class Ranking:
    """One ranked list: a branch's answer for one query text, best first."""

    branch: Branch
    capability_ids: Sequence[str]


@dataclass(frozen=True, slots=True)
class Candidate:
    """A capability offered to the planner, and why it is there.

    ``score`` is the fused score (0 when it appeared in no ranked list). The ranks are the best
    1-based position it reached in any dense or lexical list, if any. ``sibling_of`` names the
    higher-ranked capability that pulled it in ahead of its own turn, if one did.
    """

    capability_id: str
    score: float
    dense_rank: int | None = None
    lexical_rank: int | None = None
    sibling_of: str | None = None


def fuse(rankings: Sequence[Ranking], k: int = RRF_K) -> list[Candidate]:
    """Merge ranked lists: ``score = sum(1 / (k + rank))``. Highest first, ties by id."""
    scores: dict[str, float] = {}
    best: dict[tuple[str, Branch], int] = {}
    for ranking in rankings:
        for position, capability_id in enumerate(ranking.capability_ids, start=1):
            scores[capability_id] = scores.get(capability_id, 0.0) + 1.0 / (k + position)
            key = (capability_id, ranking.branch)
            best[key] = min(best.get(key, position), position)
    return [
        Candidate(
            capability_id=capability_id,
            score=score,
            dense_rank=best.get((capability_id, "dense")),
            lexical_rank=best.get((capability_id, "lexical")),
        )
        for capability_id, score in sorted(scores.items(), key=lambda item: (-item[1], item[0]))
    ]


def expand_with_siblings(
    ranked: Sequence[Candidate], siblings: Mapping[str, Sequence[str]], cap: int
) -> list[Candidate]:
    """Take candidates in rank order, each followed by its declared siblings, up to ``cap``.

    A capability and its siblings go in together or not at all, so a confusable cluster is never
    cut in half: the planner sees every option it must choose between. ``siblings`` holds only
    capabilities the user may use and the index holds, so expansion cannot widen the allow-list.
    Stops at the first group that does not fit, so a lower-ranked capability never takes the place
    of a higher-ranked group. A single group larger than ``cap`` is cut to ``cap``.
    """
    scored = {candidate.capability_id: candidate for candidate in ranked}
    chosen: list[Candidate] = []
    seen: set[str] = set()
    for candidate in ranked:
        if candidate.capability_id in seen or candidate.capability_id not in siblings:
            continue
        group = [candidate] + [
            replace(
                scored.get(sibling, Candidate(capability_id=sibling, score=0.0)),
                sibling_of=candidate.capability_id,
            )
            for sibling in dict.fromkeys(siblings[candidate.capability_id])
            if sibling in siblings and sibling not in seen and sibling != candidate.capability_id
        ]
        if len(chosen) + len(group) > cap:
            if not chosen:
                chosen = group[:cap]
            break
        chosen.extend(group)
        seen.update(member.capability_id for member in group)
    return chosen
