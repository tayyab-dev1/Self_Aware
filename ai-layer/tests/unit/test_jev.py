"""The TypeSafe System One client, against a fake API."""

from __future__ import annotations

import json
from typing import Any

import httpx
import pytest

from app.choosing import jev
from app.choosing.jev import DecisionRequest, JevModel
from app.core.settings import Settings
from app.llm.runner import ModelOutputError, ModelUnavailableError

REQUEST = DecisionRequest(
    model="jev-1.13.0",
    state={"requests": ["Show the defaulters"]},
    questions={"intent_1": {"type": "choice", "instructions": "Which?", "criteria": {"a": "A"}}},
    purpose="choose",
    system="spec",
)
ANSWER = {
    "model": "jev-1.13.0",
    "answers": {
        "intent_1": {"type": "choice", "choice": "a", "confidence": 1.0, "probabilities": {"a": 1}}
    },
    "usage": {"input_tokens": 10, "output_tokens": 2},
}


@pytest.fixture(autouse=True)
def no_waiting(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(jev, "RETRY_WAITS", (0.0, 0.0, 0.0))


def model(handler: Any, **settings: Any) -> JevModel:
    return JevModel.from_settings(
        Settings(typesafe_api_key="test-key", **settings), transport=httpx.MockTransport(handler)
    )


async def test_a_decision_posts_the_model_state_and_questions_with_the_key() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json=ANSWER)

    assert await model(handler).decide(REQUEST) == ANSWER
    [sent] = seen
    assert str(sent.url) == "https://api.typesafe.ai/v1/systemone"
    assert sent.headers["Authorization"] == "Bearer test-key"
    assert json.loads(sent.content) == {
        "model": "jev-1.13.0",
        "state": REQUEST.state,
        "questions": REQUEST.questions,
    }


async def test_rate_limits_and_overload_are_retried() -> None:
    statuses = iter([429, 529, 200])

    def handler(request: httpx.Request) -> httpx.Response:
        status = next(statuses)
        return httpx.Response(status, json=ANSWER if status == 200 else {"detail": "busy"})

    assert await model(handler).decide(REQUEST) == ANSWER


async def test_an_overload_that_lasts_is_unavailable_after_the_last_attempt() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(529, json={"detail": "overloaded"})

    with pytest.raises(ModelUnavailableError, match="529 overloaded"):
        await model(handler).decide(REQUEST)
    assert calls == jev.RETRY_ATTEMPTS


async def test_a_refused_key_is_unavailable_at_once_with_the_apis_reason() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(401, json={"detail": {"message": "Missing or invalid API key"}})

    with pytest.raises(ModelUnavailableError, match="401 Missing or invalid API key"):
        await model(handler).decide(REQUEST)
    assert calls == 1


async def test_a_body_without_answers_is_an_output_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"model": "jev-1.13.0"})

    with pytest.raises(ModelOutputError):
        await model(handler).decide(REQUEST)


def test_without_a_key_there_is_no_client() -> None:
    with pytest.raises(ModelUnavailableError, match="AI_LAYER_TYPESAFE_API_KEY"):
        JevModel.from_settings(Settings())
