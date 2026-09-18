"""Scoring retrieval results against labels.

Two different positions matter, and they are measured separately:

  in the candidates   what the planner sees: the capped list with siblings. Recall@30 uses it,
                      because the planner can only pick what it was offered.
  fused rank          the order the two searches agreed on, before siblings were pulled in.
                      Recall@1/3/5 and MRR use it: they say how well search alone ranks.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass

from eval.retrieval.dataset import EvalSentence

TOP_KS = (1, 3, 5)


@dataclass(frozen=True, slots=True)
class Outcome:
    """What retrieval did with one query."""

    sentence: EvalSentence
    query: str
    candidates: tuple[str, ...]
    fused_rank: int | None
    top: tuple[str, ...]

    @property
    def retrieved(self) -> bool:
        return self.sentence.expected in self.candidates


@dataclass(frozen=True, slots=True)
class Scores:
    count: int
    recall_at_cap: float
    recall_at: Mapping[int, float]
    mrr: float


def score(outcomes: Sequence[Outcome]) -> Scores:
    if not outcomes:
        return Scores(count=0, recall_at_cap=0.0, recall_at={k: 0.0 for k in TOP_KS}, mrr=0.0)
    total = len(outcomes)
    return Scores(
        count=total,
        recall_at_cap=sum(outcome.retrieved for outcome in outcomes) / total,
        recall_at={
            k: sum(1 for o in outcomes if o.fused_rank is not None and o.fused_rank <= k) / total
            for k in TOP_KS
        },
        mrr=sum(1 / o.fused_rank for o in outcomes if o.fused_rank is not None) / total,
    )


def breakdown(outcomes: Sequence[Outcome], key: Callable[[Outcome], str]) -> dict[str, Scores]:
    groups: dict[str, list[Outcome]] = {}
    for outcome in outcomes:
        groups.setdefault(key(outcome), []).append(outcome)
    return {name: score(group) for name, group in sorted(groups.items())}


@dataclass(frozen=True, slots=True)
class ClusterCheck:
    """Whether confusable clusters arrive whole.

    ``touched`` counts queries whose candidates held at least one cluster member, and ``whole``
    those that then held every member of that member's cluster. The Phase 4 rule is that the two
    are equal.
    """

    touched: int
    whole: int
    broken: tuple[Outcome, ...]


def cluster_check(
    outcomes: Sequence[Outcome], siblings: Mapping[str, Sequence[str]]
) -> ClusterCheck:
    touched = whole = 0
    broken: list[Outcome] = []
    for outcome in outcomes:
        offered = set(outcome.candidates)
        members = [capability for capability in offered if siblings.get(capability)]
        if not members:
            continue
        touched += 1
        if all(set(siblings[member]) <= offered for member in members):
            whole += 1
        else:
            broken.append(outcome)
    return ClusterCheck(touched=touched, whole=whole, broken=tuple(broken))
