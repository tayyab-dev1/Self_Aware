"""What planning can end in, and the shape the planner model must answer in."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictFloat, StrictInt, StrictStr

from app.gateway.models import Plan, PlanStep


class RefusalReason(StrEnum):
    NO_MATCHING_CAPABILITY = "no_matching_capability"
    NOT_A_REQUEST = "not_a_request"
    TOO_MANY_ACTIONS = "too_many_actions"


@dataclass(frozen=True, slots=True)
class PlannedSteps:
    """A plan ready for preflight: ids checked, parameters checked, versions stamped."""

    plan: Plan


@dataclass(frozen=True, slots=True)
class NeedsInput:
    """The user asked for something one capability does, but left out required values.

    ``step`` holds what the planner could fill, checked and version-stamped like any step, so the
    conversation completes this step with the user's answers instead of planning again.
    """

    step: PlanStep
    missing: tuple[str, ...]

    @property
    def capability_id(self) -> str:
        return self.step.capability_id


@dataclass(frozen=True, slots=True)
class Refusal:
    reason: RefusalReason


PlanOutcome = PlannedSteps | NeedsInput | Refusal


# --- The planner model's answer, parsed strictly --------------------------------------------------

Scalar = StrictStr | StrictInt | StrictFloat | StrictBool


class ParamOutput(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    name: str = Field(min_length=1, max_length=64)
    value: Scalar | None = None
    words: str | None = Field(default=None, min_length=1, max_length=200)
    from_step: StrictInt | None = None
    field: str | None = Field(default=None, min_length=1, max_length=64)


class StepOutput(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    capability_id: str = Field(min_length=1, max_length=128)
    params: list[ParamOutput] = Field(max_length=20)


class NeedsInputOutput(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    capability_id: str = Field(min_length=1, max_length=128)
    params: list[ParamOutput] = Field(max_length=20)
    missing: list[str] = Field(min_length=1, max_length=20)


class PlannerOutput(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    outcome: Literal["plan", "needs_input", "refusal"]
    steps: list[StepOutput] | None = None
    needs_input: NeedsInputOutput | None = None
    refusal: RefusalReason | None = None


_SCALAR_SCHEMA: dict[str, Any] = {"type": ["string", "number", "integer", "boolean"]}

SCHEMA: dict[str, Any] = {
    "$defs": {
        "params": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["name"],
                "properties": {
                    "name": {"type": "string"},
                    "value": _SCALAR_SCHEMA,
                    "words": {"type": "string"},
                    "from_step": {"type": "integer"},
                    "field": {"type": "string"},
                },
            },
        }
    },
    "type": "object",
    "additionalProperties": False,
    "required": ["outcome"],
    "properties": {
        "outcome": {"type": "string", "enum": ["plan", "needs_input", "refusal"]},
        "steps": {
            "type": "array",
            "minItems": 1,
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["capability_id", "params"],
                "properties": {
                    "capability_id": {"type": "string"},
                    "params": {"$ref": "#/$defs/params"},
                },
            },
        },
        "needs_input": {
            "type": "object",
            "additionalProperties": False,
            "required": ["capability_id", "params", "missing"],
            "properties": {
                "capability_id": {"type": "string"},
                "params": {"$ref": "#/$defs/params"},
                "missing": {"type": "array", "minItems": 1, "items": {"type": "string"}},
            },
        },
        "refusal": {"type": "string", "enum": [reason.value for reason in RefusalReason]},
    },
}
