"""Model calls through the Gemini API, with Google's Gen AI SDK.

Each call sends the pinned model, the system prompt as the system instruction, the user-turn text
as the only content, and the schema as structured output (JSON). There are no tools and no chat
history: one request, one JSON object back. One SDK client serves every call, so connections are
reused instead of opened per call.

``thinking=False`` asks for ``minimal`` thinking, Gemini 3's lowest level. Google's docs say it
matches "no thinking" for most requests but does not guarantee it. ``thinking=True`` asks for
``high``, where the model decides how much to think, as the planner did before. Changing either
level changes what the model answers, so it means re-recording (``make measure``). Temperature is
left at Gemini 3's default of 1.0, as Google recommends.

Gemini accepts a subset of JSON Schema, so ``gemini_schema`` rewrites the schema into it first
(local ``$ref``s inlined, a list of types as ``anyOf``, undocumented keywords such as length limits
dropped). Nothing is lost: every answer is still parsed with ``extra="forbid"`` models and
validated.

The SDK retries 408, 429 and 5xx itself; the whole call, retries included, is bounded by
``AI_LAYER_MODEL_TIMEOUT_SECONDS``.
"""

from __future__ import annotations

import asyncio
import json
import time
from collections.abc import Mapping
from typing import Any, Self

import httpx
import structlog
from google import genai
from google.genai import errors, types

from app.core.settings import Settings
from app.llm.runner import ModelOutputError, ModelRequest, ModelUnavailableError

log = structlog.get_logger(__name__)

THINKING_LEVELS = {False: types.ThinkingLevel.MINIMAL, True: types.ThinkingLevel.HIGH}
# Attempts per call, the first included. The SDK's default of 5, with backoff up to 60 s, is too
# patient for someone waiting in a chat.
RETRY_ATTEMPTS = 3

# The JSON Schema keywords Gemini's structured output documents. Anything else is dropped.
_KEPT_KEYWORDS = frozenset(
    {
        "type",
        "title",
        "description",
        "properties",
        "required",
        "additionalProperties",
        "enum",
        "format",
        "minimum",
        "maximum",
        "items",
        "prefixItems",
        "minItems",
        "maxItems",
        "anyOf",
    }
)
_LOCAL_REF_PREFIXES = ("#/$defs/", "#/definitions/")


def gemini_schema(schema: Mapping[str, Any]) -> dict[str, Any]:
    """The same schema, written in the subset of JSON Schema that Gemini accepts."""
    definitions = {**schema.get("definitions", {}), **schema.get("$defs", {})}
    return _rewrite(schema, definitions, ())


def _rewrite(
    node: Mapping[str, Any], definitions: Mapping[str, Any], resolving: tuple[str, ...]
) -> dict[str, Any]:
    reference = node.get("$ref")
    if isinstance(reference, str) and reference.startswith(_LOCAL_REF_PREFIXES):
        name = reference.rsplit("/", 1)[1]
        if name in resolving:
            raise ValueError(f"The schema refers to {name!r} inside itself; Gemini cannot take it")
        if name not in definitions:
            raise ValueError(f"The schema refers to an undefined {name!r}")
        return _rewrite(definitions[name], definitions, (*resolving, name))

    out: dict[str, Any] = {}
    for key, value in node.items():
        if key not in _KEPT_KEYWORDS:
            continue
        if key == "properties":
            out[key] = {name: _rewrite(sub, definitions, resolving) for name, sub in value.items()}
        elif key in ("items", "additionalProperties") and isinstance(value, Mapping):
            out[key] = _rewrite(value, definitions, resolving)
        elif key in ("anyOf", "prefixItems"):
            out[key] = [_rewrite(sub, definitions, resolving) for sub in value]
        else:
            out[key] = value

    types_listed = out.get("type")
    if isinstance(types_listed, list):
        # Gemini documents a list of types only for making one type nullable.
        others = [kind for kind in types_listed if kind != "null"]
        if len(others) > 1:
            del out["type"]
            nullable = [{"type": "null"}] if "null" in types_listed else []
            out["anyOf"] = [{"type": kind} for kind in others] + nullable
    return out


class GeminiModel:
    def __init__(self, client: genai.Client, *, timeout_seconds: float) -> None:
        self._client = client
        self._timeout = timeout_seconds

    @classmethod
    def from_settings(cls, settings: Settings) -> Self:
        api_key = settings.gemini_api_key.get_secret_value()
        if not api_key:
            raise ModelUnavailableError(
                "No Gemini API key. Set AI_LAYER_GEMINI_API_KEY to a key from Google AI Studio."
            )
        client = genai.Client(
            api_key=api_key,
            http_options=types.HttpOptions(
                timeout=int(settings.model_timeout_seconds * 1000),
                retry_options=types.HttpRetryOptions(attempts=RETRY_ATTEMPTS),
            ),
        )
        return cls(client, timeout_seconds=settings.model_timeout_seconds)

    def config(self, request: ModelRequest) -> types.GenerateContentConfig:
        return types.GenerateContentConfig(
            system_instruction=request.system,
            response_mime_type="application/json",
            response_json_schema=gemini_schema(request.schema),
            thinking_config=types.ThinkingConfig(thinking_level=THINKING_LEVELS[request.thinking]),
        )

    async def generate(self, request: ModelRequest) -> dict[str, Any]:
        started = time.monotonic()
        try:
            response = await asyncio.wait_for(
                self._client.aio.models.generate_content(
                    model=request.model, contents=request.prompt, config=self.config(request)
                ),
                timeout=self._timeout,
            )
        except TimeoutError as exc:
            raise ModelUnavailableError(
                f"The {request.purpose} call timed out after {self._timeout:g}s"
            ) from exc
        except errors.APIError as exc:
            # Google's error text says what to fix (a bad key, a used-up quota) and never
            # repeats the prompt, so it is safe to pass on.
            reason = f"{exc.code} {exc.status or ''}: {(exc.message or '')[:200]}".strip()
            raise ModelUnavailableError(f"The {request.purpose} call failed: {reason}") from exc
        except httpx.HTTPError as exc:
            raise ModelUnavailableError(
                f"The {request.purpose} call could not reach Gemini ({type(exc).__name__})"
            ) from exc
        return _structured_output(request, response, time.monotonic() - started)

    async def aclose(self) -> None:
        await self._client.aio.aclose()


def _structured_output(
    request: ModelRequest, response: types.GenerateContentResponse, seconds: float
) -> dict[str, Any]:
    usage = response.usage_metadata
    candidate = response.candidates[0] if response.candidates else None
    finish = candidate.finish_reason if candidate is not None else None
    log.info(
        "model_call",
        purpose=request.purpose,
        model=request.model,
        duration_ms=round(seconds * 1000),
        input_tokens=usage.prompt_token_count if usage else None,
        output_tokens=usage.candidates_token_count if usage else None,
        thinking_tokens=usage.thoughts_token_count if usage else None,
        outcome=finish.name if finish is not None else None,
    )
    if candidate is None:
        blocked = response.prompt_feedback.block_reason if response.prompt_feedback else None
        raise ModelOutputError(
            f"The {request.purpose} call returned no answer"
            + (f" (the prompt was blocked: {blocked.name})" if blocked else "")
        )
    if finish is not types.FinishReason.STOP:
        raise ModelOutputError(
            f"The {request.purpose} call stopped early ({finish.name if finish else 'no reason'})"
        )
    try:
        output = json.loads(response.text or "")
    except ValueError as exc:
        raise ModelOutputError(f"The {request.purpose} call returned no JSON") from exc
    if not isinstance(output, dict):
        raise ModelOutputError(f"The {request.purpose} call returned JSON that is not an object")
    return output
