"""The four numbers, per language."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import asdict, dataclass

from eval.measure.faults import FaultTally
from eval.measure.pipeline import CaseResult

GROUPS = ("all", "en", "ur-Latn")


@dataclass(frozen=True, slots=True)
class FourNumbers:
    recall_at_30: float
    plan_accuracy: float
    refusal_correctness: float
    validator_catch_rate: float
    # what each number is out of
    sentences: int
    refusal_cases: int
    faults_applied: int

    def as_dict(self) -> dict[str, float | int]:
        return asdict(self)


def four_numbers(
    results: Sequence[CaseResult], faults: Mapping[str, Mapping[str, FaultTally]]
) -> dict[str, FourNumbers]:
    numbers: dict[str, FourNumbers] = {}
    for group in GROUPS:
        chosen = [r for r in results if group == "all" or r.case.lang == group]
        positives = [r for r in chosen if r.case.expected is not None]
        tallies = faults.get(group, {}).values()
        applied = sum(t.applied for t in tallies)
        numbers[group] = FourNumbers(
            recall_at_30=_share(sum(r.retrieved for r in positives), len(positives)),
            plan_accuracy=_share(sum(r.planned_correctly for r in positives), len(positives)),
            refusal_correctness=_share(
                sum(r.refusal_decision_correct for r in chosen), len(chosen)
            ),
            validator_catch_rate=_share(sum(t.caught for t in tallies), applied),
            sentences=len(positives),
            refusal_cases=len(chosen) - len(positives),
            faults_applied=applied,
        )
    return numbers


def _share(part: int, whole: int) -> float:
    return part / whole if whole else 0.0
