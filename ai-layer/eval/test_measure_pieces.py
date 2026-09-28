"""Planning from the pieces decompose read, against planning with a second model call.

    make measure-pieces   records what is missing (decompose with pieces in decompose_pieces.json,
                          Jev's readings of a user's words in fill.json, and the planner's answers
                          for what the pieces could not fill in plan_fallback.json)

Both designs run over the same cases: the planner path exactly as `make measure` runs it, and the
pieces path, which asks the planner only for what it cannot fill. Writes
eval/reports/measure_pieces.md. It checks no baseline: the report is the result
(README decision 79).
"""

from __future__ import annotations

from dataclasses import dataclass

import pytest
import pytest_asyncio

from app.capabilities.snapshot import load_snapshot
from app.choosing.chooser import CHOOSER_MODEL, SPECIFICATION
from app.choosing.jev import JevModel
from app.core.settings import Settings
from app.decompose.decomposer import THINKING as DECOMPOSE_THINKING
from app.decompose.decomposer import system_prompt as decompose_prompt
from app.filling.pieces import pieces_prompt, vocabulary
from app.filling.values import SPECIFICATION as FILL_SPECIFICATION
from app.llm.runner import DECOMPOSE_MODEL, PLANNER_MODEL
from app.planning.planner import THINKING as PLANNER_THINKING
from app.planning.planner import system_prompt as planner_prompt
from domain.school.glossary import glossary_lines
from eval.conftest import StressIndex
from eval.measure.faults import inject_faults
from eval.measure.pieces_report import comparison_table, write_pieces_report
from eval.measure.pipeline import MAX_STEPS, CaseResult, Pipeline, load_cases
from eval.measure.recordings import Recordings, mode_from_environment
from eval.measure.scores import FourNumbers, four_numbers
from eval.test_measure import model_or_none, recordings

pytestmark = [pytest.mark.integration, pytest.mark.embeddings]


@dataclass(frozen=True, slots=True)
class Comparison:
    planner: list[CaseResult]
    pieces: list[CaseResult]
    numbers_planner: dict[str, FourNumbers]
    numbers_pieces: dict[str, FourNumbers]


@pytest_asyncio.fixture(scope="module")
async def comparison(stress: StressIndex) -> Comparison:
    retriever, index_ids, _ = stress
    catalog = {capability.id: capability for capability in load_snapshot().capabilities}
    mode = mode_from_environment()
    decompose_recordings, plan_recordings = recordings()
    pieces = pieces_prompt(vocabulary(catalog.values()))
    pieces_recordings = Recordings(
        "decompose_pieces",
        DECOMPOSE_MODEL,
        decompose_prompt(glossary_lines(), pieces),
        mode=mode,
        thinking=DECOMPOSE_THINKING,
    )
    choose_recordings = Recordings("choose_pieces", CHOOSER_MODEL, SPECIFICATION, mode=mode)
    fill_recordings = Recordings("fill", CHOOSER_MODEL, FILL_SPECIFICATION, mode=mode)
    fallback_recordings = Recordings(
        "plan_fallback",
        PLANNER_MODEL,
        planner_prompt(MAX_STEPS),
        mode=mode,
        thinking=PLANNER_THINKING,
    )
    model = model_or_none()
    jev = JevModel.from_settings(Settings()) if mode == "record" else None
    try:
        cases = load_cases()
        planner_run = await Pipeline(
            retriever, index_ids, catalog, decompose_recordings, plan_recordings, model
        ).run_all(cases)
        pieces_run = await Pipeline(
            retriever,
            index_ids,
            catalog,
            pieces_recordings,
            fallback_recordings,
            model,
            choose_recordings,
            jev,
            pieces=pieces,
            fill_recordings=fill_recordings,
        ).run_all(cases)
    finally:
        if jev is not None:
            await jev.aclose()

    numbers_planner = four_numbers(planner_run, inject_faults(planner_run, catalog))
    numbers_pieces = four_numbers(pieces_run, inject_faults(pieces_run, catalog))
    write_pieces_report(planner_run, pieces_run, numbers_planner, numbers_pieces)
    return Comparison(planner_run, pieces_run, numbers_planner, numbers_pieces)


def test_the_comparison_prints_and_every_sentence_got_an_outcome(
    comparison: Comparison, capsys: pytest.CaptureFixture[str]
) -> None:
    built = sum(result.from_pieces for result in comparison.pieces)
    with capsys.disabled():
        print("\n\nPlanner call -> built from the pieces\n")  # noqa: T201
        print(comparison_table(comparison.numbers_planner, comparison.numbers_pieces))  # noqa: T201
        print(  # noqa: T201
            f"\nBuilt without a planner call: {built} of {len(comparison.pieces)} "
            f"({built / len(comparison.pieces):.1%}). Detail: eval/reports/measure_pieces.md\n"
        )

    assert len(comparison.pieces) == len(load_cases())
    assert not [r.case.text for r in comparison.pieces if r.outcome == "failed"]
