"""The capability chooser: Jev decides which retrieved candidates the planner sees.

For each intent, Jev gets one choice question whose options are the candidates, each described by
what it does, what it is not for and what it needs (its parameters, and what each looked-up record
is found by), plus "none" for a request no candidate carries out. It answers with a probability for
every option. The shortlist keeps, per intent, the options that together hold most of the
probability, so a clear pick leaves the planner one candidate to fill in, and a close call leaves it
the two or three that are close. When "none" wins every intent the shortlist is empty and the
sentence is refused without a planner call.

The planner still decides the plan, and the validator still checks it against the shortlist. This
is an experiment behind ``AI_LAYER_CHOOSER_ENABLED``; ``make measure-jev`` compares it with
planning from every candidate.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.choosing.jev import DecisionModel, DecisionRequest
from app.core import trace
from app.gateway.models import CapabilityMetadata
from app.llm.runner import ModelOutputError

# Pinned, never "jev-latest": a silent model update would change behaviour with no deploy.
CHOOSER_MODEL = "jev-1.13.0"
NONE = "none"
NONE_MEANING = (
    "None of the other options carries out this request: the school system cannot do it here, or "
    "it is not a request at all."
)
INSTRUCTIONS = (
    'A school office staff member asked: "{intent}". Which option does exactly the action asked '
    "for? Judge by what each option does and what it says it is not for. The request may leave out "
    "details the option needs, such as which student, class, invoice or amount: those are asked "
    "for later, so the option still fits. But an option that does a different action on the same "
    "thing does not fit (proposing something is not approving, rejecting, returning or paying it "
    'out). Choose "none" when no option does the action asked for, or it is not a request.'
)
# Changing how a question or an option is written changes what Jev answers: recordings hash this.
SPECIFICATION = "\n".join([INSTRUCTIONS, NONE_MEANING, "options: does, kind, needs (v2)"])

# An intent whose "none" holds at least this much adds nothing to the shortlist.
NONE_WINS_AT = 0.6
# Per intent, options are kept in order of probability until they hold this share of the
# probability that is not "none", at most MAX_KEPT of them; an option below MIN_KEPT never is.
KEEP_SHARE = 0.9
MAX_KEPT = 3
MIN_KEPT = 0.05


def option(capability: CapabilityMetadata) -> dict[str, Any]:
    """How one candidate is described to Jev."""
    needs: dict[str, str] = {}
    for param in capability.params:
        need = param.meaning.rstrip(".")
        if param.lookup:
            need += f"; found by {param.lookup}"
        if param.allowed:
            need += f"; one of {', '.join(param.allowed)}"
        if not param.required:
            need += " (optional)"
        needs[param.name] = need
    return {
        "does": capability.description,
        "kind": "shows information only" if capability.read_only else "changes records",
        "needs": needs or "nothing",
    }


class _ChoiceAnswer(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    type: Literal["choice"]
    choice: str
    confidence: float = Field(ge=0, le=1)
    probabilities: dict[str, float]


@dataclass(frozen=True, slots=True)
class IntentChoice:
    intent: str
    choice: str  # a candidate id, or NONE
    confidence: float
    probabilities: Mapping[str, float]

    def ranked(self) -> list[tuple[str, float]]:
        """The candidates (not "none"), most probable first."""
        return sorted(
            ((c, p) for c, p in self.probabilities.items() if c != NONE), key=lambda cp: -cp[1]
        )

    @property
    def none(self) -> float:
        return self.probabilities.get(NONE, 0.0)

    def kept(self) -> list[str]:
        if self.none >= NONE_WINS_AT:
            return []
        ranked = self.ranked()
        rest = sum(p for _, p in ranked)
        kept: list[str] = []
        held = 0.0
        for capability_id, probability in ranked:
            if kept and (held >= KEEP_SHARE * rest or len(kept) == MAX_KEPT):
                break
            if kept and probability < MIN_KEPT:
                break
            kept.append(capability_id)
            held += probability
        return kept


@dataclass(frozen=True, slots=True)
class Choice:
    intents: tuple[IntentChoice, ...]

    @property
    def shortlist(self) -> tuple[str, ...]:
        """What the planner sees: each intent's kept candidates, in intent order, no repeats."""
        return tuple(dict.fromkeys(c for intent in self.intents for c in intent.kept()))

    def describe(self) -> dict[str, Any]:
        return {
            "per_intent": [
                {
                    "intent": intent.intent,
                    "choice": intent.choice,
                    "confidence": intent.confidence,
                    "probabilities": dict(
                        sorted(intent.probabilities.items(), key=lambda cp: -cp[1])
                    ),
                    "kept": intent.kept(),
                }
                for intent in self.intents
            ],
            "shortlist_for_the_planner": list(self.shortlist),
        }


def question_id(number: int) -> str:
    return f"intent_{number}"


def decision_request(
    intents: Sequence[str], candidates: Sequence[CapabilityMetadata]
) -> DecisionRequest:
    criteria: dict[str, Any] = {candidate.id: option(candidate) for candidate in candidates}
    criteria[NONE] = NONE_MEANING
    return DecisionRequest(
        model=CHOOSER_MODEL,
        state={"requests": list(intents)},
        questions={
            question_id(n): {
                "type": "choice",
                "instructions": INSTRUCTIONS.format(intent=intent),
                "criteria": criteria,
            }
            for n, intent in enumerate(intents, start=1)
        },
        purpose="choose",
        system=SPECIFICATION,
    )


def parse_choice(
    answer: Mapping[str, Any], intents: Sequence[str], options: Sequence[str]
) -> Choice:
    """Jev's answer, checked: every question answered, with one of its options, and nothing else."""
    answers = answer.get("answers")
    if not isinstance(answers, Mapping):
        raise ModelOutputError("The choose call returned no answers")
    expected = {question_id(n) for n in range(1, len(intents) + 1)}
    if set(answers) != expected:
        raise ModelOutputError("The choose call did not answer exactly the questions asked")
    allowed = set(options)
    chosen = []
    for n, intent in enumerate(intents, start=1):
        try:
            parsed = _ChoiceAnswer.model_validate(answers[question_id(n)])
        except ValidationError as exc:
            raise ModelOutputError("The choose call returned an answer of the wrong shape") from exc
        if parsed.choice not in allowed or not set(parsed.probabilities) <= allowed:
            raise ModelOutputError("The choose call answered with an option it was not given")
        chosen.append(IntentChoice(intent, parsed.choice, parsed.confidence, parsed.probabilities))
    return Choice(tuple(chosen))


class CapabilityChooser:
    def __init__(self, model: DecisionModel) -> None:
        self._model = model

    async def choose(
        self, intents: Sequence[str], candidates: Sequence[CapabilityMetadata]
    ) -> Choice:
        """Raises ``ModelError`` when the call fails or its answer breaks a rule."""
        request = decision_request(intents, candidates)
        with trace.stage(
            "choose",
            "Choose the capability",
            trace.by_model(CHOOSER_MODEL),
            "Picks, for each intent, which candidate carries it out (or none), with a probability "
            "for every option. The planner then sees only the candidates that hold most of the "
            "probability.",
        ) as stage:
            stage.input = {"intents": list(intents), "candidates": [c.id for c in candidates]}
            answer = await self._model.decide(request)
            choice = parse_choice(answer, intents, [*(c.id for c in candidates), NONE])
            stage.output = choice.describe()
        return choice
