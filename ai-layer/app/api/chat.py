"""``POST /chat``: one turn of the conversation.

The caller sends the user's own bearer token, which is forwarded to the backend and never logged,
and exactly one of: a ``message`` (a new sentence, or an answer to the last question), a ``choice``
(an option's id), or ``confirm`` (true runs the confirmed plan, false cancels whatever is waiting).

Every reply is one of four types: ``answer``, ``question`` (with ``options`` when there is a
choice), ``confirmation`` (to be answered with ``confirm``), or ``refusal``. The text comes from the
backend or from fixed wording, never from a model.

With ``"trace": true`` (and ``AI_LAYER_CHAT_TRACE_ENABLED`` on) the reply also carries ``trace``:
every pipeline stage this turn went through, with what it was given, what it did and what it
produced, for the test page to show. See ``app.core.trace``.
"""

from __future__ import annotations

from typing import Annotated, Any, Literal, Self

import structlog
from fastapi import APIRouter, Header, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.api.dependencies import AppSettings, Resources
from app.core.trace import StageStatus, Trace, collect
from app.gateway.errors import GatewayError, GatewayRejectedError
from app.orchestration.orchestrator import ChatTurn

log = structlog.get_logger(__name__)

router = APIRouter(tags=["chat"])

_PLAIN_ID = r"^[A-Za-z0-9_-]{1,64}$"


class ChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    session_id: str | None = Field(default=None, pattern=_PLAIN_ID)
    message: str | None = Field(default=None, min_length=1, max_length=1000)
    choice: str | None = Field(default=None, min_length=1, max_length=64)
    confirm: bool | None = None
    trace: bool = False

    @model_validator(mode="after")
    def _exactly_one_kind_of_turn(self) -> Self:
        given = [self.message is not None, self.choice is not None, self.confirm is not None]
        if sum(given) != 1:
            raise ValueError("send exactly one of message, choice or confirm")
        return self


class ChatOption(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    id: str
    label: str
    context: str | None = None


class ChatStep(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    step: int
    capability_id: str
    status: str
    reply: str | None = None
    count: int | None = None
    data: Any = None


class TraceStage(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    key: str
    title: str
    actor: str
    does: str
    input: Any = None
    output: Any = None
    status: StageStatus
    note: str | None = None
    duration_ms: float | None = None


class ChatResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    session_id: str
    type: Literal["answer", "question", "confirmation", "refusal"]
    text: str
    code: str | None = None
    plan_id: str | None = None
    options: list[ChatOption] = Field(default_factory=list)
    steps: list[ChatStep] = Field(default_factory=list)
    trace: list[TraceStage] | None = None


def _bearer_token(authorization: str | None) -> str:
    scheme, _, token = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED,
            "Send the user's token as Authorization: Bearer <token>",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return token.strip()


@router.post(
    "/chat",
    responses={
        status.HTTP_401_UNAUTHORIZED: {
            "description": "No token, or the backend does not accept it"
        },
        status.HTTP_503_SERVICE_UNAVAILABLE: {
            "description": "Chat or the backend is not available"
        },
    },
)
async def chat(
    body: ChatRequest,
    resources: Resources,
    settings: AppSettings,
    authorization: Annotated[str | None, Header()] = None,
) -> ChatResponse:
    token = _bearer_token(authorization)
    if resources.chat is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Chat is not available")
    turn = ChatTurn(message=body.message, choice=body.choice, confirm=body.confirm)
    trace: Trace | None = None
    try:
        if body.trace and settings.chat_trace_enabled:
            with collect() as trace:
                result = await resources.chat.handle(body.session_id, turn, token)
        else:
            result = await resources.chat.handle(body.session_id, turn, token)
    except GatewayRejectedError as rejected:
        if rejected.status in (status.HTTP_401_UNAUTHORIZED, status.HTTP_403_FORBIDDEN):
            raise HTTPException(rejected.status, rejected.message) from rejected
        raise HTTPException(
            status.HTTP_502_BAD_GATEWAY, "The backend refused the request"
        ) from rejected
    except GatewayError as exc:
        log.warning("chat_backend_unavailable", error=type(exc).__name__)
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, "The backend is not available"
        ) from exc

    reply = result.reply
    return ChatResponse(
        session_id=result.session_id,
        type=reply.type,
        text=reply.text,
        code=reply.code,
        plan_id=reply.plan_id,
        options=[
            ChatOption(id=option.id, label=option.label, context=option.context)
            for option in reply.options
        ],
        steps=[
            ChatStep(
                step=step.step,
                capability_id=step.capability_id,
                status=step.status,
                reply=step.reply,
                count=step.count,
                data=step.data,
            )
            for step in reply.steps
        ],
        trace=None if trace is None else [TraceStage(**stage) for stage in trace.as_json()],
    )
