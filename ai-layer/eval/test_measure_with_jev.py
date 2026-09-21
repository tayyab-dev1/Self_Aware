"""The chooser experiment: every eval sentence planned without and with Jev choosing first.

    make measure-jev   records any answer not yet in eval/recordings/ (Jev's in choose.json, the
                       planner's after Jev in plan_after_choose.json), then compares the two runs

``make measure-ci`` replays it with the rest. It checks no baseline: it is an experiment, and
eval/reports/measure_with_jev.md is its result. It does check what must hold either way: every
sentence got an outcome, and the validator still catches every injected fault.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any

import pytest
import pytest_asyncio

from app.capabilities.snapshot import load_snapshot
from app.choosing.chooser import CHOOSER_MODEL, SPECIFICATION
from app.choosing.jev import DecisionModel, DecisionRequest, JevModel
from app.core.settings import Settings
from app.llm.runner import PLANNER_MODEL
from app.planning.planner import system_prompt as planner_prompt
from eval.conftest import StressIndex
from eval.measure.choose_report import comparison_table, write_choose_report
from eval.measure.faults import FaultTally, inject_faults
from eval.measure.pipeline import MAX_STEPS, CaseResult, Pipeline, load_cases
from eval.measure.recordings import Recordings, mode_from_environment
from eval.measure.scores import FourNumbers, four_numbers
from eval.test_measure import model_or_none, recordings

pytestmark = [pytest.mark.integration, pytest.mark.embeddings]


class TimedDecider:
    """Keeps how long each live call took in its recorded answer, for the report."""

    def __init__(self, model: DecisionModel) -> None:
        self._model = model

    async def decide(self, request: DecisionRequest) -> dict[str, Any]:
        started = time.monotonic()
        answer = await self._model.decide(request)
        return {**answer, "seconds": round(time.monotonic() - started, 3)}


@dataclass(frozen=True, slots=True)
class Comparison:
    before: list[CaseResult]
    after: list[CaseResult]
    numbers_before: dict[str, FourNumbers]
    numbers_after: dict[str, FourNumbers]
    faults_after: dict[str, dict[str, FaultTally]]


@pytest_asyncio.fixture(scope="module")
async def comparison(stress: StressIndex) -> Comparison:
    retriever, index_ids, _ = stress
    catalog = {capability.id: capability for capability in load_snapshot().capabilities}
    mode = mode_from_environment()
    decompose_recordings, plan_recordings = recordings()
    choose_recordings = Recordings("choose", CHOOSER_MODEL, SPECIFICATION, mode=mode)
    plan_after_recordings = Recordings(
        "plan_after_choose", PLANNER_MODEL, planner_prompt(MAX_STEPS), mode=mode
    )
    model = model_or_none()
    jev = JevModel.from_settings(Settings()) if mode == "record" else None
    try:
        cases = load_cases()
        before = await Pipeline(
            retriever, index_ids, catalog, decompose_recordings, plan_recordings, model
        ).run_all(cases)
        after = await Pipeline(
            retriever,
            index_ids,
            catalog,
            decompose_recordings,
            plan_after_recordings,
            model,
            choose_recordings,
            TimedDecider(jev) if jev is not None else None,
        ).run_all(cases)
    finally:
        if jev is not None:
            await jev.aclose()

    numbers_before = four_numbers(before, inject_faults(before, catalog))
    faults_after = inject_faults(after, catalog)
    numbers_after = four_numbers(after, faults_after)
    seconds = [
        float(answer["seconds"])
        for case in cases
        if (answer := choose_recordings.recorded(case.text)) and "seconds" in answer
    ]
    write_choose_report(before, after, numbers_before, numbers_after, seconds)
    return Comparison(before, after, numbers_before, numbers_after, faults_after)


def test_the_comparison_prints_and_every_sentence_got_an_outcome(
    comparison: Comparison, capsys: pytest.CaptureFixture[str]
) -> None:
    with capsys.disabled():
        print("\n\nPlanner alone -> Jev, then the planner\n")  # noqa: T201
        print(comparison_table(comparison.numbers_before, comparison.numbers_after))  # noqa: T201
        print("\nDetail: eval/reports/measure_with_jev.md\n")  # noqa: T201

    assert len(comparison.after) == len(load_cases())
    assert not [r.case.text for r in comparison.after if r.outcome == "failed"]


def test_the_validator_still_catches_every_injected_fault(comparison: Comparison) -> None:
    escaped = {n: t.escaped for n, t in comparison.faults_after["all"].items() if t.escaped}
    assert not escaped
