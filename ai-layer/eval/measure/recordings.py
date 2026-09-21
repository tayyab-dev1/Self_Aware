"""Recorded model answers, so a measurement can run again without calling the models.

Each model call (decompose, plan, and the chooser's choose) gets its own file under
``eval/recordings/``. It holds the pinned model, a hash of the exact system prompt (for the chooser,
its instructions), ``"thinking": false`` when the call is made without extended thinking, and the
raw answer for each sentence: before validation, so a changed validator
is measured against the same answers. Changing any of these starts a fresh file.

    record   (``make measure``)     answers already recorded are replayed; missing ones are asked
                                   of the model and saved. A changed prompt starts a fresh file.
    replay   (``make measure-ci``)  nothing is asked. A missing answer, or a recording made for
                                   another prompt, fails the run: re-record with ``make measure``.

A planner answer is recorded per sentence, not per candidate list. Replaying it after retrieval
changed is an approximation: the validator still checks the recorded answer against today's
candidates, so a capability retrieval no longer offers shows up as NOT_A_CANDIDATE.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any, Literal, Protocol

from app.choosing.jev import DecisionModel, DecisionRequest
from app.llm.runner import ModelOutputError, ModelRequest, StructuredModel

RECORDINGS_DIR = Path(__file__).resolve().parents[1] / "recordings"
MODE_VARIABLE = "EVAL_MODEL_CALLS"

Mode = Literal["record", "replay"]


def mode_from_environment() -> Mode:
    mode = os.environ.get(MODE_VARIABLE, "record")
    if mode not in ("record", "replay"):
        raise ValueError(f"{MODE_VARIABLE} must be record or replay, not {mode!r}")
    return mode  # type: ignore[return-value]


class Recordable(Protocol):
    """A model call's request, as far as recordings care: ModelRequest or DecisionRequest."""

    @property
    def system(self) -> str: ...

    @property
    def thinking(self) -> bool: ...


Ask = Callable[[], Awaitable[dict[str, Any]]]


class MissingRecordingError(RuntimeError):
    """Replay found no recorded answer, or a recording made for a different prompt."""


def _sha256(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


class Recordings:
    """One model call's recorded answers. ``system`` is the exact system prompt the call sends."""

    def __init__(
        self,
        purpose: str,
        model: str,
        system: str,
        *,
        mode: Mode,
        thinking: bool = True,
        directory: Path = RECORDINGS_DIR,
    ) -> None:
        self.purpose = purpose
        self.mode = mode
        self._thinking = thinking
        self._path = directory / f"{purpose}.json"
        self._header: dict[str, Any] = {"model": model, "system_prompt_sha256": _sha256(system)}
        if not thinking:
            self._header["thinking"] = False
        stored = json.loads(self._path.read_text()) if self._path.exists() else None
        if stored is not None and {k: stored.get(k) for k in self._header} == self._header:
            self._answers: dict[str, Any] = stored["answers"]
        elif mode == "replay":
            reason = (
                "no recording"
                if stored is None
                else "a recording for another model, prompt or thinking setting"
            )
            raise MissingRecordingError(
                f"{self._path.name}: {reason}. Re-record with `make measure`."
            )
        else:
            self._answers = {}
        self._lock = asyncio.Lock()
        self.asked = 0

    def model_for(self, sentence: str, model: StructuredModel | None) -> StructuredModel:
        """A model answering this sentence's call from the recording, asking ``model`` if needed."""
        return _RecordedModel(self, sentence, model)

    def decider_for(self, sentence: str, model: DecisionModel | None) -> DecisionModel:
        """The same, for a System One call (the chooser's)."""
        return _RecordedDecider(self, sentence, model)

    async def answer(self, sentence: str, request: Recordable, ask: Ask | None) -> dict[str, Any]:
        if _sha256(request.system) != self._header["system_prompt_sha256"]:
            raise MissingRecordingError(f"{self._path.name} was opened for a different prompt")
        if request.thinking != self._thinking:
            raise MissingRecordingError(
                f"{self._path.name} was opened for another thinking setting"
            )
        if sentence in self._answers:
            return _replayed(self._answers[sentence])
        if self.mode == "replay" or ask is None:
            raise MissingRecordingError(
                f"{self._path.name} has no {self.purpose} answer for a sentence. "
                "Re-record with `make measure`."
            )
        try:
            stored: Any = await ask()
        except ModelOutputError as exc:
            # The model answered badly: that is its behaviour, so it is recorded. An outage
            # (ModelUnavailableError) is not, and fails the run so the next one asks again.
            stored = {"__model_call_failed__": f"{type(exc).__name__}: {exc}"}
        async with self._lock:
            self._answers[sentence] = stored
            self.asked += 1
            self._save()
        return _replayed(stored)

    def recorded(self, sentence: str) -> dict[str, Any] | None:
        """The raw recorded answer for a sentence, if it is a real answer."""
        stored = self._answers.get(sentence)
        if not isinstance(stored, dict) or "__model_call_failed__" in stored:
            return None
        return dict(stored)

    def _save(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        body = {**self._header, "answers": dict(sorted(self._answers.items()))}
        temporary = self._path.with_suffix(".tmp")
        temporary.write_text(json.dumps(body, ensure_ascii=False, indent=1) + "\n")
        temporary.replace(self._path)


class ModelCallFailedError(RuntimeError):
    """A recorded model call that failed (for example, a timeout) when it was recorded."""


def _replayed(stored: Any) -> dict[str, Any]:
    if isinstance(stored, dict) and "__model_call_failed__" in stored:
        raise ModelCallFailedError(stored["__model_call_failed__"])
    return dict(stored)


class _RecordedModel:
    def __init__(
        self, recordings: Recordings, sentence: str, model: StructuredModel | None
    ) -> None:
        self._recordings = recordings
        self._sentence = sentence
        self._model = model

    async def generate(self, request: ModelRequest) -> dict[str, Any]:
        model = self._model
        ask = (lambda: model.generate(request)) if model is not None else None
        return await self._recordings.answer(self._sentence, request, ask)


class _RecordedDecider:
    def __init__(self, recordings: Recordings, sentence: str, model: DecisionModel | None) -> None:
        self._recordings = recordings
        self._sentence = sentence
        self._model = model

    async def decide(self, request: DecisionRequest) -> dict[str, Any]:
        model = self._model
        ask = (lambda: model.decide(request)) if model is not None else None
        return await self._recordings.answer(self._sentence, request, ask)
