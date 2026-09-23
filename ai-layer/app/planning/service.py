"""From a sentence to a checked plan: decompose, retrieve, plan, validate.

With the capability chooser on (``AI_LAYER_CHOOSER_ENABLED``), Jev first narrows the candidates to
the ones it picks, and the planner plans among those. If the chooser cannot be reached, the planner
sees every candidate, as it does with the chooser off.

This is the understanding half of a chat turn. The orchestrator (Phase 6) calls it, then sends a
``PlannedSteps`` plan to preflight, asks for a ``NeedsInput``, or shows a ``Refusal``. Nothing here
writes, and nothing reaches the backend except through the retriever's index and the catalog.
"""

from __future__ import annotations

from collections.abc import Callable, Collection, Mapping, Sequence
from dataclasses import dataclass
from typing import Protocol

import structlog

from app.choosing.chooser import CapabilityChooser, Choice
from app.core import trace
from app.decompose.decomposer import Decomposition, IntentSource
from app.gateway.models import CapabilityMetadata
from app.llm.runner import ModelError, ModelUnavailableError
from app.planning.outcomes import NeedsInput, PlannedSteps, PlanOutcome, Refusal, RefusalReason
from app.planning.planner import Planner
from app.retrieval.hybrid import RetrievalResult

log = structlog.get_logger(__name__)


class CandidateRetriever(Protocol):
    async def retrieve(
        self, queries: Sequence[str], allowed: Collection[str]
    ) -> RetrievalResult: ...


@dataclass(frozen=True, slots=True)
class Understanding:
    decomposition: Decomposition
    candidates: tuple[str, ...]
    outcome: PlanOutcome
    # What the chooser picked, when it is on and answered.
    choice: Choice | None = None


class SentencePlanner:
    def __init__(
        self,
        decomposer: IntentSource,
        retriever: CandidateRetriever,
        planner: Planner,
        catalog: Callable[[], Mapping[str, CapabilityMetadata]],
        chooser: CapabilityChooser | None = None,
    ) -> None:
        self._decomposer = decomposer
        self._retriever = retriever
        self._planner = planner
        self._catalog = catalog
        self._chooser = chooser

    async def understand(
        self, sentence: str, *, allowed: Collection[str], session_id: str
    ) -> Understanding:
        """Raises ``ModelError`` when a model call fails or its output breaks a rule."""
        decomposition = await self._decomposer.decompose(sentence)
        catalog = dict(self._catalog())
        allow_list = [capability_id for capability_id in allowed if capability_id in catalog]
        retrieval = await self._retriever.retrieve(decomposition.texts, allow_list)
        candidates = [catalog[c] for c in retrieval.capability_ids if c in catalog]
        retrieved = tuple(c.id for c in candidates)

        choice = await self._choose(sentence, decomposition.texts, candidates, session_id)
        if choice is not None:
            by_id = {c.id: c for c in candidates}
            candidates = [by_id[c] for c in choice.shortlist]

        outcome: PlanOutcome
        if not candidates and choice is not None:
            outcome = Refusal(RefusalReason.NO_MATCHING_CAPABILITY)
            trace.note(
                "plan",
                "Plan",
                trace.BY_CODE,
                "Skipped: the chooser found no candidate that carries out the request, so no "
                "planner call is made.",
                status="skipped",
                output={"outcome": "refusal", "reason": outcome.reason.value},
            )
        elif not candidates:
            # Nothing the user may use is even close: no planner call is worth making.
            outcome = Refusal(RefusalReason.NO_MATCHING_CAPABILITY)
            trace.note(
                "plan",
                "Plan",
                trace.BY_CODE,
                "Skipped: no capability this user may use came close, so no planner call is made.",
                status="skipped",
                output={"outcome": "refusal", "reason": outcome.reason.value},
            )
        else:
            outcome = await self._planner.plan(
                sentence,
                decomposition,
                candidates,
                allowed=allow_list,
                catalog=catalog,
                session_id=session_id,
            )
        log.info(
            "sentence_planned",
            session_id=session_id,
            intents=len(decomposition.intents),
            candidates=len(retrieved),
            shortlist=len(candidates) if choice is not None else None,
            outcome=_describe(outcome),
        )
        return Understanding(decomposition, retrieved, outcome, choice)

    async def _choose(
        self,
        sentence: str,
        intents: Sequence[str],
        candidates: Sequence[CapabilityMetadata],
        session_id: str,
    ) -> Choice | None:
        """The chooser's pick, or None when it is off, has nothing to choose from, or failed."""
        if self._chooser is None or not candidates:
            return None
        try:
            return await self._chooser.choose(intents, candidates, sentence)
        except ModelError as exc:
            # The planner can still choose among every candidate, as it does with the chooser off.
            log.warning(
                "chooser_failed",
                session_id=session_id,
                unavailable=isinstance(exc, ModelUnavailableError),
                error=str(exc),
            )
            return None


def _describe(outcome: PlanOutcome) -> str:
    if isinstance(outcome, PlannedSteps):
        return "plan:" + ",".join(step.capability_id for step in outcome.plan.steps)
    if isinstance(outcome, NeedsInput):
        return f"needs_input:{outcome.capability_id}"
    return f"refusal:{outcome.reason.value}"
