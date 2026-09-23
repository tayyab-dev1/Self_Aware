"""Every eval sentence through the real understanding pipeline, from the recorded model answers.

Decompose, retrieve on the stress index, plan among the candidates the POC can run, validate: the
same steps ``SentencePlanner`` takes, spelled out so each stage's result is kept for scoring. With
``choose_recordings`` set, the capability chooser runs between retrieval and the planner, and the
planner sees only its shortlist (``make measure-jev``). The
user may use every published capability; today is fixed, so recorded dates stay valid.
"""

from __future__ import annotations

import asyncio
import functools
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict

from app.choosing.chooser import CapabilityChooser, Choice
from app.choosing.jev import DecisionModel
from app.decompose.decomposer import Decomposition, Intent
from app.gateway.models import CapabilityMetadata
from app.llm.runner import ModelError, StructuredModel
from app.planning.outcomes import NeedsInput, PlannedSteps, Refusal
from app.planning.planner import Planner
from app.retrieval.hybrid import HybridRetriever
from app.validation.problems import InvalidModelOutputError
from domain.school.calendar import SCHOOL_TIME_ZONE
from domain.school.glossary import RECORD_WORDS
from eval.measure.recordings import ModelCallFailedError, Recordings
from eval.retrieval.dataset import load_sentences
from eval.retrieval.intents import decompose

DATA_DIR = Path(__file__).resolve().parent / "data"
REFUSAL_FILES = ("refusal_sentences.jsonl", "refusal_authored.jsonl")
TODAY = date(2026, 9, 14)
MAX_STEPS = 3
CONCURRENT_CASES = 6

Outcome = Literal["plan", "needs_input", "refusal", "invalid", "failed"]


class RefusalSentence(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    text: str
    lang: Literal["en", "ur-Latn"]
    source: Literal["contract", "authored"]
    gloss: str | None = None
    origin: str | None = None


@dataclass(frozen=True, slots=True)
class Case:
    """A sentence and what should happen: ``expected`` is the capability, or None to refuse."""

    text: str
    lang: str
    expected: str | None
    source: str
    origin: str | None = None


def load_cases() -> list[Case]:
    positives = [Case(s.text, s.lang, s.expected, s.source, s.origin) for s in load_sentences()]
    refusals = [
        Case(r.text, r.lang, None, r.source, r.origin)
        for name in REFUSAL_FILES
        for line in (DATA_DIR / name).read_text().splitlines()
        if line.strip()
        for r in [RefusalSentence.model_validate_json(line)]
    ]
    return positives + refusals


@dataclass(frozen=True, slots=True)
class CaseResult:
    case: Case
    intents: tuple[str, ...]
    candidates: tuple[str, ...]
    plannable: tuple[str, ...]
    outcome: Outcome
    capabilities: tuple[str, ...] = ()
    problems: tuple[str, ...] = ()
    raw_plan: Mapping[str, Any] | None = None
    # With the chooser on: what it picked. ``plannable`` is then its shortlist.
    choice: Choice | None = None

    @property
    def acted(self) -> bool:
        """The system would do something: preflight a plan, or ask for a missing value."""
        return self.outcome in ("plan", "needs_input")

    @property
    def planned_correctly(self) -> bool:
        return self.acted and self.capabilities == (self.case.expected,)

    @property
    def refusal_decision_correct(self) -> bool:
        return self.acted != (self.case.expected is None)

    @property
    def retrieved(self) -> bool:
        return self.case.expected in self.candidates


@dataclass(frozen=True, slots=True)
class Pipeline:
    retriever: HybridRetriever
    index_ids: Sequence[str]
    catalog: Mapping[str, CapabilityMetadata]
    decompose_recordings: Recordings
    plan_recordings: Recordings
    model: StructuredModel | None
    choose_recordings: Recordings | None = None
    decider: DecisionModel | None = None
    # True when the intents are a translation: Jev reads the message as typed too (decision 79).
    chooser_reads_the_message: bool = False

    async def run_all(self, cases: Sequence[Case]) -> list[CaseResult]:
        gate = asyncio.Semaphore(CONCURRENT_CASES)

        async def one(case: Case) -> CaseResult:
            async with gate:
                return await self.run(case)

        return list(await asyncio.gather(*(one(case) for case in cases)))

    async def run(self, case: Case) -> CaseResult:
        decomposed = await decompose(case.text, self.decompose_recordings, self.model)
        if not decomposed.intents:
            return CaseResult(case, (), (), (), "invalid", problems=decomposed.problems)

        retrieval = await self.retriever.retrieve(decomposed.intents, self.index_ids)
        candidates = tuple(retrieval.capability_ids)
        plannable = tuple(c for c in candidates if c in self.catalog)
        if not plannable:
            return CaseResult(case, decomposed.intents, candidates, (), "refusal")

        choice: Choice | None = None
        if self.choose_recordings is not None:
            chooser = CapabilityChooser(
                self.choose_recordings.decider_for(case.text, self.decider),
                with_message=self.chooser_reads_the_message,
            )
            try:
                choice = await chooser.choose(
                    decomposed.intents, [self.catalog[c] for c in plannable], case.text
                )
            except (ModelCallFailedError, ModelError) as exc:
                return CaseResult(
                    case, decomposed.intents, candidates, plannable, "failed",
                    problems=(f"CHOOSER_FAILED: {type(exc).__name__}",),
                )  # fmt: skip
            plannable = choice.shortlist
            if not plannable:
                return CaseResult(
                    case, decomposed.intents, candidates, (), "refusal",
                    problems=("chooser_found_none",), choice=choice,
                )  # fmt: skip

        planner = Planner(
            self.plan_recordings.model_for(case.text, self.model),
            max_steps=MAX_STEPS,
            today=lambda: TODAY,
            time_zone=SCHOOL_TIME_ZONE,
            record_words=RECORD_WORDS,
        )
        decomposition = Decomposition(tuple(Intent(text, ()) for text in decomposed.intents))
        found = functools.partial(
            CaseResult, case, decomposed.intents, candidates, plannable, choice=choice
        )
        try:
            outcome = await planner.plan(
                case.text,
                decomposition,
                [self.catalog[c] for c in plannable],
                allowed=list(self.catalog),
                catalog=dict(self.catalog),
                session_id="measure",
            )
        except InvalidModelOutputError as exc:
            return found("invalid", problems=tuple(exc.codes), raw_plan=self.raw(case))
        except (ModelCallFailedError, ModelError) as exc:
            return found("failed", problems=(type(exc).__name__,))

        raw = self.raw(case)
        if isinstance(outcome, Refusal):
            return found("refusal", problems=(outcome.reason.value,), raw_plan=raw)
        if isinstance(outcome, NeedsInput):
            return found("needs_input", capabilities=(outcome.capability_id,), raw_plan=raw)
        assert isinstance(outcome, PlannedSteps)
        steps = tuple(step.capability_id for step in outcome.plan.steps)
        return found("plan", capabilities=steps, raw_plan=raw)

    def raw(self, case: Case) -> Mapping[str, Any] | None:
        return self.plan_recordings.recorded(case.text)
