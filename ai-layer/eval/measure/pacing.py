"""Spacing live model calls, for recording runs on a rate-limited key.

The free Gemini tier allows 15 requests a minute per project and model. A refused call is not
recorded and its quota is spent anyway, so a recording run that goes too fast makes no progress.
``EVAL_CALLS_PER_MINUTE=12 make measure`` spaces the calls instead. Replayed answers never wait.
"""

from __future__ import annotations

import asyncio
import os
import time
from typing import Any

from app.llm.runner import ModelRequest, StructuredModel

VARIABLE = "EVAL_CALLS_PER_MINUTE"


class Paced:
    """A model whose calls start at most ``per_minute`` times a minute."""

    def __init__(self, model: StructuredModel, per_minute: float) -> None:
        self._model = model
        self._gap = 60 / per_minute
        self._next = 0.0
        self._lock = asyncio.Lock()

    async def generate(self, request: ModelRequest) -> dict[str, Any]:
        async with self._lock:
            wait = self._next - time.monotonic()
            if wait > 0:
                await asyncio.sleep(wait)
            self._next = time.monotonic() + self._gap
        return await self._model.generate(request)


def paced(model: StructuredModel | None, per_minute: float | None = None) -> StructuredModel | None:
    """``model``, spaced when a rate is given or ``EVAL_CALLS_PER_MINUTE`` is set."""
    if model is None:
        return None
    rate = per_minute if per_minute is not None else float(os.environ.get(VARIABLE, "0"))
    return Paced(model, rate) if rate > 0 else model
