from __future__ import annotations

import asyncio
import dataclasses
import json
from typing import Any

import httpx
import pytest
from google.genai import errors, types

from app.core.settings import Settings
from app.decompose.decomposer import SCHEMA as DECOMPOSE_SCHEMA
from app.llm.gemini import GeminiModel, gemini_schema, hide_key
from app.llm.runner import DECOMPOSE_MODEL, ModelOutputError, ModelRequest, ModelUnavailableError
from app.planning.outcomes import SCHEMA as PLANNER_SCHEMA

SCHEMA = {"type": "object", "properties": {"words": {"type": "integer"}}}
REQUEST = ModelRequest(
    model=DECOMPOSE_MODEL,
    system="Count the words.",
    prompt="Ahmed Raza ki fees",
    schema=SCHEMA,
    purpose="probe",
)


def answer(
    text: str | None = '{"words": 4}',
    finish: types.FinishReason = types.FinishReason.STOP,
) -> types.GenerateContentResponse:
    return types.GenerateContentResponse(
        candidates=[
            types.Candidate(
                content=types.Content(role="model", parts=[types.Part(text=text)]),
                finish_reason=finish,
            )
        ],
        usage_metadata=types.GenerateContentResponseUsageMetadata(
            prompt_token_count=20, candidates_token_count=5
        ),
    )


class FakeModels:
    """Stands in for ``client.aio.models``: records each call, answers or raises as told."""

    def __init__(self, reply: types.GenerateContentResponse | BaseException) -> None:
        self.reply = reply
        self.calls: list[dict[str, Any]] = []

    async def generate_content(self, **call: Any) -> types.GenerateContentResponse:
        self.calls.append(call)
        if isinstance(self.reply, BaseException):
            raise self.reply
        return self.reply


class FakeClient:
    def __init__(self, reply: types.GenerateContentResponse | BaseException) -> None:
        self.aio = self
        self.models = FakeModels(reply)
        self.closed = False

    async def aclose(self) -> None:
        self.closed = True


def model_answering(
    reply: types.GenerateContentResponse | BaseException, *, timeout_seconds: float = 10
) -> tuple[GeminiModel, FakeModels]:
    client = FakeClient(reply)
    return GeminiModel(client, timeout_seconds=timeout_seconds), client.models  # type: ignore[arg-type]


async def test_the_call_sends_the_pinned_model_system_prompt_and_schema_and_returns_json() -> None:
    model, calls = model_answering(answer())

    output = await model.generate(REQUEST)

    (call,) = calls.calls
    config: types.GenerateContentConfig = call["config"]
    assert output == {"words": 4}
    assert call["model"] == DECOMPOSE_MODEL
    assert call["contents"] == "Ahmed Raza ki fees"
    assert config.system_instruction == "Count the words."
    assert config.response_mime_type == "application/json"
    assert config.response_json_schema == SCHEMA
    assert config.tools is None  # a question and an answer, nothing else


async def test_each_thinking_setting_asks_for_its_level() -> None:
    model, calls = model_answering(answer())

    await model.generate(REQUEST)
    for thinking in (False, "low", "medium"):
        await model.generate(dataclasses.replace(REQUEST, thinking=thinking))

    levels = [call["config"].thinking_config.thinking_level for call in calls.calls]
    assert levels == [
        types.ThinkingLevel.HIGH,
        types.ThinkingLevel.MINIMAL,
        types.ThinkingLevel.LOW,
        types.ThinkingLevel.MEDIUM,
    ]


def test_the_schema_is_rewritten_into_what_gemini_accepts() -> None:
    schema = {
        "$defs": {"name": {"type": "string", "minLength": 1, "maxLength": 50}},
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "who": {"$ref": "#/$defs/name"},
            "value": {"type": ["string", "number", "boolean"]},
            "maybe": {"type": ["string", "null"]},
            # Property names are the user's, never keywords: these must survive.
            "minLength": {"type": "integer"},
            "type": {"type": "string", "enum": ["a", "b"]},
        },
    }

    assert gemini_schema(schema) == {
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "who": {"type": "string"},
            "value": {"anyOf": [{"type": "string"}, {"type": "number"}, {"type": "boolean"}]},
            "maybe": {"type": ["string", "null"]},
            "minLength": {"type": "integer"},
            "type": {"type": "string", "enum": ["a", "b"]},
        },
    }


def test_a_schema_that_refers_to_itself_is_refused() -> None:
    schema = {"$defs": {"node": {"type": "array", "items": {"$ref": "#/$defs/node"}}}}

    with pytest.raises(ValueError, match="inside itself"):
        gemini_schema({**schema, "$ref": "#/$defs/node"})


@pytest.mark.parametrize("schema", [DECOMPOSE_SCHEMA, PLANNER_SCHEMA], ids=["decompose", "plan"])
def test_the_real_schemas_keep_their_shape_and_lose_only_what_gemini_does_not_take(
    schema: dict[str, Any],
) -> None:
    rewritten = json.dumps(gemini_schema(schema))

    assert "$ref" not in rewritten and "$defs" not in rewritten
    assert "Length" not in rewritten
    assert gemini_schema(schema)["required"] == schema["required"]


async def test_an_api_error_says_what_went_wrong_such_as_a_used_up_quota() -> None:
    quota = errors.ClientError(
        429,
        {"error": {"code": 429, "status": "RESOURCE_EXHAUSTED", "message": "Quota exceeded"}},
    )
    model, _ = model_answering(quota)

    with pytest.raises(ModelUnavailableError, match="429 RESOURCE_EXHAUSTED: Quota exceeded"):
        await model.generate(REQUEST)


async def test_a_key_named_in_googles_error_text_never_leaves_the_client() -> None:
    key = "AQ.Ab8Rtestonlynotarealkey0123456789"
    suspended = errors.ClientError(
        403,
        {
            "error": {
                "code": 403,
                "status": "PERMISSION_DENIED",
                "message": f"Permission denied: Consumer 'api_key:{key}' has been suspended.",
            }
        },
    )
    client = FakeClient(suspended)
    model = GeminiModel(client, timeout_seconds=10, api_key=key)  # type: ignore[arg-type]

    with pytest.raises(ModelUnavailableError) as raised:
        await model.generate(REQUEST)

    assert "api_key:[hidden]" in str(raised.value)
    assert "Ab8R" not in str(raised.value)


def test_a_key_is_hidden_even_when_only_its_label_gives_it_away() -> None:
    hidden = hide_key("Consumer 'api_key:AQ.other' is off", "")
    assert hidden == "Consumer 'api_key:[hidden]' is off"
    assert hide_key("the key AIzaSecret leaked", "AIzaSecret") == "the key [hidden] leaked"


async def test_an_unreachable_api_is_model_unavailable() -> None:
    model, _ = model_answering(httpx.ConnectError("no route"))

    with pytest.raises(ModelUnavailableError, match="could not reach Gemini"):
        await model.generate(REQUEST)


async def test_a_slow_call_gives_up_at_the_timeout() -> None:
    class Slow(FakeModels):
        async def generate_content(self, **call: Any) -> types.GenerateContentResponse:
            await asyncio.sleep(30)
            raise AssertionError("never reached")

    client = FakeClient(answer())
    client.models = Slow(answer())
    model = GeminiModel(client, timeout_seconds=0.2)  # type: ignore[arg-type]

    with pytest.raises(ModelUnavailableError, match="timed out"):
        await model.generate(REQUEST)


async def test_a_blocked_prompt_is_a_model_output_error() -> None:
    blocked = types.GenerateContentResponse(
        prompt_feedback=types.GenerateContentResponsePromptFeedback(
            block_reason=types.BlockedReason.SAFETY
        )
    )
    model, _ = model_answering(blocked)

    with pytest.raises(ModelOutputError, match="blocked: SAFETY"):
        await model.generate(REQUEST)


async def test_an_answer_cut_off_early_is_a_model_output_error() -> None:
    model, _ = model_answering(answer('{"words": ', types.FinishReason.MAX_TOKENS))

    with pytest.raises(ModelOutputError, match="stopped early \\(MAX_TOKENS\\)"):
        await model.generate(REQUEST)


@pytest.mark.parametrize("text", ["four", "[4]", None])
async def test_an_answer_that_is_not_a_json_object_is_a_model_output_error(
    text: str | None,
) -> None:
    model, _ = model_answering(answer(text))

    with pytest.raises(ModelOutputError):
        await model.generate(REQUEST)


def test_a_missing_api_key_is_reported_with_the_setting_to_fix_it() -> None:
    with pytest.raises(ModelUnavailableError, match="AI_LAYER_GEMINI_API_KEY"):
        GeminiModel.from_settings(Settings(gemini_api_key=""))


async def test_closing_the_model_closes_its_client() -> None:
    client = FakeClient(answer())

    await GeminiModel(client, timeout_seconds=1).aclose()  # type: ignore[arg-type]

    assert client.closed
