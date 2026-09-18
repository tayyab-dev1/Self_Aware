"""What one chat turn did, stage by stage: for showing the pipeline on the test page.

Tracing is off unless a request asks for it (``POST /chat`` with ``"trace": true``) and the
setting ``AI_LAYER_CHAT_TRACE_ENABLED`` allows it. While it is on, each stage of the pipeline
records what it was given, what it did and what it produced. The trace lives in a context variable,
so a stage deep inside retrieval or planning records itself without every function passing a trace
along; with no trace active, ``stage`` still runs its block and simply keeps nothing.

A trace goes back only to the caller who sent the turn, who already holds the sentence. It is never
logged. Secrets never go in: not the user's token, not the backend's confirmation token.
"""

from __future__ import annotations

import time
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Any, Literal

from pydantic_core import to_jsonable_python

# ok: done. failed: raised an error. skipped: not needed this turn. paused: waits for the user.
StageStatus = Literal["ok", "failed", "skipped", "paused"]

MAX_TEXT = 20_000

# Who does a stage, as the page shows it.
BY_CODE = "AI layer · code, no model"
BY_BACKEND = "Backend · agent gateway"
BY_EMBEDDINGS = "AI layer · embeddings + Postgres search"


def by_model(model: str) -> str:
    return f"Model call · {model}"


@dataclass(slots=True)
class Stage:
    """One stage. ``actor`` says who does it: a model, the AI layer's own code, or the backend."""

    key: str
    title: str
    actor: str
    does: str
    input: Any = None
    output: Any = None
    status: StageStatus = "ok"
    note: str | None = None
    duration_ms: float | None = None

    def as_json(self) -> dict[str, Any]:
        return {
            "key": self.key,
            "title": self.title,
            "actor": self.actor,
            "does": self.does,
            "input": _jsonable(self.input),
            "output": _jsonable(self.output),
            "status": self.status,
            "note": self.note,
            "duration_ms": None if self.duration_ms is None else round(self.duration_ms, 1),
        }


@dataclass(slots=True)
class Trace:
    stages: list[Stage] = field(default_factory=list)

    def as_json(self) -> list[dict[str, Any]]:
        return [stage.as_json() for stage in self.stages]


_current: ContextVar[Trace | None] = ContextVar("chat_trace", default=None)


@contextmanager
def collect() -> Iterator[Trace]:
    """Record every stage run inside this block."""
    trace = Trace()
    reset = _current.set(trace)
    try:
        yield trace
    finally:
        _current.reset(reset)


def active() -> bool:
    """Whether stages are being kept. Check it before building an expensive input or output."""
    return _current.get() is not None


@contextmanager
def stage(key: str, title: str, actor: str, does: str) -> Iterator[Stage]:
    """Time the block and keep the stage, in the order stages start.

    An exception marks the stage failed with its message, and is raised on unchanged.
    """
    record = Stage(key, title, actor, does)
    trace = _current.get()
    if trace is not None:
        trace.stages.append(record)
    started = time.perf_counter()
    try:
        yield record
    except Exception as exc:
        record.status = "failed"
        record.note = record.note or f"{type(exc).__name__}: {exc}"[:500]
        raise
    finally:
        record.duration_ms = (time.perf_counter() - started) * 1000


def note(key: str, title: str, actor: str, does: str, **fields: Any) -> Stage:
    """Keep a stage that takes no time of its own (a decision, or a stage that was skipped)."""
    record = Stage(key, title, actor, does, **fields)
    trace = _current.get()
    if trace is not None:
        trace.stages.append(record)
    return record


def _jsonable(value: Any) -> Any:
    converted = to_jsonable_python(value, fallback=str)
    return _cut(converted)


def _cut(value: Any) -> Any:
    if isinstance(value, str) and len(value) > MAX_TEXT:
        return value[:MAX_TEXT] + f"… ({len(value) - MAX_TEXT} more characters)"
    if isinstance(value, list):
        return [_cut(item) for item in value]
    if isinstance(value, dict):
        return {key: _cut(item) for key, item in value.items()}
    return value
