"""Decisions through TypeSafe's System One API (``POST /v1/systemone``), answered by Jev.

A System One model writes no text: it gets a state and typed questions, and answers every question
at once with a pick from the options it was given and a probability for each option. It cannot
answer with anything that was not an option, but it can pick the wrong one, so what it answers is
still checked by the caller.

The API asks to retry 429 (rate limited) and 529 (overloaded) with backoff; 5xx and a dropped
connection are retried the same way. The whole call, retries included, is bounded by
``AI_LAYER_CHOOSER_TIMEOUT_SECONDS``.
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Protocol, Self

import httpx
import structlog

from app.core.settings import Settings
from app.llm.runner import ModelOutputError, ModelUnavailableError

log = structlog.get_logger(__name__)

PATH = "/v1/systemone"
# Attempts per call, the first included, and the wait before each retry.
RETRY_ATTEMPTS = 4
RETRY_WAITS = (0.5, 1.0, 2.0)
RETRY_STATUSES = frozenset({429, 500, 502, 503, 504, 529})
# How long an idle connection is kept for the next call. httpx's default of 5 s closes it between
# chat turns, and a new connection to TypeSafe costs 0.7-1 s (TLS over a ~330 ms round trip): a
# call took 1.1-1.5 s after 10 s idle, and 0.44-0.48 s even after 60 s idle with this.
KEEPALIVE_SECONDS = 120.0


@dataclass(frozen=True, slots=True)
class DecisionRequest:
    model: str
    state: Mapping[str, Any]
    questions: Mapping[str, Any]
    # For logs and metrics only; never the state, which holds the user's words.
    purpose: str
    # What stays the same from call to call (the instructions and how options are written), so
    # recordings know which version of the question an answer belongs to.
    system: str

    @property
    def thinking(self) -> bool:
        # A System One model has no thinking setting; recordings ask every call for one.
        return True

    def body(self) -> dict[str, Any]:
        return {"model": self.model, "state": self.state, "questions": self.questions}


class DecisionModel(Protocol):
    async def decide(self, request: DecisionRequest) -> dict[str, Any]: ...


class JevModel:
    def __init__(self, client: httpx.AsyncClient, *, timeout_seconds: float) -> None:
        self._client = client
        self._timeout = timeout_seconds

    @classmethod
    def from_settings(
        cls, settings: Settings, *, transport: httpx.AsyncBaseTransport | None = None
    ) -> Self:
        api_key = settings.typesafe_api_key.get_secret_value()
        if not api_key:
            raise ModelUnavailableError(
                "No TypeSafe API key. Set AI_LAYER_TYPESAFE_API_KEY to a key from "
                "console.typesafe.ai."
            )
        client = httpx.AsyncClient(
            base_url=str(settings.typesafe_base_url).rstrip("/"),
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=settings.chooser_timeout_seconds,
            limits=httpx.Limits(keepalive_expiry=KEEPALIVE_SECONDS),
            transport=transport,
        )
        return cls(client, timeout_seconds=settings.chooser_timeout_seconds)

    async def decide(self, request: DecisionRequest) -> dict[str, Any]:
        started = time.monotonic()
        try:
            response, attempts = await asyncio.wait_for(self._post(request), self._timeout)
        except TimeoutError as exc:
            raise ModelUnavailableError(
                f"The {request.purpose} call timed out after {self._timeout:g}s"
            ) from exc
        except httpx.HTTPError as exc:
            raise ModelUnavailableError(
                f"The {request.purpose} call could not reach TypeSafe ({type(exc).__name__})"
            ) from exc
        body = _json(response)
        usage = body.get("usage") if isinstance(body, dict) else None
        log.info(
            "model_call",
            purpose=request.purpose,
            model=request.model,
            duration_ms=round((time.monotonic() - started) * 1000),
            attempts=attempts,
            status=response.status_code,
            input_tokens=usage.get("input_tokens") if isinstance(usage, dict) else None,
        )
        if response.status_code != 200:
            # TypeSafe's error text says what to fix (a bad key, an unknown model) and never
            # repeats the state, so it is safe to pass on.
            raise ModelUnavailableError(
                f"The {request.purpose} call failed: {response.status_code} {_detail(body)}"
            )
        if not isinstance(body, dict) or not isinstance(body.get("answers"), dict):
            raise ModelOutputError(f"The {request.purpose} call returned no answers")
        return body

    async def _post(self, request: DecisionRequest) -> tuple[httpx.Response, int]:
        for attempt in range(1, RETRY_ATTEMPTS + 1):
            last = attempt == RETRY_ATTEMPTS
            try:
                response = await self._client.post(PATH, json=request.body())
            except (httpx.ConnectError, httpx.RemoteProtocolError, httpx.ReadError):
                if last:
                    raise
            else:
                if response.status_code not in RETRY_STATUSES or last:
                    return response, attempt
            await asyncio.sleep(RETRY_WAITS[attempt - 1])
        raise AssertionError("unreachable: the last attempt returns or raises")

    async def aclose(self) -> None:
        await self._client.aclose()


def _json(response: httpx.Response) -> Any:
    try:
        return response.json()
    except ValueError:
        return None


def _detail(body: Any) -> str:
    detail = body.get("detail") if isinstance(body, dict) else None
    if isinstance(detail, dict):
        detail = detail.get("message")
    return str(detail or "no detail")[:200]
