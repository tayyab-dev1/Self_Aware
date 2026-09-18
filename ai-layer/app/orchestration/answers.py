"""Reading the user's answer to a question, without a model.

An answer fills exactly what was asked: a yes or no, one of the offered options, or one value of
the parameter's type. Anything that cannot be read that way is asked again, never guessed.
"""

from __future__ import annotations

import re
from collections.abc import Sequence
from datetime import date, timedelta
from decimal import Decimal, InvalidOperation

from app.gateway.models import EntityCandidate, ParamMetadata, ParamType
from app.validation.problems import normalise

YES = frozenset(
    {"yes", "y", "yeah", "ok", "okay", "confirm", "sure", "go ahead", "do it", "haan", "han", "ji"}
    | {"jee", "ji haan", "theek hai", "thik hai", "kar do", "kardo", "haan kar do"}
)
NO = frozenset(
    {"no", "n", "nope", "cancel", "stop", "don't", "dont", "nahi", "nahin", "na", "mat karo"}
    | {"rehne do", "ruk jao", "cancel karo"}
)

_NUMBER = re.compile(r"\d[\d,]*(?:\.\d+)?")
_DAY_MONTH_YEAR = re.compile(r"^(\d{1,2})[/-](\d{1,2})[/-](\d{4})$")


def _plain(text: str) -> str:
    return normalise(text).strip(" .!?")


def is_yes(text: str) -> bool:
    return _plain(text) in YES


def is_no(text: str) -> bool:
    return _plain(text) in NO


def choose(text: str, options: Sequence[EntityCandidate]) -> str | None:
    """The option the user picked: its number in the list, or its label (or a unique part of it)."""
    plain = _plain(text)
    if plain.isdigit() and 1 <= int(plain) <= len(options):
        return options[int(plain) - 1].id
    exact = [option.id for option in options if _plain(option.label) == plain]
    if len(exact) == 1:
        return exact[0]
    partial = [option.id for option in options if plain and plain in _plain(option.label)]
    return partial[0] if len(partial) == 1 else None


def read_value(param: ParamMetadata, text: str, today: date) -> str | int | bool | None:
    """The answer as a value of the parameter's type, or None when it cannot be read as one."""
    plain = _plain(text)
    if param.type in (ParamType.decimal, ParamType.integer):
        numbers = _NUMBER.findall(plain)
        if len(numbers) != 1:
            return None
        try:
            amount = Decimal(numbers[0].replace(",", ""))
        except InvalidOperation:
            return None
        if param.type is ParamType.integer:
            return int(amount) if amount == amount.to_integral_value() else None
        return format(amount.normalize(), "f")
    if param.type is ParamType.date:
        return _read_date(plain, today)
    if param.type is ParamType.boolean:
        return True if plain in YES else False if plain in NO else None
    if param.allowed:
        matches = [value for value in param.allowed if value.replace("_", " ") in plain]
        return matches[0] if len(matches) == 1 else None
    return text.strip() if 0 < len(text.strip()) <= 500 else None


def _read_date(plain: str, today: date) -> str | None:
    if plain in ("today", "aaj"):
        return today.isoformat()
    if plain in ("yesterday",):  # "kal" means both yesterday and tomorrow, so it is asked again
        return (today - timedelta(days=1)).isoformat()
    try:
        return date.fromisoformat(plain).isoformat()
    except ValueError:
        pass
    match = _DAY_MONTH_YEAR.match(plain)
    if match:
        day, month, year = (int(part) for part in match.groups())
        try:
            return date(year, month, day).isoformat()
        except ValueError:
            return None
    return None
