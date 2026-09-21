"""The planner (model call 2): the sentence, its intents and the candidates, into a plan.

The model sees the user's own sentence, the intents (only as a hint: they were written for search
and can be wrong), today's date, and the candidates with their parameters. It answers with a plan,
a request for missing values, or a refusal, and ``validate_plan`` checks that answer before anything
uses it.
"""

from __future__ import annotations

import json
from collections.abc import Callable, Collection, Sequence
from datetime import date
from pathlib import Path
from typing import Any

from app.core import trace
from app.decompose.decomposer import Decomposition
from app.gateway.models import CapabilityMetadata
from app.llm.runner import PLANNER_MODEL, ModelRequest, StructuredModel
from app.planning.outcomes import SCHEMA, NeedsInput, PlannedSteps, PlanOutcome
from app.validation.plan_validator import validate_plan

SYSTEM = (Path(__file__).parent / "system_prompt.md").read_text().strip()


def candidate_entry(capability: CapabilityMetadata) -> dict[str, Any]:
    """What the planner needs to know about one candidate, and nothing it could misuse."""
    parameters = []
    for param in capability.params:
        entry: dict[str, Any] = {
            "name": param.name,
            "type": param.type.value,
            "required": param.required,
            "meaning": param.meaning,
        }
        if param.resolver is not None:
            entry["looked_up_from_words"] = True
            if param.lookup:
                entry["looked_up_by"] = param.lookup
        if param.allowed:
            entry["allowed"] = param.allowed
        if param.default_value is not None:
            entry["default"] = param.default_value
        parameters.append(entry)
    return {
        "id": capability.id,
        "description": capability.description,
        "changes_data": not capability.read_only,
        "parameters": parameters,
        "publishes": capability.effect.facts,
    }


def planner_prompt(
    sentence: str,
    decomposition: Decomposition,
    candidates: Sequence[CapabilityMetadata],
    today: date,
    time_zone: str,
) -> str:
    intents = "\n".join(f"{n}. {text}" for n, text in enumerate(decomposition.texts, start=1))
    entries = json.dumps([candidate_entry(c) for c in candidates], ensure_ascii=False, indent=1)
    return (
        f"Message:\n{sentence}\n\n"
        f"Intents (a retrieval aid, not the plan):\n{intents}\n\n"
        f"Today: {today.isoformat()} ({time_zone})\n\n"
        f"Candidates:\n{entries}"
    )


def system_prompt(max_steps: int) -> str:
    return SYSTEM.format(max_steps=max_steps)


class Planner:
    def __init__(
        self,
        model: StructuredModel,
        *,
        max_steps: int,
        today: Callable[[], date],
        time_zone: str,
        record_words: Collection[str] = (),
    ) -> None:
        self._model = model
        self._record_words = tuple(record_words)
        self._max_steps = max_steps
        self._today = today
        self._time_zone = time_zone
        self._system = system_prompt(max_steps)

    async def plan(
        self,
        sentence: str,
        decomposition: Decomposition,
        candidates: Sequence[CapabilityMetadata],
        *,
        allowed: Collection[str],
        catalog: dict[str, CapabilityMetadata],
        session_id: str,
    ) -> PlanOutcome:
        request = ModelRequest(
            model=PLANNER_MODEL,
            system=self._system,
            prompt=planner_prompt(
                sentence, decomposition, candidates, self._today(), self._time_zone
            ),
            schema=SCHEMA,
            purpose="plan",
        )
        with trace.stage(
            "plan",
            "Plan",
            trace.by_model(PLANNER_MODEL),
            "Reads the original message, the intents and the candidates, and answers with a "
            "plan (which capabilities, with which parameters), a request for missing values, "
            "or a refusal.",
        ) as stage:
            stage.input = {
                "message": sentence,
                "intents": decomposition.texts,
                "today": self._today().isoformat(),
                "candidates": [candidate.id for candidate in candidates],
                "prompt": request.prompt,
            }
            output = await self._model.generate(request)
            stage.output = output
        with trace.stage(
            "validate",
            "Validate the plan",
            trace.BY_CODE,
            "Checks every rule before anything uses the plan: each capability exists, is allowed "
            "and was a candidate; each parameter is declared and typed; names and amounts are the "
            "user's own words; versions are stamped from the metadata.",
        ) as stage:
            stage.input = output
            outcome = validate_plan(
                output,
                sentence=sentence,
                catalog=catalog,
                allowed=allowed,
                candidates=[candidate.id for candidate in candidates],
                max_steps=self._max_steps,
                session_id=session_id,
                record_words=self._record_words,
            )
            stage.output = describe_outcome(outcome)
        return outcome


def describe_outcome(outcome: PlanOutcome) -> dict[str, Any]:
    """A plan outcome as plain data, for the trace."""
    if isinstance(outcome, PlannedSteps):
        return {
            "outcome": "plan",
            "steps": [step.model_dump(exclude_none=True) for step in outcome.plan.steps],
        }
    if isinstance(outcome, NeedsInput):
        return {
            "outcome": "needs_input",
            "step": outcome.step.model_dump(exclude_none=True),
            "missing": list(outcome.missing),
        }
    return {"outcome": "refusal", "reason": outcome.reason.value}
