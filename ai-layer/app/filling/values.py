"""Turning what the user said into the value a parameter allows, with Jev rather than the planner.

A piece is the user's own word: "naqad", "whole school", "over three months". The parameter takes a
fixed value: ``cash``, ``school_wide``, ``over_90_days``. When the word is not one of them already,
one choice question per parameter goes to Jev, which answers with a probability for each allowed
value and for "none" (README decision 79). Nothing is guessed: below ``SURE_AT``, or on "none", the
parameter is left for the planner.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

from app.choosing.chooser import CHOOSER_MODEL, NONE
from app.choosing.jev import DecisionModel, DecisionRequest
from app.core import trace
from app.gateway.models import CapabilityMetadata, ParamMetadata

INSTRUCTIONS = (
    'A school office staff member wrote "{said}" for "{meaning}". Which of these is that? '
    'Choose "none" when it is none of them.'
)
SPECIFICATION = "\n".join([INSTRUCTIONS, "a question per parameter, its values as criteria (v1)"])
# A value is used only when Jev is at least this sure: it decides what the school records.
SURE_AT = 0.7


def wanted(
    capability: CapabilityMetadata, pieces: Mapping[str, str]
) -> list[tuple[ParamMetadata, str]]:
    """Every parameter whose piece is not already one of its allowed values."""
    asking = []
    for param in capability.params:
        said = pieces.get(param.filled_by or "", "").strip() if param.filled_by else ""
        if not said or not param.allowed:
            continue
        wanted_value = said.lower().replace(" ", "_").replace("-", "_")
        if wanted_value not in [allowed.lower() for allowed in param.allowed]:
            asking.append((param, said))
    return asking


def decision_request(asking: Sequence[tuple[ParamMetadata, str]]) -> DecisionRequest:
    questions: dict[str, Any] = {}
    for param, said in asking:
        criteria: dict[str, Any] = {value: f"{param.meaning}: {value}" for value in param.allowed}
        criteria[NONE] = "none of these values is what they said"
        questions[param.name] = {
            "type": "choice",
            "instructions": INSTRUCTIONS.format(said=said, meaning=param.meaning),
            "criteria": criteria,
        }
    return DecisionRequest(
        model=CHOOSER_MODEL,
        state={"said": {param.name: said for param, said in asking}},
        questions=questions,
        purpose="fill",
        system=SPECIFICATION,
    )


def parse_values(
    answer: Mapping[str, Any], asking: Sequence[tuple[ParamMetadata, str]]
) -> dict[str, str]:
    """The value for each parameter Jev was sure enough about; the rest are left out."""
    answers = answer.get("answers")
    if not isinstance(answers, Mapping):
        return {}
    chosen: dict[str, str] = {}
    for param, _ in asking:
        given = answers.get(param.name)
        if not isinstance(given, Mapping):
            continue
        value, confidence = given.get("choice"), given.get("confidence", 0)
        probability = (given.get("probabilities") or {}).get(value, 0)
        sure = min(float(confidence or 0), float(probability or 0))
        if value in param.allowed and sure >= SURE_AT:
            chosen[param.name] = str(value)
    return chosen


class ValueChooser:
    """Asks Jev which allowed value the user's words are, for the parameters that need it."""

    def __init__(self, model: DecisionModel) -> None:
        self._model = model

    async def choose(
        self, capability: CapabilityMetadata, pieces: Mapping[str, str]
    ) -> dict[str, str]:
        asking = wanted(capability, pieces)
        if not asking:
            return {}
        request = decision_request(asking)
        with trace.stage(
            "fill",
            "Fill the fixed values",
            trace.by_model(CHOOSER_MODEL),
            'Turns what the user said into the values a parameter allows ("naqad" into cash), '
            "one question per parameter, so the planner is not needed for them.",
        ) as stage:
            stage.input = {param.name: said for param, said in asking}
            answer = await self._model.decide(request)
            chosen = parse_values(answer, asking)
            stage.output = {"chosen": chosen, "asked": [param.name for param, _ in asking]}
        return chosen
