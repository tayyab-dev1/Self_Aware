"""A plan built from the pieces a message was read into, with no second model call.

Decompose reports what the user said as pieces ({"student_name": "Hassan Ali", "amount": "2000",
"payment_method": "cash", "date": "today"}); the chooser picks the capability; this puts the two
together into the same answer shape the planner would have produced, which the validator then checks
exactly as it checks the planner's (README decision 79).

It only ever fills what it is sure of. A piece it cannot turn into the parameter's type, an allowed
value it cannot match, or anything it has no rule for, gives ``None``: the caller then asks the
planner, so a hard message is planned as carefully as before and only easy ones skip the call.
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from datetime import date
from types import MappingProxyType
from typing import Any

from app.gateway.models import ParamType, PublishedCapability, PublishedParam

# What a user says for "the day I mean today", in both languages.
TODAY_WORDS = frozenset({"today", "aaj", "aj"})
_NUMBER = re.compile(r"^\d[\d,]*(\.\d+)?$")
_ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


class CannotFill(Exception):
    """A piece the assembler has no safe rule for: the planner is asked instead."""


def assemble(
    capability: PublishedCapability,
    pieces: Mapping[str, str],
    today: date,
    chosen: Mapping[str, str] = MappingProxyType({}),
) -> dict[str, Any] | None:
    """The planner's answer for one capability, or None when a piece cannot be used safely.

    ``chosen`` holds the allowed value Jev read the user's words as, for the parameters whose words
    were not already one of them ("naqad" for ``cash``); see ``values.py``.
    """
    try:
        params, missing = _params(capability, pieces, today, chosen)
    except CannotFill:
        return None
    step = {"capability_id": capability.id, "params": params}
    if missing:
        return {"outcome": "needs_input", "needs_input": {**step, "missing": sorted(missing)}}
    return {"outcome": "plan", "steps": [step]}


def _params(
    capability: PublishedCapability,
    pieces: Mapping[str, str],
    today: date,
    chosen: Mapping[str, str],
) -> tuple[list[dict[str, Any]], list[str]]:
    filled: list[dict[str, Any]] = []
    missing: list[str] = []
    used: set[str] = set()
    for param in capability.params:
        if param.resolver:
            given, from_pieces = _lookup(param, pieces)
            if from_pieces & used:
                # Two records read from the same words, as a class and a section both would from
                # "class 5": which one the user meant is a judgement, so the planner makes it.
                raise CannotFill(f"{param.name} reads the same pieces as another parameter")
            used |= from_pieces
        else:
            given = _value(param, pieces, today, chosen)
        if given is not None:
            filled.append({"name": param.name, **given})
        elif param.required and param.default_value is None:
            missing.append(param.name)
    return filled, missing


def _lookup(
    param: PublishedParam, pieces: Mapping[str, str]
) -> tuple[dict[str, Any] | None, set[str]]:
    """The parts of the record this parameter looks up, and which pieces they came from."""
    declared = param.lookup_fields or []
    parts = {
        field.name: pieces[field.name].strip()
        for field in declared
        if pieces.get(field.name, "").strip()
    }
    if not parts:
        return None, set()
    if any(field.identifies for field in declared if field.name in parts):
        return {"lookup": parts}, set(parts)
    # Only narrowing parts, as in "everyone overdue in Grade 4": these name no record on their own,
    # so they travel as words and the backend offers what they match (decision 78).
    return {"words": " ".join(parts.values())}, set(parts)


def _value(
    param: PublishedParam, pieces: Mapping[str, str], today: date, chosen: Mapping[str, str]
) -> dict[str, Any] | None:
    said = pieces.get(param.filled_by or "", "").strip() if param.filled_by else ""
    if not said:
        return None
    if param.allowed:
        return {"value": chosen.get(param.name) or _allowed_value(param, said)}
    if param.type is ParamType.date:
        return {"value": _date(said, today)}
    if param.type in (ParamType.decimal, ParamType.integer):
        return {"value": _number(param, said)}
    if param.type is ParamType.string:
        return {"value": said}
    raise CannotFill(f"{param.name} is a {param.type.value}")


def _allowed_value(param: PublishedParam, said: str) -> str:
    """The allowed value the user's word is, or nothing: guessing here changes what happens."""
    wanted = said.strip().lower().replace(" ", "_").replace("-", "_")
    for allowed in param.allowed:
        if wanted == allowed.lower():
            return allowed
    raise CannotFill(f"{said!r} is not one of {param.allowed}")


def _date(said: str, today: date) -> str:
    if said.strip().lower() in TODAY_WORDS:
        return today.isoformat()
    if _ISO_DATE.match(said.strip()):
        return said.strip()
    raise CannotFill(f"{said!r} is not a date this can work out")


def _number(param: PublishedParam, said: str) -> float | int:
    if not _NUMBER.match(said.strip()):
        raise CannotFill(f"{said!r} is not a number")
    plain = said.strip().replace(",", "")
    if param.type is ParamType.integer:
        if "." in plain:
            raise CannotFill(f"{said!r} is not a whole number")
        return int(plain)
    return float(plain) if "." in plain else int(plain)
