"""The other way to get a search query: a local translator instead of a model call.

``Decomposer`` asks Gemini for 1-3 English intents (splitting "do X and Y", copying names, checked
against rules). This asks a small translator running beside us for one English sentence, and uses
that as the only intent. It is one HTTP call to our own machine, about 0.25 s, with no quota and no
model bill; the planner still reads the message as the user typed it.

What it gives up is measured in README decision 77: recall@30 95.4% -> 92.6%, plan accuracy
89.7% -> 87.4%. Splitting turned out not to cost retrieval anything, because 30 candidates leave
room for both actions of a two-part message.

Which one runs is ``AI_LAYER_DECOMPOSE_SOURCE``. The service is hasyarshad/roman-urdu-translator
behind an HTTP endpoint that takes ``{"text": ...}`` and answers ``{"english": ...}``; the direction
is forced to English there, never guessed (guessing costs 4 points of recall on mixed sentences).
"""

from __future__ import annotations

from typing import Self

import httpx
import structlog

from app.core import trace
from app.core.settings import Settings
from app.decompose.decomposer import Decomposition, Intent
from app.llm.runner import ModelOutputError, ModelUnavailableError

log = structlog.get_logger(__name__)

PURPOSE = "translate"
ACTOR = "AI layer · local translator"
# Idle connections are kept, so a turn does not pay to open one (as for Jev, decision 74).
KEEPALIVE_SECONDS = 120.0


class TranslatorDecomposer:
    """One English sentence per message, from the translator service, as the only intent."""

    def __init__(self, client: httpx.AsyncClient) -> None:
        self._client = client

    @classmethod
    def from_settings(
        cls, settings: Settings, *, transport: httpx.AsyncBaseTransport | None = None
    ) -> Self:
        client = httpx.AsyncClient(
            base_url=str(settings.translator_base_url).rstrip("/"),
            timeout=settings.translator_timeout_seconds,
            limits=httpx.Limits(keepalive_expiry=KEEPALIVE_SECONDS),
            transport=transport,
        )
        return cls(client)

    async def decompose(self, sentence: str) -> Decomposition:
        """Raises ``ModelError`` when the service cannot be reached or answers badly."""
        with trace.stage(
            "decompose",
            "Translate",
            ACTOR,
            "Turns the message into one English sentence for search. No splitting into intents, "
            "no model call: the translator runs beside this service.",
        ) as stage:
            stage.input = {"message": sentence}
            english = await self._translate(sentence)
            stage.output = {"english": english}
        return Decomposition((Intent(english, ()),))

    async def _translate(self, sentence: str) -> str:
        try:
            response = await self._client.post("/", json={"text": sentence})
        except httpx.HTTPError as exc:
            raise ModelUnavailableError(
                f"The {PURPOSE} call could not reach the translator ({type(exc).__name__})"
            ) from exc
        if response.status_code != 200:
            raise ModelUnavailableError(f"The {PURPOSE} call failed: {response.status_code}")
        try:
            english = response.json()["english"]
        except (ValueError, KeyError, TypeError) as exc:
            raise ModelOutputError(f"The {PURPOSE} call returned no english") from exc
        if not isinstance(english, str) or not english.strip():
            raise ModelOutputError(f"The {PURPOSE} call returned an empty english")
        return english.strip()

    async def warm_up(self) -> None:
        """Load the model and open the connection, so the first message does not wait for it."""
        await self._translate("warm up")

    async def aclose(self) -> None:
        await self._client.aclose()
