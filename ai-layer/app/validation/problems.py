"""How validation reports what is wrong with a model's output."""

from __future__ import annotations

import re
from collections.abc import Collection, Sequence
from dataclasses import dataclass

from app.llm.runner import ModelOutputError


@dataclass(frozen=True, slots=True)
class Problem:
    """One broken rule. ``code`` is stable for metrics; ``detail`` never quotes the sentence."""

    code: str
    detail: str
    step: int | None = None

    def describe(self) -> str:
        where = f"step {self.step}: " if self.step is not None else ""
        return f"{self.code} ({where}{self.detail})"


class InvalidModelOutputError(ModelOutputError):
    """The model answered in the right shape, but broke a rule. Nothing it wrote is used."""

    def __init__(self, purpose: str, problems: Sequence[Problem]) -> None:
        self.purpose = purpose
        self.problems = tuple(problems)
        super().__init__(
            f"The {purpose} output broke {len(problems)} rule(s): "
            + "; ".join(problem.describe() for problem in problems)
        )

    @property
    def codes(self) -> list[str]:
        return [problem.code for problem in self.problems]


_SPACE = re.compile(r"\s+")
_EDGE_PUNCTUATION = ".,;:!?\"'()[]"


def normalise(text: str) -> str:
    """Case-folded, single-spaced, for comparing a quoted span with the text it came from."""
    return _SPACE.sub(" ", text.casefold()).strip()


_WORD = re.compile(r"[^\W_]+")
_POSSESSIVE = re.compile("(?<=\\w)['\u2019]s\\b")

# Words that join the user's words into a lookup phrase without naming anything: "Zain's September
# bill", "Ali walon ka duplicate bill". Domain nouns ("fee", "invoice") come from the caller.
CONNECTING_WORDS = frozenset(
    {"a", "an", "the", "of", "for", "from", "ka", "ki", "ke", "wala", "wali"}
)


def words_of(text: str) -> list[str]:
    return _WORD.findall(_POSSESSIVE.sub("", text.casefold()))


def only_users_words(text: str, phrase: str, allowed_extra: Collection[str] = ()) -> bool:
    """Whether every word of ``phrase`` is one the user wrote, apart from connecting words.

    The phrase may put the user's words in a different order, drop some, or add "of" or "'s", but
    it cannot bring in a name or a number the user never typed.
    """
    said = set(words_of(text))
    extra = CONNECTING_WORDS | {word.casefold() for word in allowed_extra}
    words = words_of(phrase)
    return bool(set(words) & said) and all(word in said or word in extra for word in words)


def quotes(text: str, span: str) -> bool:
    """Whether ``span`` appears in ``text`` as the user wrote it, ignoring case and spacing."""
    wanted = normalise(span).strip(_EDGE_PUNCTUATION)
    return bool(wanted) and wanted in normalise(text)
