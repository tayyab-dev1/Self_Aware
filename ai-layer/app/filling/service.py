"""Planning without a second model call: the chooser's capability, plus the pieces decompose read.

The message is read once (decompose, with pieces), the capability is picked (the chooser), the
values a parameter allows are read from the user's words (Jev again, only when needed), and the plan
is put together by code. The validator then checks that plan exactly as it checks the planner's.

Anything this is not sure of gives ``None`` and the planner is asked instead: a piece that is not a
date or a number, a value Jev was not sure enough about, two records read from the same words, more
than one intent where one is incomplete, or a piece name that was never published. So the fast path
is only ever taken for messages it can fill with confidence (README decision 79).
"""

from __future__ import annotations

from collections.abc import Callable, Collection, Mapping, Sequence
from datetime import date
from typing import Any

import structlog

from app.choosing.chooser import NONE, Choice
from app.core import trace
from app.decompose.decomposer import Decomposition
from app.filling.assembler import assemble
from app.filling.values import ValueChooser
from app.gateway.models import PublishedCapability
from app.planning.outcomes import PlanOutcome
from app.validation.plan_validator import validate_plan

log = structlog.get_logger(__name__)


class PieceFiller:
    def __init__(
        self,
        values: ValueChooser | None,
        *,
        today: Callable[[], date],
        max_steps: int,
        known_pieces: Collection[str],
        record_words: Collection[str] = (),
    ) -> None:
        # The piece names decompose was given (``pieces.vocabulary``), so a name it made up is
        # noticed rather than ignored.
        self._known_pieces = frozenset(known_pieces)
        self._values = values
        self._today = today
        self._max_steps = max_steps
        self._record_words = tuple(record_words)

    async def plan(
        self,
        sentence: str,
        decomposition: Decomposition,
        choice: Choice,
        *,
        allowed: Collection[str],
        catalog: Mapping[str, PublishedCapability],
        candidates: Sequence[str],
        session_id: str,
    ) -> PlanOutcome | None:
        """The plan the pieces make, or None when the planner should be asked instead."""
        if len(decomposition.intents) != len(choice.intents):
            return None
        answer = await self._answer(decomposition, choice, catalog)
        if answer is None:
            return None
        with trace.stage(
            "fill_plan",
            "Build the plan from the pieces",
            trace.BY_CODE,
            "Puts the chosen capability together with what the user said, piece by piece, so no "
            "second model call is needed. Anything it is unsure of goes to the planner instead.",
        ) as stage:
            stage.input = {
                "capabilities": [step["capability_id"] for step in _steps(answer)],
                "pieces": [dict(intent.fields) for intent in decomposition.intents],
            }
            stage.output = answer
        return validate_plan(
            answer,
            sentence=sentence,
            catalog=dict(catalog),
            allowed=allowed,
            candidates=list(candidates),
            max_steps=self._max_steps,
            session_id=session_id,
            record_words=self._record_words,
        )

    async def _answer(
        self,
        decomposition: Decomposition,
        choice: Choice,
        catalog: Mapping[str, PublishedCapability],
    ) -> dict[str, Any] | None:
        steps: list[dict[str, Any]] = []
        needs_input: dict[str, Any] | None = None
        for intent, picked in zip(decomposition.intents, choice.intents, strict=True):
            if picked.choice == NONE or picked.choice not in catalog or not intent.fields:
                return None
            if invented := sorted(set(intent.fields) - self._known_pieces):
                # A piece name no published capability uses, as "method_of_payment" would be for
                # "payment_method": nothing reads it, so what the user said there would be dropped
                # and a parameter they did give asked for again. The planner reads the message.
                log.info("piece_name_not_published", names=invented)
                return None
            capability = catalog[picked.choice]
            chosen = await self._values.choose(capability, intent.fields) if self._values else {}
            built = assemble(capability, intent.fields, self._today(), chosen)
            if built is None:
                return None
            if built["outcome"] == "needs_input":
                # Only one action can be answered this way, so anything else is the planner's.
                if needs_input is not None or len(decomposition.intents) > 1:
                    return None
                needs_input = built["needs_input"]
            else:
                steps.extend(built["steps"])
        if needs_input is not None:
            return {"outcome": "needs_input", "needs_input": needs_input}
        return {"outcome": "plan", "steps": steps} if steps else None


def _steps(answer: Mapping[str, Any]) -> list[dict[str, Any]]:
    if answer["outcome"] == "needs_input":
        return [answer["needs_input"]]
    steps: list[dict[str, Any]] = answer["steps"]
    return steps
