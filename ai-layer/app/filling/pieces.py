"""The pieces a message is read into, and what each one is for.

Decompose reads a message once, without knowing which capability will handle it, so it can only
report what the user said: an amount, a date, a student's name, a class. The backend says which
parameter each piece fills (``@AgentParam(filledBy)``) and which parts each lookup searches by
(``EntityResolver.fields()``), so the vocabulary is read from the catalog, never hand-written here:
add a capability with a new piece and decompose is told about it on the next sync.

``assembler.py`` then builds a plan from the pieces, with no second model call (README decision 79).
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping

from app.gateway.models import CapabilityMetadata

# Kept short so the prompt stays readable: a piece is described by the parameters that take it.
MAX_MEANINGS = 2


def vocabulary(catalog: Iterable[CapabilityMetadata]) -> dict[str, str]:
    """Every piece the published capabilities can use, with what it means, in name order."""
    meanings: dict[str, list[str]] = {}
    for capability in catalog:
        for param in capability.params:
            for name, meaning in _pieces_of(param):
                said = meanings.setdefault(name, [])
                if meaning not in said:
                    said.append(meaning)
    return {name: "; ".join(said[:MAX_MEANINGS]) for name, said in sorted(meanings.items())}


def _pieces_of(param: object) -> list[tuple[str, str]]:
    """A parameter's own piece, or the parts of the record it looks up."""
    fields = getattr(param, "lookup_fields", None) or []
    if fields:
        return [(field.name, field.meaning) for field in fields]
    filled_by = getattr(param, "filled_by", None)
    meaning = getattr(param, "meaning", "")
    allowed = getattr(param, "allowed", None) or []
    if allowed:
        # A piece that is one of a few kinds, not a name: saying so stops "Grade 4" landing in the
        # piece that says whether a list covers a class or a section.
        meaning = f"{meaning} (one of: {', '.join(allowed)})"
    return [(filled_by, meaning)] if filled_by else []


def pieces_prompt(vocabulary_of: Mapping[str, str]) -> str:
    """The vocabulary as the lines decompose is given."""
    return "\n".join(f"- {name}: {meaning}" for name, meaning in vocabulary_of.items())
