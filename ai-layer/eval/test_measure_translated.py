"""The four numbers when a translator replaces decompose (experiment; not committed yet).

    SCRATCH=<dir> EVAL_CALLS_PER_MINUTE=12 uv run pytest eval/test_measure_translated.py -q -s

The translator writes one English sentence per message (direction forced to English, produced by
scripts in the scratchpad), and that sentence is the only search query: no splitting into intents,
no checks. Everything after retrieval is unchanged, and the planner still reads the original
message. Its answers are recorded apart, in eval/recordings/plan_from_translation.json.

Prints the four numbers beside a run of the same pipeline with decompose's intents, so the cost of
dropping the decompose call is a single comparison.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path

import pytest
import pytest_asyncio

from app.capabilities.snapshot import load_snapshot
from app.choosing.chooser import CHOOSER_MODEL, SPECIFICATION_WITH_MESSAGE
from app.choosing.jev import JevModel
from app.core.settings import Settings
from app.llm.runner import PLANNER_MODEL
from app.planning.planner import THINKING as PLANNER_THINKING
from app.planning.planner import system_prompt as planner_prompt
from eval.conftest import StressIndex
from eval.measure import pipeline as pipeline_module
from eval.measure.choose_report import comparison_table
from eval.measure.faults import inject_faults
from eval.measure.pipeline import MAX_STEPS, CaseResult, Pipeline, load_cases
from eval.measure.recordings import Recordings, mode_from_environment
from eval.measure.scores import FourNumbers, four_numbers
from eval.retrieval.intents import DecomposedSentence
from eval.test_measure import model_or_none, recordings

TRANSLATIONS = Path(os.environ.get("SCRATCH", ".")) / "translations_forced.json"

pytestmark = [
    pytest.mark.integration,
    pytest.mark.embeddings,
    # The translator writes this file outside the repo; without it there is nothing to compare.
    pytest.mark.skipif(
        not TRANSLATIONS.exists(), reason=f"no translations at {TRANSLATIONS}; set SCRATCH"
    ),
]


@pytest.fixture(scope="module")
def monkeypatch_module():
    """pytest's monkeypatch is function-scoped; the pipeline runs once for the module."""
    patch = pytest.MonkeyPatch()
    yield patch
    patch.undo()


@pytest.fixture(scope="module")
def translations() -> dict[str, str]:
    """What the translator wrote for every eval sentence, keyed by the sentence."""
    written: dict[str, str] = json.loads(TRANSLATIONS.read_text())
    return written


@dataclass(frozen=True, slots=True)
class Comparison:
    with_intents: list[CaseResult]
    with_translation: list[CaseResult]
    with_both: list[CaseResult]
    numbers_intents: dict[str, FourNumbers]
    numbers_translation: dict[str, FourNumbers]
    numbers_both: dict[str, FourNumbers]


@pytest_asyncio.fixture(scope="module")
async def comparison(
    stress: StressIndex, monkeypatch_module: pytest.MonkeyPatch, translations: dict[str, str]
) -> Comparison:
    retriever, index_ids, _ = stress
    catalog = {capability.id: capability for capability in load_snapshot().capabilities}
    mode = mode_from_environment()
    decompose_recordings, plan_recordings = recordings()
    translated_plan = Recordings(
        "plan_from_translation",
        PLANNER_MODEL,
        planner_prompt(MAX_STEPS),
        mode=mode,
        thinking=PLANNER_THINKING,
    )
    model = model_or_none()
    cases = load_cases()

    with_intents = await Pipeline(
        retriever, index_ids, catalog, decompose_recordings, plan_recordings, model
    ).run_all(cases)

    # The translator's sentence stands in for decompose's intents; nothing else changes.
    missing = [c.text for c in cases if c.text not in translations]
    assert not missing, f"no translation for {len(missing)} sentences, first: {missing[:1]}"

    async def translated(text: str, *args: object, **kwargs: object) -> DecomposedSentence:
        return DecomposedSentence((translations[text],))

    monkeypatch_module.setattr(pipeline_module, "decompose", translated)
    with_translation = await Pipeline(
        retriever, index_ids, catalog, decompose_recordings, translated_plan, model
    ).run_all(cases)

    # The same again with the chooser, as chat runs it: Jev narrows, then the planner fills in.
    choose_recordings = Recordings(
        "choose_from_translation", CHOOSER_MODEL, SPECIFICATION_WITH_MESSAGE, mode=mode
    )
    plan_after_choose = Recordings(
        "plan_from_translation_with_jev",
        PLANNER_MODEL,
        planner_prompt(MAX_STEPS),
        mode=mode,
        thinking=PLANNER_THINKING,
    )
    jev = JevModel.from_settings(Settings()) if mode == "record" else None
    try:
        with_both = await Pipeline(
            retriever,
            index_ids,
            catalog,
            decompose_recordings,
            plan_after_choose,
            model,
            choose_recordings,
            jev,
            chooser_reads_the_message=True,
        ).run_all(cases)
    finally:
        if jev is not None:
            await jev.aclose()

    return Comparison(
        with_intents,
        with_translation,
        with_both,
        four_numbers(with_intents, inject_faults(with_intents, catalog)),
        four_numbers(with_translation, inject_faults(with_translation, catalog)),
        four_numbers(with_both, inject_faults(with_both, catalog)),
    )


def test_the_four_numbers_with_a_translator_instead_of_decompose(
    comparison: Comparison, capsys: pytest.CaptureFixture[str]
) -> None:
    with capsys.disabled():
        print("\n\ndecompose's intents -> the translator's sentence\n")  # noqa: T201
        print(comparison_table(comparison.numbers_intents, comparison.numbers_translation))  # noqa: T201
        print("\n\nthe translator alone -> the translator with Jev choosing\n")  # noqa: T201
        print(comparison_table(comparison.numbers_translation, comparison.numbers_both))  # noqa: T201
        changed = [
            (new.case.text, old.outcome, new.outcome, new.capabilities)
            for old, new in zip(comparison.with_intents, comparison.with_translation, strict=True)
            if (old.planned_correctly or old.refusal_decision_correct)
            != (new.planned_correctly or new.refusal_decision_correct)
        ]
        print(f"\nmessages whose result changed ({len(changed)}):")  # noqa: T201
        for text, before, after, capabilities in changed[:25]:
            print(f"  {text[:52]:52} {before:10} -> {after:10} {', '.join(capabilities)}")  # noqa: T201

    assert not [r.case.text for r in comparison.with_translation if r.outcome == "failed"]
