"""The local translator as the source of the search query, against a fake service."""

from __future__ import annotations

import json
from typing import Any

import httpx
import pytest

from app.core import trace
from app.core.settings import Settings
from app.decompose.translator import TranslatorDecomposer
from app.llm.runner import ModelOutputError, ModelUnavailableError

SENTENCE = "class 5 blue ke defaulters dikhao"
ENGLISH = "Show the defaulters of class 5 blue"


def translator(handler: Any, **settings: Any) -> TranslatorDecomposer:
    return TranslatorDecomposer.from_settings(
        Settings(decompose_source="translator", **settings),
        transport=httpx.MockTransport(handler),
    )


async def test_the_message_is_translated_into_one_intent() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json={"english": ENGLISH})

    decomposition = await translator(handler).decompose(SENTENCE)

    assert decomposition.texts == [ENGLISH]
    assert decomposition.intents[0].entities == ()  # names are the planner's job, from the message
    [sent] = seen
    assert str(sent.url) == "http://127.0.0.1:8099/"
    assert json.loads(sent.content) == {"text": SENTENCE}


async def test_the_translation_is_a_traced_stage_showing_both_texts() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"english": ENGLISH})

    with trace.collect() as collected:
        await translator(handler).decompose(SENTENCE)

    [stage] = collected.stages
    assert stage.key == "decompose" and stage.status == "ok"
    assert stage.input == {"message": SENTENCE}
    assert stage.output == {"english": ENGLISH}


async def test_a_service_that_is_down_is_model_unavailable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("no route")

    with pytest.raises(ModelUnavailableError, match="could not reach the translator"):
        await translator(handler).decompose(SENTENCE)


async def test_a_service_that_errors_is_model_unavailable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="overloaded")

    with pytest.raises(ModelUnavailableError, match="503"):
        await translator(handler).decompose(SENTENCE)


@pytest.mark.parametrize("body", [{"not_english": ENGLISH}, {"english": ""}, {"english": 5}])
async def test_an_answer_without_usable_english_is_an_output_error(body: dict[str, Any]) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=body)

    with pytest.raises(ModelOutputError):
        await translator(handler).decompose(SENTENCE)


async def test_warming_up_translates_once_so_the_first_message_does_not_wait() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, json={"english": "warm up"})

    await translator(handler).warm_up()

    assert calls == 1
