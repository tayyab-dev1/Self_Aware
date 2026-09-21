"""Everything the chat says, built from fixed wording, metadata and the backend's own text.

No model writes here (CLAUDE.md invariant 3). A confirmation, a step's reply, a precondition's hint
and the candidates' labels all come from the backend word for word; this module only frames them.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any, Literal

from app.gateway.models import (
    AgentErrorResponse,
    EntityCandidate,
    ExecuteOutcome,
    ExecuteResponse,
    ParamMetadata,
    ParamType,
    StepStatus,
)
from app.planning.outcomes import RefusalReason

ReplyType = Literal["answer", "question", "confirmation", "refusal"]


@dataclass(frozen=True, slots=True)
class StepResult:
    step: int
    capability_id: str
    status: str
    reply: str | None
    count: int | None = None
    data: Any = None


@dataclass(frozen=True, slots=True)
class ChatReply:
    type: ReplyType
    text: str
    code: str | None = None
    options: tuple[EntityCandidate, ...] = ()
    plan_id: str | None = None
    steps: tuple[StepResult, ...] = field(default_factory=tuple)


# --- Refusals ---------------------------------------------------------------------------------

_REFUSALS: dict[str, str] = {
    RefusalReason.NO_MATCHING_CAPABILITY: "Sorry, that isn't something I can do here.",
    RefusalReason.NOT_A_REQUEST: (
        "Tell me what you would like to do, for example: show the overdue fees for Class 5 Blue."
    ),
    RefusalReason.TOO_MANY_ACTIONS: (
        "That is more than three things at once. Please ask for them in smaller parts."
    ),
    "MODEL_FAILED": "Sorry, I couldn't work out what to do. Please try saying it another way.",
    "MODEL_UNAVAILABLE": "Sorry, I can't understand requests right now. Please try again shortly.",
    "BACKEND_UNAVAILABLE": "The school system is not answering right now. Nothing was changed.",
    "NOT_READY": "I'm still starting up. Please try again in a moment.",
    "NOT_PERMITTED": "You don't have permission to do that.",
    "STALE_VERSION": "The system was just updated. Please send your message again.",
    "INVALID_PLAN": "Sorry, I couldn't prepare that correctly. Please try saying it another way.",
    "TOO_MANY_ATTEMPTS": "I still couldn't find it, so I've stopped. Nothing was changed.",
}


def refusal(code: str, *, plan_id: str | None = None) -> ChatReply:
    return ChatReply("refusal", _REFUSALS[code], code=str(code), plan_id=plan_id)


def backend_refusal(error: AgentErrorResponse, *, plan_id: str | None) -> ChatReply:
    """A refusal whose words come from the backend: a precondition's hint, or its message."""
    if error.code in _REFUSALS:
        return refusal(error.code, plan_id=plan_id)
    if error.code == "NOT_IMPLEMENTED":
        return ChatReply("refusal", "That isn't available yet.", code=error.code, plan_id=plan_id)
    text = error.hint or error.message
    return ChatReply("refusal", text, code=error.code, plan_id=plan_id)


# --- Questions --------------------------------------------------------------------------------


def which_one(raw: str, options: Sequence[EntityCandidate], *, plan_id: str) -> ChatReply:
    return ChatReply(
        "question",
        f'More than one record matches "{raw}". Which one do you mean?',
        code="AMBIGUOUS_ENTITY",
        options=tuple(options),
        plan_id=plan_id,
    )


def not_found(
    raw: str, error: AgentErrorResponse, *, plan_id: str, lookup: str | None = None
) -> ChatReply:
    """Ask for the name again, saying what the record is found by when the backend says it."""
    # The backend's message ("Step 1: no section matches ...") is for developers, not repeated. The
    # lookup ("the student's name, optionally with ...") is the backend's own wording for users.
    ask = (
        f"Please type {lookup.rstrip('.')}."
        if lookup
        else ("Please type the name again, as the school's records write it.")
    )
    return ChatReply(
        "question",
        f'I couldn\'t find "{raw}". {ask}',
        code="NOT_FOUND",
        plan_id=plan_id,
    )


def missing_value(param: ParamMetadata, *, plan_id: str, again: bool = False) -> ChatReply:
    lead = "I couldn't read that. " if again else "I need one more detail. "
    choices = f" ({', '.join(value.replace('_', ' ') for value in param.allowed)})"
    hint = {
        ParamType.date: " (a date, for example 2026-09-14 or today)",
        ParamType.decimal: " (an amount)",
        ParamType.integer: " (a number)",
        ParamType.boolean: " (yes or no)",
    }.get(param.type, "")
    return ChatReply(
        "question",
        f"{lead}{param.meaning}{choices if param.allowed else hint}?",
        code="NEEDS_INPUT",
        plan_id=plan_id,
    )


def pick_again(reply: ChatReply) -> ChatReply:
    return ChatReply(
        "question",
        "Please pick one of the options, by its number or its name. " + reply.text,
        code=reply.code,
        options=reply.options,
        plan_id=reply.plan_id,
    )


# --- Confirmations and answers ----------------------------------------------------------------


def confirmation(text: str, *, plan_id: str, lead: str = "") -> ChatReply:
    return ChatReply("confirmation", f"{lead}{text}", code="CONFIRM", plan_id=plan_id)


CONFIRM_AGAIN_EXPIRED = "That confirmation expired, so here it is again. "
CONFIRM_AGAIN_CHANGED = "The numbers changed since you confirmed, so please check again. "


def cancelled(*, plan_id: str | None) -> ChatReply:
    return ChatReply("answer", "Cancelled. Nothing was changed.", code="CANCELLED", plan_id=plan_id)


def nothing_pending() -> ChatReply:
    return ChatReply("answer", "There is nothing waiting for an answer.", code="NOTHING_PENDING")


def session_expired() -> ChatReply:
    return ChatReply(
        "answer",
        "That question expired because it waited too long for an answer, so nothing was done. "
        "Please send your message again.",
        code="SESSION_EXPIRED",
    )


def executed(result: ExecuteResponse) -> ChatReply:
    lines: list[str] = []
    steps: list[StepResult] = []
    several = len(result.steps) > 1
    for step in result.steps:
        prefix = f"{step.step}. " if several else ""
        if step.status in (StepStatus.succeeded, StepStatus.replayed) and step.reply:
            lines.append(prefix + step.reply)
        elif step.status is StepStatus.failed and step.error is not None:
            lines.append(
                f"{prefix}This did not go through: {step.error.hint or step.error.message}"
            )
        elif step.status is StepStatus.not_run:
            lines.append(f"{prefix}Not done, because an earlier step did not go through.")
        steps.append(
            StepResult(
                step=step.step,
                capability_id=step.capability_id,
                status=step.status.value,
                reply=step.reply,
                count=step.count,
                data=step.data,
            )
        )
    return ChatReply(
        "answer",
        "\n".join(lines),
        code=result.outcome.value if result.outcome is not ExecuteOutcome.completed else None,
        plan_id=result.plan_id,
        steps=tuple(steps),
    )
