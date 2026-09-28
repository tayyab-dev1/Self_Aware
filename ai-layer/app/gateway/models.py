# GENERATED from openapi/agent-gateway.json by scripts/generate_gateway_models.py.
# Do not edit by hand. Change the backend, then run `make contracts`.

from __future__ import annotations

from enum import StrEnum
from typing import Any

from pydantic import AwareDatetime, BaseModel, ConfigDict


class CapabilityVersion(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    id: str
    version: str


class CapabilityVersionsResponse(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    versions: list[CapabilityVersion]


class EntityCandidate(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    context: str | None = None
    id: str
    label: str


class ExecuteOutcome(StrEnum):
    completed = "completed"
    partial = "partial"
    failed = "failed"


class LookupField(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    identifies: bool
    meaning: str
    name: str


class ParamType(StrEnum):
    string = "string"
    integer = "integer"
    decimal = "decimal"
    boolean = "boolean"
    date = "date"


class ParamValue(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    chosen_id: str | None = None
    field: str | None = None
    from_step: int | None = None
    lookup: dict[str, str] | None = None
    raw: str | None = None
    value: Any | None = None


class PlanStep(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    capability_id: str
    capability_version: str
    params: dict[str, ParamValue]
    step: int


class PublishedEffect(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    facts: list[str]


class PublishedParam(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    allowed: list[str]
    default_value: str | None = None
    filled_by: str | None = None
    lookup: str | None = None
    lookup_fields: list[LookupField] | None = None
    meaning: str
    name: str
    required: bool
    resolver: str | None = None
    type: ParamType


class ResolvedEntity(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    id: str
    label: str
    param: str


class SessionCapabilitiesResponse(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    capability_ids: list[str]
    user_id: str


class StepStatus(StrEnum):
    succeeded = "succeeded"
    replayed = "replayed"
    failed = "failed"
    not_run = "not_run"


class AgentErrorResponse(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    candidates: list[EntityCandidate] | None = None
    code: str
    confirmed_count: int | None = None
    current_count: int | None = None
    hint: str | None = None
    message: str
    param: str | None = None
    precondition: str | None = None
    step: int | None = None


class ExecutedStep(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    capability_id: str
    count: int | None = None
    data: Any | None = None
    error: AgentErrorResponse | None = None
    reply: str | None = None
    status: StepStatus
    step: int


class Plan(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    plan_id: str
    session_id: str
    steps: list[PlanStep]


class PreflightRequest(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    plan: Plan


class PreflightStepResult(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    capability_id: str
    count: int | None = None
    line: str | None = None
    pending: bool
    resolved: list[ResolvedEntity]
    step: int
    unit: str | None = None


class PublishedCapability(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    description: str
    disambiguate_from: list[str]
    effect: PublishedEffect
    id: str
    module: str
    params: list[PublishedParam]
    read_only: bool
    version: str


class AgentMetadataResponse(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    capabilities: list[PublishedCapability]


class ExecuteRequest(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    plan: Plan
    sentence: str
    token: str


class ExecuteResponse(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    outcome: ExecuteOutcome
    plan_id: str
    steps: list[ExecutedStep]


class PreflightResponse(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
    )
    confirmation: str | None = None
    expires_at: AwareDatetime
    plan_id: str
    requires_confirmation: bool
    steps: list[PreflightStepResult]
    token: str
    warnings: list[str]
