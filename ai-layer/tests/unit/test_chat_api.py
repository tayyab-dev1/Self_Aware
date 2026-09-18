"""``POST /chat`` over HTTP, with the orchestrator replaced."""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

import httpx
import pytest

from app.core import trace
from app.core.settings import Settings
from app.gateway.errors import GatewayRejectedError, GatewayUnavailableError
from app.gateway.models import AgentErrorResponse, EntityCandidate
from app.main import create_app
from app.orchestration.orchestrator import ChatResult, ChatTurn
from app.resources import AppResources
from app.response.replies import ChatReply
from tests.unit.test_health_api import FakeIndex


class FakeGateway:
    async def get_metadata(self) -> Any:
        raise AssertionError("not used")

    async def aclose(self) -> None:
        return None


class FakeChat:
    def __init__(self, outcome: ChatResult | Exception) -> None:
        self.outcome = outcome
        self.calls: list[tuple[str | None, ChatTurn, str]] = []

    async def handle(self, session_id: str | None, turn: ChatTurn, user_token: str) -> ChatResult:
        self.calls.append((session_id, turn, user_token))
        with trace.stage("session", "Who is asking", trace.BY_BACKEND, "Checks the token.") as s:
            s.input = {"turn": turn.message}
            s.output = {"user_id": "7"}
        if isinstance(self.outcome, Exception):
            raise self.outcome
        return self.outcome


@asynccontextmanager
async def client_for(
    chat: FakeChat | None, settings: Settings | None = None
) -> AsyncIterator[httpx.AsyncClient]:
    async def resources(_: Settings) -> AppResources:
        return AppResources(index=FakeIndex(), gateway=FakeGateway(), chat=chat)  # type: ignore[arg-type]

    app = create_app(settings or Settings(), resources_factory=resources)
    async with (
        app.router.lifespan_context(app),
        httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://ai.test"
        ) as client,
    ):
        yield client


QUESTION = ChatResult(
    session_id="s1",
    reply=ChatReply(
        "question",
        'More than one record matches "class 5". Which one do you mean?',
        code="AMBIGUOUS_ENTITY",
        options=(EntityCandidate(id="2", label="Class 5 Blue", context="12 students"),),
        plan_id="p1",
    ),
)
AUTH = {"Authorization": "Bearer user-token"}


async def test_a_turn_goes_to_the_orchestrator_with_the_users_token_and_comes_back_typed() -> None:
    chat = FakeChat(QUESTION)

    async with client_for(chat) as client:
        response = await client.post("/chat", json={"message": "class 5 ko reminder"}, headers=AUTH)

    assert response.status_code == 200
    assert response.json() == {
        "session_id": "s1",
        "type": "question",
        "text": 'More than one record matches "class 5". Which one do you mean?',
        "code": "AMBIGUOUS_ENTITY",
        "plan_id": "p1",
        "options": [{"id": "2", "label": "Class 5 Blue", "context": "12 students"}],
        "steps": [],
        "trace": None,
    }
    assert chat.calls == [(None, ChatTurn(message="class 5 ko reminder"), "user-token")]


@pytest.mark.parametrize(
    "body",
    [{}, {"message": "hi", "confirm": True}, {"message": ""}, {"confirm": True, "extra": 1},
     {"session_id": "not a plain id", "confirm": True}],
)  # fmt: skip
async def test_a_turn_must_be_exactly_one_thing(body: dict[str, Any]) -> None:
    async with client_for(FakeChat(QUESTION)) as client:
        response = await client.post("/chat", json=body, headers=AUTH)

    assert response.status_code == 422


@pytest.mark.parametrize(
    "headers", [{}, {"Authorization": "Basic abc"}, {"Authorization": "Bearer "}]
)
async def test_no_bearer_token_is_401(headers: dict[str, str]) -> None:
    async with client_for(FakeChat(QUESTION)) as client:
        response = await client.post("/chat", json={"confirm": True}, headers=headers)

    assert response.status_code == 401


async def test_a_token_the_backend_refuses_is_401() -> None:
    refusal = GatewayRejectedError(
        401, AgentErrorResponse(code="UNAUTHENTICATED", message="Sign in")
    )

    async with client_for(FakeChat(refusal)) as client:
        response = await client.post("/chat", json={"confirm": True}, headers=AUTH)

    assert response.status_code == 401


async def test_chat_off_or_backend_down_is_503() -> None:
    async with client_for(None) as client:
        off = await client.post("/chat", json={"confirm": True}, headers=AUTH)
    async with client_for(FakeChat(GatewayUnavailableError("down"))) as client:
        down = await client.post("/chat", json={"confirm": True}, headers=AUTH)

    assert (off.status_code, down.status_code) == (503, 503)


async def test_a_turn_that_asks_for_the_trace_gets_every_stage_back() -> None:
    async with client_for(FakeChat(QUESTION)) as client:
        response = await client.post(
            "/chat", json={"message": "class 5 ko reminder", "trace": True}, headers=AUTH
        )

    (stage,) = response.json()["trace"]
    assert stage["key"] == "session" and stage["status"] == "ok"
    assert stage["input"] == {"turn": "class 5 ko reminder"}
    assert stage["output"] == {"user_id": "7"}
    assert stage["duration_ms"] >= 0
    assert "user-token" not in response.text


async def test_the_trace_can_be_turned_off() -> None:
    settings = Settings(chat_trace_enabled=False)

    async with client_for(FakeChat(QUESTION), settings) as client:
        response = await client.post("/chat", json={"message": "hi", "trace": True}, headers=AUTH)

    assert response.status_code == 200
    assert response.json()["trace"] is None
