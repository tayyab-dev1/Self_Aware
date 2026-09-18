"""Validator catch rate: break real planner answers on purpose, and count how many get through.

Each fault takes a recorded answer the validator accepted as a plan, changes one thing the way a
model could get it wrong, and runs the validator again with the same sentence, catalog, allow-list
and candidates (or with one of those narrowed). A fault that does not apply to an answer (no amount
to change, say) is skipped for that answer. The rate is caught / applied; the right-reason rate
also asks that the expected problem code is among the ones reported.
"""

from __future__ import annotations

import copy
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any

from app.gateway.models import CapabilityMetadata, ParamType
from app.validation.plan_validator import validate_plan
from app.validation.problems import InvalidModelOutputError
from eval.measure.pipeline import MAX_STEPS, CaseResult

Answer = dict[str, Any]


@dataclass(frozen=True, slots=True)
class Broken:
    answer: Answer
    expected_code: str
    allowed: Sequence[str] | None = None
    candidates: Sequence[str] | None = None


FaultFn = Callable[[Answer, Mapping[str, CapabilityMetadata]], Broken | None]


def _first_step(answer: Answer) -> dict[str, Any]:
    step: dict[str, Any] = answer["steps"][0]
    return step


def _param_where(
    answer: Answer,
    catalog: Mapping[str, CapabilityMetadata],
    wanted: Callable[[Any, dict[str, Any]], bool],
) -> dict[str, Any] | None:
    step = _first_step(answer)
    declared = {p.name: p for p in catalog[step["capability_id"]].params}
    return next(
        (
            given
            for given in step["params"]
            if given["name"] in declared and wanted(declared[given["name"]], given)
        ),
        None,
    )


def hallucinated_capability(answer: Answer, _: Mapping[str, CapabilityMetadata]) -> Broken:
    _first_step(answer)["capability_id"] = "fee.defaulters.expel"
    return Broken(answer, "UNKNOWN_CAPABILITY")


def capability_not_allowed(answer: Answer, catalog: Mapping[str, CapabilityMetadata]) -> Broken:
    chosen = _first_step(answer)["capability_id"]
    return Broken(answer, "NOT_ALLOWED", allowed=[c for c in catalog if c != chosen])


def not_a_candidate(answer: Answer, catalog: Mapping[str, CapabilityMetadata]) -> Broken:
    chosen = _first_step(answer)["capability_id"]
    return Broken(answer, "NOT_A_CANDIDATE", candidates=[c for c in catalog if c != chosen])


def invented_parameter(answer: Answer, _: Mapping[str, CapabilityMetadata]) -> Broken:
    _first_step(answer)["params"].append({"name": "priority", "value": "urgent"})
    return Broken(answer, "INVENTED_PARAMETER")


def missing_required_parameter(
    answer: Answer, catalog: Mapping[str, CapabilityMetadata]
) -> Broken | None:
    given = _param_where(answer, catalog, lambda p, _: p.required and p.default_value is None)
    if given is None:
        return None
    _first_step(answer)["params"].remove(given)
    return Broken(answer, "MISSING_PARAMETER")


def wrong_type(answer: Answer, catalog: Mapping[str, CapabilityMetadata]) -> Broken | None:
    given = _param_where(answer, catalog, lambda p, g: p.type is ParamType.string and "value" in g)
    if given is None:
        return None
    given["value"] = 12345
    return Broken(answer, "WRONG_TYPE")


def words_not_from_the_user(
    answer: Answer, catalog: Mapping[str, CapabilityMetadata]
) -> Broken | None:
    given = _param_where(answer, catalog, lambda p, g: p.resolver is not None and "words" in g)
    if given is None:
        return None
    given["words"] = "Zubair Qureshi of Class 9 Purple"
    return Broken(answer, "WORDS_NOT_IN_SENTENCE")


def amount_not_from_the_user(
    answer: Answer, catalog: Mapping[str, CapabilityMetadata]
) -> Broken | None:
    given = _param_where(answer, catalog, lambda p, g: p.type is ParamType.decimal and "value" in g)
    if given is None:
        return None
    given["value"] = 987654
    return Broken(answer, "VALUE_NOT_IN_SENTENCE")


def value_outside_allowed(
    answer: Answer, catalog: Mapping[str, CapabilityMetadata]
) -> Broken | None:
    given = _param_where(answer, catalog, lambda p, g: bool(p.allowed) and "value" in g)
    if given is None:
        return None
    given["value"] = "telepathy"
    return Broken(answer, "NOT_AN_ALLOWED_VALUE")


def forward_step_reference(
    answer: Answer, catalog: Mapping[str, CapabilityMetadata]
) -> Broken | None:
    given = _param_where(answer, catalog, lambda _, g: "value" in g or "words" in g)
    if given is None:
        return None
    for form in ("value", "words"):
        given.pop(form, None)
    given.update({"from_step": 2, "field": "receipt_number"})
    return Broken(answer, "BAD_STEP_REFERENCE")


def too_many_steps(answer: Answer, _: Mapping[str, CapabilityMetadata]) -> Broken:
    answer["steps"] = [copy.deepcopy(_first_step(answer)) for _ in range(MAX_STEPS + 1)]
    return Broken(answer, "TOO_MANY_STEPS")


def unexpected_field(answer: Answer, _: Mapping[str, CapabilityMetadata]) -> Broken:
    answer["confidence"] = 0.97
    return Broken(answer, "SCHEMA")


def contradictory_outcome(answer: Answer, _: Mapping[str, CapabilityMetadata]) -> Broken:
    answer["refusal"] = "no_matching_capability"
    return Broken(answer, "INCONSISTENT_OUTCOME")


FAULTS: dict[str, FaultFn] = {
    "hallucinated capability id": hallucinated_capability,
    "capability outside the allow-list": capability_not_allowed,
    "capability not among the candidates": not_a_candidate,
    "invented parameter": invented_parameter,
    "required parameter left out": missing_required_parameter,
    "value of the wrong type": wrong_type,
    "value outside the allowed list": value_outside_allowed,
    "record name the user never wrote": words_not_from_the_user,
    "amount the user never wrote": amount_not_from_the_user,
    "reference to a later step": forward_step_reference,
    "more than three steps": too_many_steps,
    "field outside the schema": unexpected_field,
    "answer contradicting its outcome": contradictory_outcome,
}


@dataclass(slots=True)
class FaultTally:
    applied: int = 0
    caught: int = 0
    right_reason: int = 0
    escaped: list[str] = field(default_factory=list)


def inject_faults(
    results: Sequence[CaseResult], catalog: Mapping[str, CapabilityMetadata]
) -> dict[str, dict[str, FaultTally]]:
    """Per language ("all", "en", "ur-Latn"), per fault: how many were applied and caught."""
    tallies: dict[str, dict[str, FaultTally]] = {}
    for result in results:
        if result.outcome != "plan" or result.raw_plan is None:
            continue
        for name, fault in FAULTS.items():
            broken = fault(copy.deepcopy(dict(result.raw_plan)), catalog)
            if broken is None:
                continue
            try:
                validate_plan(
                    broken.answer,
                    sentence=result.case.text,
                    catalog=catalog,
                    allowed=broken.allowed if broken.allowed is not None else list(catalog),
                    candidates=broken.candidates
                    if broken.candidates is not None
                    else result.plannable,
                    max_steps=MAX_STEPS,
                    session_id="measure",
                )
                caught, codes = False, []
            except InvalidModelOutputError as exc:
                caught, codes = True, exc.codes
            for group in ("all", result.case.lang):
                tally = tallies.setdefault(group, {}).setdefault(name, FaultTally())
                tally.applied += 1
                tally.caught += caught
                tally.right_reason += broken.expected_code in codes
                if not caught:
                    tally.escaped.append(result.case.text)
    return tallies
