"""Phase 7: one command prints the four numbers, and a broken description shows up as a recall drop.

    make measure      records any model answer not yet in eval/recordings/, then measures
    make measure-ci   replays the recordings only: no model is called (the CI regression run)

Needs Docker and the embeddings service. Prints the four numbers for all sentences, English and
Roman Urdu, and fails when one falls below eval/measure/baseline.json by more than its tolerance.
``EVAL_UPDATE_BASELINE=1 make measure`` accepts the current numbers as the new baseline.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path

import pytest
import pytest_asyncio

from app.capabilities.snapshot import load_snapshot
from app.core.settings import Settings
from app.decompose.decomposer import THINKING as DECOMPOSE_THINKING
from app.decompose.decomposer import system_prompt as decompose_prompt
from app.embeddings.client import EmbeddingsClient
from app.index.database import CapabilityRow
from app.llm.gemini import GeminiModel
from app.llm.runner import DECOMPOSE_MODEL, PLANNER_MODEL
from app.planning.planner import system_prompt as planner_prompt
from app.retrieval.hybrid import HybridRetriever
from app.sync.metadata_sync import EMBEDDING_MODEL
from domain.school.glossary import glossary_lines
from eval.conftest import IndexFactory, StressIndex
from eval.measure.faults import FaultTally, inject_faults
from eval.measure.pipeline import MAX_STEPS, CaseResult, Pipeline, load_cases
from eval.measure.recordings import Recordings, mode_from_environment
from eval.measure.report import table, write_report
from eval.measure.scores import GROUPS, FourNumbers, four_numbers
from eval.retrieval.dataset import load_distractors
from eval.retrieval.embedding_cache import CachingEmbedder
from eval.retrieval.harness import add_distractors, fill_poc_index
from eval.retrieval.intents import decompose

pytestmark = [pytest.mark.integration, pytest.mark.embeddings]

BASELINE_PATH = Path(__file__).resolve().parent / "measure" / "baseline.json"
# How far a number may fall below the baseline before the run fails. Replayed answers give the
# same numbers every time; the room is for a fresh recording, where the models vary a little.
TOLERANCE = {
    "recall_at_30": 0.02,
    "plan_accuracy": 0.03,
    "refusal_correctness": 0.03,
    "validator_catch_rate": 0.0,
}
SABOTAGED = "fee.payment.record"
SABOTAGE = (
    "Records a library book lent to a student, with the date it is due back, and marks it returned "
    "when it comes back. Not for buying new books for the library."
)


@dataclass(frozen=True, slots=True)
class Measurement:
    results: list[CaseResult]
    numbers: dict[str, FourNumbers]
    faults: dict[str, dict[str, FaultTally]]
    baseline: dict[str, dict[str, float]] | None


def recordings() -> tuple[Recordings, Recordings]:
    mode = mode_from_environment()
    return (
        Recordings(
            "decompose",
            DECOMPOSE_MODEL,
            decompose_prompt(glossary_lines()),
            mode=mode,
            thinking=DECOMPOSE_THINKING,
        ),
        Recordings("plan", PLANNER_MODEL, planner_prompt(MAX_STEPS), mode=mode),
    )


def model_or_none() -> GeminiModel | None:
    return GeminiModel.from_settings(Settings()) if mode_from_environment() == "record" else None


@pytest_asyncio.fixture(scope="module")
async def measurement(stress: StressIndex) -> Measurement:
    retriever, index_ids, _ = stress
    catalog = {capability.id: capability for capability in load_snapshot().capabilities}
    decompose_recordings, plan_recordings = recordings()
    pipeline = Pipeline(
        retriever, index_ids, catalog, decompose_recordings, plan_recordings, model_or_none()
    )

    results = await pipeline.run_all(load_cases())
    faults = inject_faults(results, catalog)
    numbers = four_numbers(results, faults)

    baseline = json.loads(BASELINE_PATH.read_text()) if BASELINE_PATH.exists() else None
    if baseline is None or os.environ.get("EVAL_UPDATE_BASELINE") == "1":
        BASELINE_PATH.write_text(
            json.dumps({g: numbers[g].as_dict() for g in GROUPS}, indent=1) + "\n"
        )
    write_report(results, numbers, faults, baseline, index_size=len(index_ids))
    return Measurement(results, numbers, faults, baseline)


def test_one_command_prints_the_four_numbers_and_none_fell_below_the_baseline(
    measurement: Measurement, capsys: pytest.CaptureFixture[str]
) -> None:
    with capsys.disabled():
        print("\n\n" + table(measurement.numbers, measurement.baseline) + "\n")  # noqa: T201
        print("Detail: eval/reports/measure.md\n")  # noqa: T201

    fallen = [
        f"{group} {key}: {getattr(measurement.numbers[group], key):.1%} "
        f"(baseline {measurement.baseline[group][key]:.1%})"
        for group in GROUPS
        for key, room in TOLERANCE.items()
        if measurement.baseline is not None
        and getattr(measurement.numbers[group], key) < measurement.baseline[group][key] - room
    ]
    assert not fallen, "Below the baseline: " + "; ".join(fallen)


def test_every_answer_was_recorded_and_every_sentence_got_an_outcome(
    measurement: Measurement,
) -> None:
    assert len(measurement.results) == len(load_cases())
    assert not [r.case.text for r in measurement.results if r.outcome == "failed"]


def test_the_validator_catches_every_injected_fault(measurement: Measurement) -> None:
    tallies = measurement.faults["all"]

    assert all(tally.applied > 0 for tally in tallies.values()), {
        n: t.applied for n, t in tallies.items()
    }
    escaped = {name: tally.escaped for name, tally in tallies.items() if tally.escaped}
    assert not escaped
    assert all(tally.right_reason == tally.applied for tally in tallies.values())


def test_recall_at_30_with_real_intents_still_clears_the_phase_4_gate(
    measurement: Measurement,
) -> None:
    assert measurement.numbers["all"].recall_at_30 > 0.90


async def test_a_deliberately_broken_description_shows_up_as_a_recall_drop(
    new_index: IndexFactory, capsys: pytest.CaptureFixture[str]
) -> None:
    cases = [c for c in load_cases() if c.expected is not None]
    decompose_recordings, _ = recordings()
    model = model_or_none()
    intents = {
        c.text: (await decompose(c.text, decompose_recordings, model)).intents for c in cases
    }

    client = EmbeddingsClient.from_settings(Settings())
    try:
        embedder = CachingEmbedder(client)
        index = await new_index()
        await fill_poc_index(index, embedder)
        await add_distractors(index, embedder, load_distractors())
        retriever = HybridRetriever(index, embedder)
        allowed = sorted(await index.indexed_versions())

        async def recall(only: str | None = None) -> float:
            chosen = [c for c in cases if only is None or c.expected == only]
            hits = 0
            for case in chosen:
                if intents[case.text]:
                    result = await retriever.retrieve(intents[case.text], allowed)
                    hits += case.expected in result.capability_ids
            return hits / len(chosen)

        before = (await recall(), await recall(SABOTAGED))
        metadata = {c.id: c for c in load_snapshot().capabilities}[SABOTAGED]
        [vector] = await embedder.embed([SABOTAGE])
        broken = CapabilityRow(
            capability_id=SABOTAGED,
            content=SABOTAGE,
            module=metadata.module,
            read_only=metadata.read_only,
            embedding=vector,
            version="sabotaged",
            disambiguate_from=tuple(metadata.disambiguate_from),
            embedding_model=EMBEDDING_MODEL,
        )
        await index.apply_sync([broken], [])
        after = (await recall(), await recall(SABOTAGED))
    finally:
        await client.aclose()

    with capsys.disabled():
        print(  # noqa: T201
            f"\n\nBroken description for {SABOTAGED}: recall@30 for its sentences "
            f"{before[1]:.1%} -> {after[1]:.1%}; "
            f"for all sentences {before[0]:.1%} -> {after[0]:.1%}\n"
        )
    assert after[1] <= before[1] - 0.30
    assert after[0] < before[0]
