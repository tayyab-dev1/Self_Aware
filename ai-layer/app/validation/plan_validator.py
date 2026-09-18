"""Checks the planner's answer before anything uses it. Deterministic: no model, no network.

The planner can only suggest. Whatever it names must exist in the current metadata, be allowed for
this user, and have been among the candidates it was shown. Every parameter must be declared, in the
right form and type, and quoted from the user where it names a record or states an amount. Versions
are stamped here from the metadata, never taken from the model. The backend checks all of this
again at preflight; this catches a bad answer before it costs a round trip or reaches the user.
"""

from __future__ import annotations

import re
import uuid
from collections.abc import Collection, Mapping, Sequence
from datetime import date
from decimal import Decimal, InvalidOperation
from typing import Any

from pydantic import ValidationError

from app.gateway.models import CapabilityMetadata, ParamMetadata, ParamType, ParamValue, Plan
from app.gateway.models import PlanStep as GatewayPlanStep
from app.planning.outcomes import (
    NeedsInput,
    NeedsInputOutput,
    ParamOutput,
    PlannedSteps,
    PlannerOutput,
    PlanOutcome,
    Refusal,
    StepOutput,
)
from app.validation.problems import InvalidModelOutputError, Problem, only_users_words

PURPOSE = "plan"

_DECIMAL = re.compile(r"^-?\d+(\.\d+)?$")
_NUMBER_IN_TEXT = re.compile(r"\d[\d,]*(?:\.\d+)?")


def validate_plan(
    output: Mapping[str, Any],
    *,
    sentence: str,
    catalog: Mapping[str, CapabilityMetadata],
    allowed: Collection[str],
    candidates: Collection[str],
    max_steps: int,
    session_id: str,
    plan_id: str | None = None,
    record_words: Collection[str] = (),
) -> PlanOutcome:
    """The planner's answer as a plan, a request for input, or a refusal.

    Raises ``InvalidModelOutputError`` listing every broken rule; nothing is partly accepted.
    ``record_words`` are the domain's generic nouns ("fee", "invoice") a lookup phrase may add to
    the user's own words.
    """
    try:
        parsed = PlannerOutput.model_validate(output)
    except ValidationError as exc:
        raise InvalidModelOutputError(
            PURPOSE,
            [Problem("SCHEMA", f"{error['loc']}: {error['msg']}") for error in exc.errors()],
        ) from exc

    checker = _Checker(sentence, catalog, set(allowed), set(candidates), record_words)
    outcome = checker.outcome(parsed, max_steps, session_id, plan_id or str(uuid.uuid4()))
    if checker.problems:
        raise InvalidModelOutputError(PURPOSE, checker.problems)
    assert outcome is not None
    return outcome


class _Checker:
    def __init__(
        self,
        sentence: str,
        catalog: Mapping[str, CapabilityMetadata],
        allowed: set[str],
        candidates: set[str],
        record_words: Collection[str],
    ) -> None:
        self.sentence = sentence
        self.record_words = record_words
        self.catalog = catalog
        self.allowed = allowed
        self.candidates = candidates
        self.problems: list[Problem] = []
        self.numbers = {_as_decimal(match) for match in _NUMBER_IN_TEXT.findall(sentence)}

    def fail(self, code: str, detail: str, step: int | None = None) -> None:
        self.problems.append(Problem(code, detail, step))

    def outcome(
        self, parsed: PlannerOutput, max_steps: int, session_id: str, plan_id: str
    ) -> PlanOutcome | None:
        filled = {
            "plan": parsed.steps is not None,
            "needs_input": parsed.needs_input is not None,
            "refusal": parsed.refusal is not None,
        }
        if not filled[parsed.outcome] or sum(filled.values()) != 1:
            self.fail("INCONSISTENT_OUTCOME", f"outcome {parsed.outcome} with {filled}")
            return None
        if parsed.refusal is not None:
            return Refusal(parsed.refusal)
        if parsed.needs_input is not None:
            return self.needs_input(parsed.needs_input)
        assert parsed.steps is not None
        if len(parsed.steps) > max_steps:
            self.fail("TOO_MANY_STEPS", f"{len(parsed.steps)} steps, at most {max_steps}")
            return None
        steps = [
            self.step(number, step, parsed.steps) for number, step in enumerate(parsed.steps, 1)
        ]
        if self.problems or any(step is None for step in steps):
            return None
        return PlannedSteps(
            Plan(plan_id=plan_id, session_id=session_id, steps=[s for s in steps if s is not None])
        )

    def capability(self, capability_id: str, step: int | None) -> CapabilityMetadata | None:
        metadata = self.catalog.get(capability_id)
        if metadata is None:
            self.fail("UNKNOWN_CAPABILITY", f"{capability_id!r} is not in the metadata", step)
            return None
        if capability_id not in self.allowed:
            self.fail("NOT_ALLOWED", f"{capability_id} is not in the user's allow-list", step)
            return None
        if capability_id not in self.candidates:
            self.fail("NOT_A_CANDIDATE", f"{capability_id} was not among the candidates", step)
            return None
        return metadata

    def needs_input(self, answer: NeedsInputOutput) -> NeedsInput | None:
        metadata = self.capability(answer.capability_id, 1)
        if metadata is None:
            return None
        askable = {p.name for p in metadata.params if p.required and p.default_value is None}
        given = {param.name for param in answer.params}
        unknown = sorted(set(answer.missing) - askable)
        if unknown:
            self.fail("NOT_A_MISSING_PARAMETER", f"not required without a default: {unknown}")
            return None
        if given & set(answer.missing):
            self.fail(
                "MISSING_BUT_GIVEN", f"{sorted(given & set(answer.missing))} both given and missing"
            )
            return None
        partial = StepOutput(capability_id=answer.capability_id, params=answer.params)
        step = self.step(1, partial, [partial], still_missing=set(answer.missing))
        if step is None or self.problems:
            return None
        return NeedsInput(step, tuple(dict.fromkeys(answer.missing)))

    def step(
        self,
        number: int,
        step: StepOutput,
        all_steps: Sequence[StepOutput],
        still_missing: set[str] | None = None,
    ) -> GatewayPlanStep | None:
        metadata = self.capability(step.capability_id, number)
        if metadata is None:
            return None
        declared = {param.name: param for param in metadata.params}
        params: dict[str, ParamValue] = {}
        for given in step.params:
            if given.name in params:
                self.fail("DUPLICATE_PARAMETER", f"{given.name} given twice", number)
                continue
            param = declared.get(given.name)
            if param is None:
                self.fail("INVENTED_PARAMETER", f"{step.capability_id} has no {given.name}", number)
                continue
            value = self.param(number, param, given, all_steps)
            if value is not None:
                params[given.name] = value
        for param in metadata.params:
            if (
                param.required
                and param.default_value is None
                and param.name not in declared_given(step)
                and param.name not in (still_missing or set())
            ):
                self.fail("MISSING_PARAMETER", f"{param.name} is required", number)
        return GatewayPlanStep(
            step=number,
            capability_id=metadata.id,
            capability_version=metadata.version,
            params=params,
        )

    def param(
        self, number: int, param: ParamMetadata, given: ParamOutput, all_steps: Sequence[StepOutput]
    ) -> ParamValue | None:
        forms = [
            name
            for name, present in (
                ("value", given.value is not None),
                ("words", given.words is not None),
                ("from_step", given.from_step is not None or given.field is not None),
            )
            if present
        ]
        if len(forms) != 1:
            self.fail(
                "MALFORMED_PARAMETER", f"{param.name} needs exactly one form, got {forms}", number
            )
            return None

        if forms == ["from_step"]:
            return self.earlier_step(number, param, given, all_steps)
        if param.resolver is not None:
            if given.words is None:
                self.fail("WRONG_FORM", f"{param.name} is looked up from the user's words", number)
                return None
            if not only_users_words(self.sentence, given.words, self.record_words):
                self.fail("WORDS_NOT_IN_SENTENCE", f"{param.name} words are not the user's", number)
                return None
            return ParamValue(raw=given.words.strip())
        if given.words is not None:
            self.fail("WRONG_FORM", f"{param.name} takes a value, not words", number)
            return None
        return self.value(number, param, given.value)

    def value(self, number: int, param: ParamMetadata, value: object) -> ParamValue | None:
        ok = {
            ParamType.string: isinstance(value, str),
            ParamType.date: isinstance(value, str) and _is_date(value),
            ParamType.boolean: isinstance(value, bool),
            ParamType.integer: isinstance(value, int) and not isinstance(value, bool),
            ParamType.decimal: (isinstance(value, int | float) and not isinstance(value, bool))
            or (isinstance(value, str) and bool(_DECIMAL.match(value))),
        }[param.type]
        if not ok:
            self.fail("WRONG_TYPE", f"{param.name} must be {param.type.value}", number)
            return None
        if param.allowed and value not in param.allowed:
            self.fail(
                "NOT_AN_ALLOWED_VALUE", f"{param.name} must be one of {param.allowed}", number
            )
            return None
        if param.type in (ParamType.integer, ParamType.decimal):
            assert isinstance(value, int | float | str)
            if _as_decimal(str(value)) not in self.numbers:
                self.fail(
                    "VALUE_NOT_IN_SENTENCE", f"{param.name} is not a number the user wrote", number
                )
                return None
        return ParamValue(value=value)

    def earlier_step(
        self, number: int, param: ParamMetadata, given: ParamOutput, all_steps: Sequence[StepOutput]
    ) -> ParamValue | None:
        if given.from_step is None or given.field is None:
            self.fail("MALFORMED_PARAMETER", f"{param.name} needs from_step and field", number)
            return None
        if not 1 <= given.from_step < number:
            self.fail(
                "BAD_STEP_REFERENCE", f"{param.name} refers to step {given.from_step}", number
            )
            return None
        source = self.catalog.get(all_steps[given.from_step - 1].capability_id)
        if source is None or given.field not in source.effect.facts:
            self.fail("UNKNOWN_FIELD", f"step {given.from_step} publishes no {given.field}", number)
            return None
        return ParamValue(from_step=given.from_step, field=given.field)


def declared_given(step: StepOutput) -> set[str]:
    return {param.name for param in step.params}


def _is_date(text: str) -> bool:
    try:
        return date.fromisoformat(text).isoformat() == text
    except ValueError:
        return False


def _as_decimal(text: str) -> Decimal | None:
    try:
        return Decimal(text.replace(",", "")).normalize()
    except InvalidOperation:
        return None
